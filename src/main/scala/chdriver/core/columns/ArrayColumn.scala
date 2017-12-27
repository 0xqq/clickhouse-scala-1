package chdriver.core.columns

import java.io.{DataInputStream, DataOutputStream}
import java.util.ArrayDeque

import chdriver.core.DriverException
import chdriver.core.DriverProperties.DEFAULT_INSERT_BLOCK_SIZE
import chdriver.core.Protocol.DataOutputStreamOps

class ArrayColumn private[columns] (_data: Array[Any], val inner: Column) extends Column {
  override type T = Any
  override val data = _data

  override def writeTo(out: DataOutputStream, toRow: Int): Unit = {
    require(data.nonEmpty)

    // `inner` may be an ArrayColumn, and we need innermost Column (that is not an ArrayColumn) to send data
    val innermost = {
      var innermost = inner
      while (innermost.isInstanceOf[ArrayColumn]) innermost = innermost.asInstanceOf[ArrayColumn].inner
      innermost
    }

    val q = new ArrayDeque[(Array[_], Int)]()
    q.addFirst(data -> toRow)
    var sum = 0
    var leftOnCurrentLevel = toRow
    while (!q.isEmpty) {
      val (data, onThisLevel) = q.removeFirst()

      if (data.isInstanceOf[Array[Array[_]]] || data.nonEmpty && data.head.isInstanceOf[Array[_]]) { // data contains nodes
        data.take(onThisLevel).foreach { v =>
          val innerArray = v.asInstanceOf[Array[_]]
          val size = innerArray.length
          sum += size
          out.writeInt64(sum)
          leftOnCurrentLevel -= 1
          q.addLast(innerArray -> size)
        }
      } else { // data contains leaves
        System.arraycopy(data, 0, innermost.data, 0, data.length)
        innermost.writeTo(out, data.length)
      }

      if (leftOnCurrentLevel == 0) {
        leftOnCurrentLevel = sum
        sum = 0
      }
    }
  }

  override def chType: String = super.chType + "(" + inner.chType + ")"
}

object ArrayColumn {
  import chdriver.core.Protocol.DataInputStreamOps

  final val prefix = "Array("

  def apply(inner: Column) = new ArrayColumn(new Array[Any](DEFAULT_INSERT_BLOCK_SIZE), inner)

  /*
  for [[42,43]] [[44],[45,46]] :
  itemsNumber = 2
  length of 1 array = 1
  length of 1 array + length of 2 array = 1 + 2 = 3
  length of 1.1 array = 2
  length of 1.1 array + length of 2.1 array = 2 + 1 = 3
  length of 1.1 array + length of 2.1 array + length of 2.2 array = 2 + 1 + 2 = 5
  data = 42 43 44 45 46

  for [[[47]],[[48,49,50,51],[],[52],[53]]] :
  itemsNumber = 1
  length of 1 array = 2
  length of 1.1 array = 1
  length of 1.1 array + length of 1.2 array = 1 + 4 = 5
  length of 1.1.1 array = 1
  length of 1.1.1 array + length of 1.2.1 array = 1 + 4 = 5
  length of 1.1.1 array + length of 1.2.1 array + length of 1.2.2 array = 1 + 4 + 0 = 5
  length of 1.1.1 array + length of 1.2.1 array + length of 1.2.2 array + length of 1.2.3 array = 1 + 4 + 1 = 6
  length of 1.1.1 array + length of 1.2.1 array + length of 1.2.2 array + length of 1.2.3 array + length of 1.2.4 array = 1 + 4 + 1 + 1 = 7
  data = 47 48 49 50 50 51 52 53
   */
  def from(in: DataInputStream, itemsNumber: Int, innerType: String): ArrayColumn = {
    def fillOffsets(): ArrayDeque[Int] = {
      var level = {
        var current = 0
        var res = 1
        if (innerType.startsWith(prefix))
          while (innerType.substring(current, current + prefix.length) == prefix) {
            current += prefix.length
            res += 1
          }
        res
      }
      var onThisLevel = itemsNumber
      val q = new ArrayDeque[Int]
      while (level > 0) {
        val offsets = in.readArrayInt64(onThisLevel) // todo should be UInt64 here, but anyway exception is thrown atm
        for (offset <- offsets) {
          if (offset < 0 || (!q.isEmpty && (offset - q.getLast) > Integer.MAX_VALUE))
            throw new DriverException(s"Too big length=$offset of array, 2^31 max is supported.")
          q.addFirst(offset.toInt)
        }
        onThisLevel = q.getFirst
        level -= 1
      }
      q
    }

    def fillData(datas: ArrayDeque[(Array[Any], String)], offsets: ArrayDeque[Int]): Unit = {
      var previousOffset = 0
      var stopResetOffset = false
      while (!datas.isEmpty) {
        val (data, innerType) = datas.removeLast()
        var i = 0

        if (!stopResetOffset) previousOffset = 0

        if (!innerType.startsWith(prefix)) {
          stopResetOffset = true
          while (i < data.length && !offsets.isEmpty) {
            val o = offsets.removeLast()
            data(i) = Column.from(in, o - previousOffset, innerType).data
            previousOffset = o
            i += 1
          }
        } else {
          while (i < data.length) {
            val o = offsets.removeLast()
            val newNodes = new Array[Any](o - previousOffset)
            data(i) = newNodes
            previousOffset = o
            datas.addFirst(newNodes -> innerType.substring(prefix.length, innerType.length - 1))
            i += 1
          }
        }
      }
    }

    val data = new Array[Any](itemsNumber)
    val offsets = fillOffsets()
    val q = new ArrayDeque[(Array[Any], String)]
    q.addFirst(data -> innerType)
    fillData(q, offsets)

    new ArrayColumn(data, null) // todo might be dangerous
  }
}
