package com.tencent.angel.graph.framework

import java.util.UUID

import com.tencent.angel.graph._
import com.tencent.angel.graph.core.data._
import com.tencent.angel.graph.core.psf.common.{PSFGUCtx, PSFMCtx, Singular}
import com.tencent.angel.graph.framework.EdgeActiveness.EdgeActiveness
import com.tencent.angel.graph.framework.EdgeDirection.EdgeDirection
import com.tencent.angel.graph.utils._
import com.tencent.angel.graph.utils.psfConverters._
import com.tencent.angel.psagent.PSAgentContext
import com.tencent.angel.psagent.matrix.PSAgentMatrixMetaManager
import com.tencent.angel.spark.models.PSMatrix
import org.apache.spark.rdd.RDD
import org.apache.spark.{HashPartitioner, SparkContext}

import scala.reflect._
import scala.reflect.runtime.universe._


class Graph[VD: ClassTag : TypeTag, ED: ClassTag](val psVertices: PSMatrix,
                                                  val edges: RDD[EdgePartition[VD, ED]])
  extends Serializable with Logging {

  def this(edges: RDD[EdgePartition[VD, ED]]) = this(Graph.createPSVertices(edges), edges)

  @transient private lazy val sparkContext: SparkContext = edges.sparkContext
  @transient private lazy val matrixMetaManager: PSAgentMatrixMetaManager = PSAgentContext.get.getMatrixMetaManager

  @transient lazy val maxVertexId: VertexId = edges.mapPartitions { iter =>
    val part = iter.next()
    Iterator.single(part.maxVertexId)
  }.max()

  @transient lazy val minVertexId: VertexId = edges.mapPartitions { iter =>
    val part = iter.next()
    Iterator.single(part.minVertexId)
  }.min()

  @transient lazy val numPsPartition: Int = matrixMetaManager.getPartitions(psVertices.id).size()

  @transient lazy val vertices: RDD[NodePartition[VD]] = sparkContext
    .parallelize(0 until numPsPartition, numPsPartition).mapPartitions { iter =>
      val partId = iter.next()

      val pullAttr = psVertices.createGet { ctx: PSFGUCtx =>
        val pid = ctx.getParam[Singular]
        assert(pid.partition == ctx.partitionId)

        val partition = ctx.getPartition[VD]

        val result = new FastHashMap[VertexId, VD](partition.local2global.length)
        partition.local2global.foreach { vid => result(vid) = partition.getAttr(vid) }
        result
      } { ctx: PSFMCtx =>
        val last = ctx.getLast[FastHashMap[VertexId, VD]]
        val curr = ctx.getCurr[FastHashMap[VertexId, VD]]

        last.merge(curr)
      }

      val attrs = pullAttr(Singular(partId))
      pullAttr.clear()

      val nodePartition = new NodePartition[VD](attrs)
      Iterator.single(nodePartition)
    }

  private[graph] def withEdges(newEdges: RDD[EdgePartition[VD, ED]]): Graph[VD, ED] = new Graph(psVertices, newEdges)

  // operations on vertices, note those ops do not create new Graph, that means update vertex data inplace
  def outerJoinVertices[U: ClassTag : TypeTag](other: RDD[(VertexId, U)])(mapFunc: (VD, U) => VD): this.type = {
    other.foreachPartition { iter =>
      val fastMap = new FastHashMap[VertexId, U]()
      iter.foreach { case (k, u) => fastMap(k) = u }

      val push = psVertices.createUpdate { ctx: PSFGUCtx =>
        val params = ctx.getMapParam[U]
        val partition = ctx.getPartition[VD]

        params.foreach { case (vid, u) =>
          partition.setAttr(vid, mapFunc(partition.getAttr(vid), u))
        }
      }

      push(fastMap)
      fastMap.clear()
    }

    this
  }

  def createSlot[U: ClassTag : TypeTag](slotName: String): this.type = {
    val create = psVertices.createUpdate { ctx: PSFGUCtx =>
      val partition = ctx.getPartition[VD]
      partition.createSlot[U](slotName)
    }

    create()
    create.clear()

    this
  }

  def updateSlots[U: ClassTag : TypeTag](other: RDD[(VertexId, U)])(slotName: String, updateFunc: (U, U) => U): this.type = {
    other.foreachPartition { iter =>
      val fastMap = new FastHashMap[VertexId, U]()
      iter.foreach { case (k, u) => fastMap(k) = u }

      val push = psVertices.createUpdate { ctx: PSFGUCtx =>
        val params = ctx.getMapParam[U]
        val partition = ctx.getPartition[VD]
        val slot = partition.getOrCreateSlot[U](slotName)

        params.foreach { case (vid, u) =>
          if (slot.containsKey(vid)) {
            slot(vid) = updateFunc(slot(vid), u)
          } else {
            slot(vid) = updateFunc(null.asInstanceOf[U], u)
          }
        }
      }

      push(fastMap)
      fastMap.clear()
    }

    this
  }

  def getSlot[U: ClassTag : TypeTag](slotName: String): RDD[(VertexId, U)] = {
    sparkContext.parallelize(0 until numPsPartition, numPsPartition).mapPartitions { iter =>
      val partId = iter.next()

      val pull = psVertices.createGet { ctx: PSFGUCtx =>
        val pid = ctx.getParam[Singular]
        assert(pid.partition == ctx.partitionId)

        val partition = ctx.getPartition[VD]

        partition.getSlot[U](slotName).asFastHashMap
      } { ctx: PSFMCtx =>
        val last = ctx.getLast[FastHashMap[VertexId, U]]
        val curr = ctx.getCurr[FastHashMap[VertexId, U]]

        last.merge(curr)
      }

      val slotValues = pull(Singular(partId))
      pull.clear()

      slotValues.iterator
    }
  }

  def removeSlot[U: ClassTag : TypeTag](slotName: String): this.type = {
    val remove = psVertices.createUpdate { ctx: PSFGUCtx =>
      val partition = ctx.getPartition[VD]
      partition.removeSlot(slotName)
    }

    remove()
    remove.clear()

    this
  }

  // mapVertices on vertices will create new Graph, so it's inefficient
  def mapVertices[VD2: ClassTag : TypeTag](map: VD => VD2): Graph[VD2, ED] = {
    val oldMatrixId = psVertices.id
    val meta = PSAgentContext.get.getMatrixMetaManager.getMatrixMeta(oldMatrixId)
    val matrixContext = meta.getMatrixContext
    val minVertexId = matrixContext.getIndexStart
    val maxVertexId = matrixContext.getIndexEnd

    val matrix = PSFUtils.createPSMatrix(Graph.nextPSMatrixName, minVertexId, maxVertexId)

    val createPSPartition = matrix.createUpdate { ctx: PSFGUCtx =>
      val oldMatrixId = ctx.getParam[Int]
      val oldKey: String = s"$oldMatrixId-${ctx.partitionId}"
      val thisKey: String = s"${ctx.matrixId}-${ctx.partitionId}"

      val oldPartition = PSPartition.get[VD](oldKey)
      val newPartition = oldPartition.withAttrType[VD2]

      oldPartition.local2global.foreach { vid =>
        newPartition.setAttr(vid, map(oldPartition.getAttr(vid)))
      }

      PSPartition.set[VD2](thisKey, newPartition)
    }

    createPSPartition(oldMatrixId)
    createPSPartition.clear()

    val newEdges = edges.mapPartitions { iter =>
      val edgePartition = iter.next()

      val newPartition = edgePartition.withoutVertexAttributes[VD2]()
      Iterator.single(newPartition)
    }

    new Graph[VD2, ED](matrix, newEdges)
  }

  // operations on edge, note those ops will create new Graph, so it's inefficient
  def mapEdges[ED2: ClassTag](map: Edge[ED] => ED2): Graph[VD, ED2] = {
    val newEdge = edges.mapPartitions { iter =>
      val edgePartition = iter.next()
      val edgeIterator = edgePartition.iterator

      val newAttrs = edgeIterator.map { edge => map(edge) }.toArray
      val newPartition = edgePartition.withData(newAttrs)
      Iterator.single(newPartition)
    }

    new Graph[VD, ED2](psVertices, newEdge)
  }

  def mapTriplets[ED2: ClassTag](map: EdgeTriplet[VD, ED] => ED2): Graph[VD, ED2] = {
    val newEdge = edges.mapPartitions { iter =>
      val edgePartition = iter.next()
      val tripletIterator = edgePartition.tripletIterator()

      val newAttrs = tripletIterator.map { triplet => map(triplet) }.toArray
      val newPartition = edgePartition.withData(newAttrs)
      Iterator.single(newPartition)
    }

    new Graph[VD, ED2](psVertices, newEdge)
  }

  // merge duplicated edges, will create a new graph
  def groupEdges(merge: (ED, ED) => ED): Graph[VD, ED] = {
    val newEdges = edges.mapPartitions { iter =>
      val edgePartition = iter.next()
      val newEdgePartition = edgePartition.groupEdges(merge)
      Iterator.single(newEdgePartition)
    }

    withEdges(newEdges)
  }

  def subgraph(vpred: (VertexId, VD) => Boolean, epred: EdgeTriplet[VD, ED] => Boolean): Graph[VD, ED] = {
    val newEdges = edges.mapPartitions { iter =>
      val edgePartition = iter.next()
      val builder = new EdgePartitionBuilder[VD, ED](edgePartition.size)

      val tripletIterator = edgePartition.tripletIterator()
      val newVerticesAttrs = new FastHashMap[VertexId, VD]()
      tripletIterator.foreach { triplet =>
        if (vpred(triplet.srcId, triplet.srcAttr) &&
          vpred(triplet.dstId, triplet.dstAttr) && epred(triplet)) {
          if (!newVerticesAttrs.containsKey(triplet.srcId)) {
            newVerticesAttrs(triplet.srcId) = triplet.srcAttr
          }

          if (!newVerticesAttrs.containsKey(triplet.dstId)) {
            newVerticesAttrs(triplet.dstId) = triplet.dstAttr
          }

          builder.add(triplet.srcId, triplet.dstId, triplet.attr)
        }
      }

      val newEdgePartition = builder.build

      Iterator.single(newEdgePartition.updateVertices(newVerticesAttrs.iterator))
    }

    newEdges.cache()

    val newMatrix = Graph.createPSVertices(newEdges)

    newEdges.foreachPartition { iter =>
      val edgePartition = iter.next()

      val vertexAttrs = new FastHashMap[VertexId, VD](edgePartition.local2global.length)
      edgePartition.global2local.foreach { case (vid, idx) =>
        vertexAttrs(vid) = edgePartition.vertexAttrs(idx)
      }

      val push = newMatrix.createUpdate { ctx: PSFGUCtx =>
        val params = ctx.getMapParam[VD]
        val psPartition = ctx.getPartition[VD]

        params.foreach { case (k, v) =>
          psPartition.setAttr(k, v)
        }
      }

      push(vertexAttrs)
      push.clear()
    }

    new Graph(newMatrix, newEdges)
  }

  def reverse: Graph[VD, ED] = {
    val newEdges = edges.mapPartitions { iter =>
      val edgePartition = iter.next()

      Iterator.single(edgePartition.reverse)
    }

    withEdges(newEdges)
  }

  private[graph] def initVertex[M: TypeTag](vprog: (VD, M) => VD, active: (VD, M) => Boolean,
                                            defaultMsg: M): this.type = {
    val updateVertexPSF = psVertices.createUpdate { ctx: PSFGUCtx =>
      val part = ctx.getPartition[VD]
      part.updateAttrs(vprog, active, defaultMsg)
    }

    updateVertexPSF()
    updateVertexPSF.clear()

    this
  }

  private[graph] def updateVertex[M: TypeTag](vprog: (VD, M) => VD, active: (VD, M) => Boolean): this.type = {
    initVertex(vprog, active, null.asInstanceOf[M])
  }

  def calDegree(slotName: String, direction: EdgeDirection = EdgeDirection.Both): Unit = {
    edges.foreachPartition { iter =>
      val edgePartition = iter.next()

      val localDegree = new FastHashMap[VertexId, Int]()
      direction match {
        case EdgeDirection.Out =>
          edgePartition.iterator.foreach { edge =>
            localDegree.putMerge(edge.srcId, 1, (v1, v2) => v1 + v2)
          }
        case EdgeDirection.In =>
          edgePartition.iterator.foreach { edge =>
            localDegree.putMerge(edge.dstId, 1, (v1, v2) => v1 + v2)
          }
        case EdgeDirection.Both =>
          edgePartition.iterator.foreach { edge =>
            localDegree.putMerge(edge.srcId, 1, (v1, v2) => v1 + v2)
            localDegree.putMerge(edge.dstId, 1, (v1, v2) => v1 + v2)
          }
        case _ =>
          throw new Exception("direction error")
      }

      val pushDegree = psVertices.createUpdate { ctx: PSFGUCtx =>
        val params = ctx.getMapParam[Int]
        val partition = ctx.getPartition[VD]
        val degree = partition.getOrCreateSlot[Int](slotName)

        def mergeF(v1: Int, v2: Int): Int = v1 + v2

        params.foreach { case (k, v) =>
          degree.putMerge(k, v, mergeF)
        }
      }

      pushDegree(localDegree)
      pushDegree.clear()
    }
  }

  def activeMessageCount(): Int = {
    val activeMessageCountPSF = psVertices.createGet { ctx: PSFGUCtx =>
      ctx.getPartition[VD].activeVerticesCount()
    } { ctx: PSFMCtx => ctx.getLast[Int] + ctx.getCurr[Int] }

    val count = activeMessageCountPSF()
    activeMessageCountPSF.clear()

    count
  }

  def adjacency[N <: Neighbor : ClassTag : TypeTag](direction: EdgeDirection): this.type = {
    edges.foreachPartition { iter =>
      val edgePartition = iter.next()

      // 1. create Adjacency in spark RDD partition
      var pos = 0
      val adjBuilder = new PartitionUnTypedNeighborBuilder[N](direction)
      typeOf[N] match {
        case nt if nt =:= typeOf[NeighN] =>
          while (pos < edgePartition.size) {
            adjBuilder.add(edgePartition.srcIdFromPos(pos), edgePartition.dstIdFromPos(pos))
            pos += 1
          }
        case nt if nt =:= typeOf[NeighNW] =>
          while (pos < edgePartition.size) {
            adjBuilder.add(edgePartition.srcIdFromPos(pos),
              edgePartition.dstIdFromPos(pos),
              edgePartition.attrs(pos).asInstanceOf[WgtTpe])
            pos += 1
          }
        case _ =>
          throw new Exception("Adjacency data error!")
      }

      // 2. create PSF to push Adjacency (from executor)
      val pushAdjacency = psVertices.createUpdate { ctx: PSFGUCtx =>
        val psPartition = ctx.getPartition[VD]
        val psAdjBuilder = psPartition.getOrCreateSlot[UnTypedNeighborBuilder]("adjacencyBuilder")
        ctx.getMapParam[N].foreach { case (vid, neigh) =>
          if (psAdjBuilder.containsKey(vid)) {
            psAdjBuilder(vid).add(neigh)
          } else {
            val builder = new UnTypedNeighborBuilder()
            builder.add(neigh)
            psAdjBuilder(vid) = builder
          }
        }
      }

      // 3. push adjacency (on executor)
      pushAdjacency(adjBuilder.build)

      // 4. remove PSF (on executor)
      pushAdjacency.clear()
    }

    // create adjacency on PS (from driver)
    val createAdjacency = psVertices.createUpdate { ctx: PSFGUCtx =>
      val psPartition = ctx.getPartition[VD]
      val psAdjBuilder = psPartition.getSlot[UnTypedNeighborBuilder]("adjacencyBuilder")
      if (psAdjBuilder != null) {
        val psAdjacency = psPartition.getOrCreateSlot[N]("adjacency")

        psAdjBuilder.foreach { case (vid, adj) =>
          psAdjacency(vid) = adj.clearAndBuild[N](vid)
        }

        psPartition.removeSlot("adjacencyBuilder")
      } else {
        psPartition.getOrCreateSlot[N]("adjacency")
      }
    }
    createAdjacency()
    createAdjacency.clear() // remove PSF (on driver)

    this
  }

  def typedAdjacency[N <: Neighbor : ClassTag : TypeTag](direction: EdgeDirection): this.type = {
    edges.foreachPartition { iter =>
      val edgePartition = iter.next()

      // 1. create Adjacency in spark RDD partition
      var pos = 0
      val adjBuilder = new PartitionTypedNeighborBuilder[N](direction)
      typeOf[N] match {
        case nt if nt =:= typeOf[NeighTN] =>
          while (pos < edgePartition.size) {
            val srcId = edgePartition.srcIdFromPos(pos)
            val dstId = edgePartition.dstIdFromPos(pos)
            val attr = edgePartition.attrs(pos).asInstanceOf[Long]
            val srcType = attr.srcType
            val dstType = attr.dstType
            adjBuilder.add(srcId, srcType, dstId, dstType)
            pos += 1
          }
        case nt if nt =:= typeOf[NeighTNW] =>
          while (pos < edgePartition.size) {
            val srcId = edgePartition.srcIdFromPos(pos)
            val dstId = edgePartition.dstIdFromPos(pos)
            val attr = edgePartition.attrs(pos).asInstanceOf[Long]
            val srcType = attr.srcType
            val dstType = attr.dstType
            val weight = attr.weight
            adjBuilder.add(srcId, srcType, dstId, dstType, weight)
            pos += 1
          }
        case _ =>
          throw new Exception("Adjacency data error!")
      }

      // 2. create PSF to push Adjacency (from executor)
      val pushAdjacency = psVertices.createUpdate { ctx: PSFGUCtx =>
        val psPartition = ctx.getPartition[VD]
        val psAdjBuilder = psPartition.getOrCreateSlot[TypedNeighborBuilder]("adjacencyBuilder")
        ctx.getMapParam[N].foreach { case (vid, neigh) =>
          if (psAdjBuilder.containsKey(vid)) {
            psAdjBuilder(vid).add(neigh)
          } else {
            val builder = new TypedNeighborBuilder()
            builder.add(neigh)
            psAdjBuilder(vid) = builder
          }
        }
      }

      // 3. push adjacency (on executor)
      pushAdjacency(adjBuilder.build)

      // 4. remove PSF (on executor)
      pushAdjacency.clear()
    }

    // create adjacency on PS (from driver)
    val createAdjacency = psVertices.createUpdate { ctx: PSFGUCtx =>
      val psPartition = ctx.getPartition[VD]
      val psAdjBuilder = psPartition.getSlot[TypedNeighborBuilder]("adjacencyBuilder")
      if (psAdjBuilder != null) {
        val psAdjacency = psPartition.getOrCreateSlot[N]("adjacency")

        psAdjBuilder.foreach { case (vid, adj) =>
          psAdjacency(vid) = adj.clearAndBuild[N](vid)
        }

        psPartition.removeSlot("adjacencyBuilder")
      } else {
        psPartition.getOrCreateSlot[N]("adjacency")
      }
    }
    createAdjacency()
    createAdjacency.clear() // remove PSF (on driver)

    this
  }

  def partitionBy(partitionStrategy: PartitionStrategy): Graph[VD, ED] = {
    partitionBy(partitionStrategy, edges.partitions.length)
  }

  def partitionBy(partitionStrategy: PartitionStrategy, numPartitions: Int): Graph[VD, ED] = {
    val newEdges = edges.mapPartitions { iter =>
      val edgePartition = iter.next()
      edgePartition.iterator.map { e =>
        val part: PartitionID = partitionStrategy.getPartition(e.srcId, e.dstId, numPartitions)
        (part, (e.srcId, e.dstId, e.attr))
      }
    }.partitionBy(new HashPartitioner(numPartitions))
      .mapPartitions { iter =>
        val builder = new EdgePartitionBuilder[VD, ED]()
        iter.foreach { case (_, (srcId, dstId, attr)) =>
          builder.add(srcId, dstId, attr)
        }
        Iterator.single(builder.build)
      }

    newEdges.cache()
    withEdges(newEdges)
  }

  private[graph] def aggregateMessagesWithActiveSet[M: ClassTag : TypeTag](sendMsg: EdgeContext[VD, ED, M] => Unit,
                                                                           mergeMsg: (M, M) => M,
                                                                           tripletFields: TripletFields,
                                                                           batchSize: Int = -1,
                                                                           activeness: EdgeActiveness): Unit = {
    edges.foreachPartition { iter =>
      val edgePartition: EdgePartition[VD, ED] = iter.next()
      edgePartition.setPSMatrix(psVertices)

      logInfo(s"updateVertexAttrs with batchSize $batchSize")
      edgePartition.updateVertexAttrs(batchSize)

      logInfo(s"updateActiveSet with batchSize $batchSize")
      edgePartition.updateActiveSet(batchSize)

      logInfo(s"aggregateMessagesScan with batchSize $batchSize")
      edgePartition.aggregateMessagesScan(sendMsg, mergeMsg, tripletFields, activeness, batchSize)
    }

  }

  def aggregateMessages[M: ClassTag : TypeTag](sendMsg: EdgeContext[VD, ED, M] => Unit,
                                               mergeMsg: (M, M) => M,
                                               tripletFields: TripletFields = TripletFields.All): Unit = {
    aggregateMessagesWithActiveSet(sendMsg, mergeMsg, tripletFields, activeness = EdgeActiveness.Both)
  }

}

