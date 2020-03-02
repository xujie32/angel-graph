package com.tencent.angel.graph.core.psf.get

import java.util

import com.tencent.angel.PartitionKey
import com.tencent.angel.graph.VertexId
import com.tencent.angel.graph.core.psf.common.{NonSplitter, RangeSplitter, Singular, Splitter}
import com.tencent.angel.graph.core.psf.utils.ParamSerDe
import com.tencent.angel.graph.utils.{GUtils, SerDe}

import scala.reflect.runtime.universe._
import com.tencent.angel.ml.matrix.psf.get.base.{GetParam, PartitionGetParam}
import com.tencent.angel.psagent.PSAgentContext
import io.netty.buffer.ByteBuf

class GGetParam[T: TypeTag](mId: Int, params: T, getFuncId: Int, mergeFuncId: Int)
  extends GetParam(mId) {

  private val typeTag = implicitly[TypeTag[T]]
  private val tpe = typeTag.tpe

  override def split(): util.List[PartitionGetParam] = {
    val parts: util.List[PartitionKey] = PSAgentContext.get.getMatrixMetaManager
      .getPartitions(matrixId)

    if (tpe =:= typeOf[Array[VertexId]]) {
      val splitters = RangeSplitter.getSplit(params.asInstanceOf[Array[VertexId]], parts)

      val splits = new util.ArrayList[PartitionGetParam](splitters.size)
      splitters.foreach { splitter =>
        val pp = new GPartitionGetParam(matrixId, splitter.part, splitter, typeTag, params,
          getFuncId, mergeFuncId)
        splits.add(pp)
      }

      splits
    } else if (GUtils.isFastMap(tpe)) {
      val splitters = RangeSplitter.getSplit(GUtils.getFastMapKeys(params), parts)

      val splits = new util.ArrayList[PartitionGetParam](splitters.size)
      splitters.foreach { splitter =>
        val pp = new GPartitionGetParam(matrixId, splitter.part, splitter, typeTag, params,
          getFuncId, mergeFuncId)
        splits.add(pp)
      }

      splits
    } else if (tpe <:< typeOf[Singular]) {
      val splits = new util.ArrayList[PartitionGetParam]()
      val idx = params.asInstanceOf[Singular].partition
      val pp = new GPartitionGetParam(matrixId, parts.get(idx), NonSplitter(), typeTag, params,
        getFuncId, mergeFuncId)
      splits.add(pp)
      splits
    } else {
      try {
        val splits = new util.ArrayList[PartitionGetParam](parts.size())
        (0 until parts.size()).foreach { idx =>
          val pp = new GPartitionGetParam(matrixId, parts.get(idx), NonSplitter(), typeTag, params,
            getFuncId, mergeFuncId)
          splits.add(pp)
        }

        splits
      } catch {
        case e: Exception => throw e
      }
    }
  }
}

object GGetParam {
  def empty(mId: Int, getFuncId: Int, mergeFuncId: Int): GGetParam[Byte] = {
    new GGetParam[Byte](mId, 0.toByte, getFuncId, mergeFuncId)
  }

  def apply[T: TypeTag](mId: Int, params: T, getFuncId: Int, mergeFuncId: Int): GGetParam[T] = {
    new GGetParam[T](mId, params, getFuncId, mergeFuncId)
  }
}


class GPartitionGetParam(mId: Int, pKey: PartitionKey, splitter: Splitter,
                         var tt: TypeTag[_], var params: Any, var getFunc: Any, var mergeFunc: Any)
  extends PartitionGetParam(mId, pKey) {

  def this() = this(0, null, null, null, null, null, null)

  override def serialize(buf: ByteBuf): Unit = {
    super.serialize(buf)
    ParamSerDe.serializeSplit(splitter, tt, params, buf)

    val dataObj = GetOp.get(getFunc.asInstanceOf[Int])
    buf.writeInt(dataObj.length).writeBytes(dataObj)

    buf.writeInt(mergeFunc.asInstanceOf[Int])
  }

  override def deserialize(buf: ByteBuf): Unit = {
    super.deserialize(buf)
    val (t, p) = ParamSerDe.deserializeSplit(buf)

    tt = t
    params = p

    getFunc = SerDe.javaDeserialize[GetOp](buf)
    mergeFunc = buf.readInt()
  }

  override def bufferLen(): Int = {
    var len = super.bufferLen()
    len += ParamSerDe.bufferLenSplit(splitter, tt, params)
    len += GetOp.get(getFunc.asInstanceOf[Int]).length + 8
    len
  }
}
