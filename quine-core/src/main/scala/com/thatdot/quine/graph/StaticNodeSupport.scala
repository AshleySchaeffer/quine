package com.thatdot.quine.graph

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

import com.thatdot.common.logging.Log.{LazySafeLogging, LogConfig, Safe, SafeLoggableInterpolator}
import com.thatdot.common.quineid.QuineId
import com.thatdot.quine.graph.NodeActor.{Journal, MultipleValuesStandingQueries}
import com.thatdot.quine.graph.StaticNodeSupport.{deserializeSnapshotBytes, getMultipleValuesStandingQueryStates}
import com.thatdot.quine.graph.cypher.{MultipleValuesStandingQuery, MultipleValuesStandingQueryLookupInfo}
import com.thatdot.quine.graph.messaging.SpaceTimeQuineId
import com.thatdot.quine.graph.metrics.implicits.TimeFuture
import com.thatdot.quine.model.QuineIdProvider
import com.thatdot.quine.persistor.codecs.{AbstractSnapshotCodec, MultipleValuesStandingQueryStateCodec}
import com.thatdot.quine.util.Log.implicits._

abstract class StaticNodeSupport[
  Node <: AbstractNodeActor,
  Snapshot <: AbstractNodeSnapshot,
  ConstructorRecord <: Product,
](implicit
  val nodeClass: ClassTag[Node],
  val snapshotCodec: AbstractSnapshotCodec[Snapshot],
) {

  /** nodeClass is the class of nodes in the graph
    *
    * INV: this class's constructor arguments must end with the same types in the same order as [[ConstructorRecord]],
    * See finalNodeArgs in [[GraphShardActor]]'s handling of [[NodeStateRehydrated]]
    */
  def createNodeArgs(
    snapshot: Option[Snapshot],
    initialJournal: Journal = Iterable.empty,
    multipleValuesStandingQueryStates: MultipleValuesStandingQueries = mutable.Map.empty,
  ): ConstructorRecord

  def readConstructorRecord(
    quineIdAtTime: SpaceTimeQuineId,
    recoverySnapshotBytes: Option[Array[Byte]],
    graph: BaseGraph,
  )(implicit logConfig: LogConfig): Future[ConstructorRecord] =
    recoverySnapshotBytes match {
      case Some(recoverySnapshotBytes) =>
        val snapshot =
          deserializeSnapshotBytes(recoverySnapshotBytes, quineIdAtTime)(
            graph.idProvider,
            snapshotCodec,
          )
        val multipleValuesStandingQueryStatesFut: Future[MultipleValuesStandingQueries] =
          getMultipleValuesStandingQueryStates(quineIdAtTime, graph)
        multipleValuesStandingQueryStatesFut.map(multipleValuesStandingQueryStates =>
          // this snapshot was created as the node slept, so there are no journal events after the snapshot
          createNodeArgs(
            Some(snapshot),
            initialJournal = Iterable.empty,
            multipleValuesStandingQueryStates = multipleValuesStandingQueryStates,
          ),
        )(graph.nodeDispatcherEC)

      case None => restoreFromSnapshotAndJournal(quineIdAtTime, graph)
    }

  /** Load the state of specified the node at the specified time. The resultant NodeActorConstructorArgs should allow
    * the node to restore itself to its state prior to sleeping (up to removed Standing Queries) without any additional
    * persistor calls.
    *
    * @param untilOpt load changes made up to and including this time
    */
  def restoreFromSnapshotAndJournal(
    quineIdAtTime: SpaceTimeQuineId,
    graph: BaseGraph,
  )(implicit logConfig: LogConfig): Future[ConstructorRecord] = graph
    .namespacePersistor(quineIdAtTime.namespace)
    .fold {
      Future.successful(((None: Option[Snapshot], Nil: Journal), mutable.Map.empty: MultipleValuesStandingQueries))
    } { persistor =>
      val SpaceTimeQuineId(qid, _, atTime) = quineIdAtTime
      val persistenceConfig = persistor.persistenceConfig

      def getSnapshot(): Future[Option[Snapshot]] =
        if (!persistenceConfig.snapshotEnabled) Future.successful(None)
        else {
          val upToTime = atTime match {
            case Some(historicalTime) if !persistenceConfig.snapshotSingleton =>
              EventTime.fromMillis(historicalTime)
            case _ =>
              EventTime.MaxValue
          }
          graph.metrics.persistorGetLatestSnapshotTimer
            .time {
              persistor.getLatestSnapshot(qid, upToTime)
            }
            .map { maybeBytes =>
              maybeBytes.map(
                deserializeSnapshotBytes(_, quineIdAtTime)(graph.idProvider, snapshotCodec),
              )
            }(graph.nodeDispatcherEC)
        }

      def getJournalAfter(after: Option[EventTime], includeDomainIndexEvents: Boolean): Future[Iterable[NodeEvent]] = {
        val startingAt = after.fold(EventTime.MinValue)(_.tickEventSequence(None))
        val endingAt = atTime match {
          case Some(until) => EventTime.fromMillis(until).largestEventTimeInThisMillisecond
          case None => EventTime.MaxValue
        }
        graph.metrics.persistorGetJournalTimer.time {
          persistor.getJournal(qid, startingAt, endingAt, includeDomainIndexEvents)
        }
      }

      // Get the snapshot and journal events
      val snapshotAndJournal =
        getSnapshot()
          .flatMap { latestSnapshotOpt =>
            val journalAfterSnapshot: Future[Journal] = if (persistenceConfig.journalEnabled) {
              getJournalAfter(latestSnapshotOpt.map(_.time), includeDomainIndexEvents = atTime.isEmpty)
              // QU-429 to avoid extra retries, consider unifying the Failure types of `persistor.getJournal`, and adding a
              // recoverWith here to map any that represent irrecoverable failures to a [[NodeWakeupFailedException]]
            } else
              Future.successful(Vector.empty)

            journalAfterSnapshot.map(journalAfterSnapshot => (latestSnapshotOpt, journalAfterSnapshot))(
              ExecutionContext.parasitic,
            )
          }(graph.nodeDispatcherEC)

      // Get the materialized standing query states for MultipleValues.
      val multipleValuesStandingQueryStates: Future[MultipleValuesStandingQueries] =
        getMultipleValuesStandingQueryStates(quineIdAtTime, graph)

      // Will defer all other message processing until the Future is complete.
      // It is OK to ignore the returned future from `pauseMessageProcessingUntil` because nothing else happens during
      // initialization of this actor. Additional message processing is deferred by `pauseMessageProcessingUntil`'s
      // message stashing.
      snapshotAndJournal
        .zip(multipleValuesStandingQueryStates)
    }
    .map { case ((snapshotOpt, journal), multipleValuesStates) =>
      createNodeArgs(snapshotOpt, journal, multipleValuesStates)
    }(graph.nodeDispatcherEC)
}