object Graph extends Logging {
  private[Graph] def nextPSMatrixName: String = s"PSPartition_${UUID.randomUUID()}"

  def maxVertexId[VD: ClassTag : TypeTag, ED: ClassTag](edges: RDD[EdgePartition[VD, ED]]): VertexId = {
    edges.mapPartitions { iter =>
      val part = iter.next()
      Iterator.single(part.maxVertexId)
    }.max()
  }

  def minVertexId[VD: ClassTag : TypeTag, ED: ClassTag](edges: RDD[EdgePartition[VD, ED]]): VertexId = {
    edges.mapPartitions { iter =>
      val part = iter.next()
      Iterator.single(part.minVertexId)
    }.min()
  }

  def createPSVertices[VD: ClassTag : TypeTag, ED: ClassTag](minVertexId: VertexId,
                                                             maxVertexId: VertexId,
                                                             edges: RDD[EdgePartition[VD, ED]]): PSMatrix = {
    val psMatrix = PSFUtils.createPSMatrix(nextPSMatrixName, minVertexId, maxVertexId)

    edges.foreachPartition { iter =>
      val edgePartition = iter.next()

      val init = psMatrix.createUpdate { ctx: PSFGUCtx =>
        ctx.put(ctx.getArrayParam)
      }

      init(edgePartition.localVertices)
      init.clear()
    }

    val createPSPartition = psMatrix.createUpdate { ctx: PSFGUCtx =>
      ctx.getOrCreatePartition[VD]
    }
    createPSPartition()
    createPSPartition.clear()

    logInfo("Finished to create PSMatrix")

    psMatrix
  }

  def createPSVertices[VD: ClassTag : TypeTag, ED: ClassTag](edges: RDD[EdgePartition[VD, ED]]): PSMatrix = {
    val maxVerId = maxVertexId(edges)
    val minVerId = minVertexId(edges)
    val psMatrix = PSFUtils.createPSMatrix(nextPSMatrixName, minVerId, maxVerId)

    edges.foreachPartition { iter =>
      val edgePartition = iter.next()

      val init = psMatrix.createUpdate { ctx: PSFGUCtx =>
        ctx.put(ctx.getArrayParam)
      }

      init(edgePartition.localVertices)
      init.clear()
    }

    val createPSPartition = psMatrix.createUpdate { ctx: PSFGUCtx =>
      ctx.getOrCreatePartition[VD]
    }
    createPSPartition()
    createPSPartition.clear()

    logInfo("Finished to create PSMatrix")

    psMatrix
  }
}