object StaticNodeSupport extends LazySafeLogging {
  @throws[NodeWakeupFailedException]("When snapshot could not be deserialized")
  private def deserializeSnapshotBytes[Snapshot <: AbstractNodeSnapshot](
    snapshotBytes: Array[Byte],
    qidForDebugging: SpaceTimeQuineId,
  )(implicit
    idProvider: QuineIdProvider,
    snapshotCodec: AbstractSnapshotCodec[Snapshot],
  ): Snapshot =
    snapshotCodec.format
      .read(snapshotBytes)
      .fold(
        err =>
          throw new NodeWakeupFailedException(
            s"Snapshot could not be loaded for: ${qidForDebugging.pretty}",
            err,
          ),
        identity,
      )

  private def getMultipleValuesStandingQueryStates(
    qidAtTime: SpaceTimeQuineId,
    graph: BaseGraph,
  )(implicit logConfig: LogConfig): Future[MultipleValuesStandingQueries] = (graph -> qidAtTime) match {
    case (sqGraph: StandingQueryOpsGraph, SpaceTimeQuineId(qid, namespace, None)) =>
      sqGraph
        .namespacePersistor(namespace)
        .fold {
          Future.successful(mutable.Map.empty: MultipleValuesStandingQueries)
        } { persistor =>
          sqGraph
            .standingQueries(namespace)
            .fold(Future.successful(mutable.Map.empty: MultipleValuesStandingQueries)) { sqns =>
              val idProv: QuineIdProvider = sqGraph.idProvider
              val lookupInfo = new MultipleValuesStandingQueryLookupInfo {
                def lookupQuery(queryPartId: MultipleValuesStandingQueryPartId): MultipleValuesStandingQuery =
                  sqns.getStandingQueryPart(queryPartId)
                val executingNodeId: QuineId = qid
                val idProvider: QuineIdProvider = idProv
              }
              sqGraph.metrics.persistorGetMultipleValuesStandingQueryStatesTimer
                .time {
                  persistor.getMultipleValuesStandingQueryStates(qid)
                }
                .map { multipleValuesStandingQueryStates =>
                  // partition the retrieved MVSQ states into those that are still running and those that are not
                  val (keepThese, removeThese) = multipleValuesStandingQueryStates.partition {
                    case ((sqId, partId @ _), _) => sqns.runningStandingQuery(sqId).isDefined
                  }
                  // `removeThese` represents standing queries that have been cancelled since the previous time the
                  // node was awoken. Because these are no longer running, their persisted information is no longer
                  // relevant, and they will not be found if the we try to `rehydrate` them during construction.
                  // Therefore, we can safely remove them from the persistor as an optimization in disk space and
                  // future wake-up latencies.
                  // QU-1921 fire-and-forget removing `removeThese` from the persistor (i.e., define
                  //  `removeStandingQueryStatesForQidAndSqId` so it can be used like:)
                  //  removeThese.keySet.map(_._1).foreach(persistor.removeStandingQueryStatesForQidAndSqId(qid, _))
                  if (removeThese.nonEmpty) {
                    logger.debug(
                      safe"""During node constructor assembly, found ${Safe(removeThese.size)} no-longer-relevant
                            |MVSQ states for node: ${Safe(qidAtTime.pretty(idProv))}""".cleanLines,
                    )
                  }

                  // with the still-relevant SQ states, continue to assemble the node's constructor arguments
                  keepThese.map { case (sqIdAndPartId, bytes) =>
                    val sqState = MultipleValuesStandingQueryStateCodec.format
                      .read(bytes)
                      .fold(
                        err =>
                          throw new NodeWakeupFailedException(
                            s"NodeActor state (Standing Query States) for node: ${qidAtTime.pretty(idProv)} could not be loaded",
                            err,
                          ),
                        identity,
                      )
                    sqState._2.rehydrate(lookupInfo)
                    sqIdAndPartId -> sqState
                  }
                }(sqGraph.nodeDispatcherEC)
                .map(map => mutable.Map.from(map))(sqGraph.nodeDispatcherEC)
            }
        }
    case (_: StandingQueryOpsGraph, SpaceTimeQuineId(_, _, Some(_))) =>
      // this is the right kind of graph, but by definition, historical nodes (ie, atTime != None)
      // have no multipleValues states
      Future.successful(mutable.Map.empty)
    case (nonStandingQueryGraph @ _, _) =>
      // wrong kind of graph: only [[StandingQueryOpsGraph]]s can manage MultipleValues Standing Queries
      Future.successful(mutable.Map.empty)

  }
}
