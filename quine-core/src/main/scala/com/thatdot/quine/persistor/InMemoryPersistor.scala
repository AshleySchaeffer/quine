package com.thatdot.quine.persistor

import java.util.concurrent._

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import akka.NotUsed
import akka.stream.scaladsl.Source

import cats.data.NonEmptyList

import com.thatdot.quine.graph.{
  DomainIndexEvent,
  EventTime,
  MultipleValuesStandingQueryPartId,
  NodeChangeEvent,
  NodeEvent,
  StandingQuery,
  StandingQueryId
}
import com.thatdot.quine.model.DomainGraphNode.DomainGraphNodeId
import com.thatdot.quine.model.{DomainGraphNode, QuineId}

/** Persistence implementation which actually just keeps everything in memory
  *
  * This is useful primarily as a debugging or testing mechanism - it should
  * behave like other persistors with the exception that it will consume
  * increasing amounts of memory. It is also convenient as a code-explanation
  * of what the persistor API is supposed to be doing.
  *
  * @param journals map storing all node events
  * @param snapshots map storing all snapshots
  * @param standingQueries set storing all standing queries
  * @param multipleValuesStandingQueryStates map storing all standing query states
  * @param metaData map storing all meta data
  * @param persistenceConfig persistence options
  */
class InMemoryPersistor(
  journals: ConcurrentMap[QuineId, ConcurrentNavigableMap[EventTime, NodeChangeEvent]] = new ConcurrentHashMap(),
  domainIndexEvents: ConcurrentMap[QuineId, ConcurrentNavigableMap[EventTime, DomainIndexEvent]] =
    new ConcurrentHashMap(),
  snapshots: ConcurrentMap[QuineId, ConcurrentNavigableMap[EventTime, Array[Byte]]] = new ConcurrentHashMap(),
  standingQueries: ConcurrentMap[StandingQueryId, StandingQuery] = new ConcurrentHashMap(),
  multipleValuesStandingQueryStates: ConcurrentMap[
    QuineId,
    ConcurrentMap[(StandingQueryId, MultipleValuesStandingQueryPartId), Array[
      Byte
    ]]
  ] = new ConcurrentHashMap(),
  metaData: ConcurrentMap[String, Array[Byte]] = new ConcurrentHashMap(),
  domainGraphNodes: ConcurrentMap[DomainGraphNodeId, DomainGraphNode] = new ConcurrentHashMap(),
  val persistenceConfig: PersistenceConfig = PersistenceConfig()
) extends PersistenceAgent {

  private val allTables =
    Seq(journals, domainIndexEvents, snapshots, standingQueries, multipleValuesStandingQueryStates, domainGraphNodes)
  override def emptyOfQuineData(): Future[Boolean] =
    Future.successful(
      allTables.forall(_.isEmpty)
    )

  def persistNodeChangeEvents(id: QuineId, events: NonEmptyList[NodeEvent.WithTime[NodeChangeEvent]]): Future[Unit] = {
    for { NodeEvent.WithTime(event, atTime) <- events.toList } journals
      .computeIfAbsent(id, (_: QuineId) => new ConcurrentSkipListMap())
      .put(atTime, event)
    Future.unit
  }

  override def deleteNodeChangeEvents(qid: QuineId): Future[Unit] = {
    journals.remove(qid)
    Future.unit
  }

  def persistDomainIndexEvents(
    id: QuineId,
    events: NonEmptyList[NodeEvent.WithTime[DomainIndexEvent]]
  ): Future[Unit] = {
    for { NodeEvent.WithTime(event, atTime) <- events.toList } domainIndexEvents
      .computeIfAbsent(id, (_: QuineId) => new ConcurrentSkipListMap())
      .put(atTime, event)
    Future.unit
  }

  override def deleteDomainIndexEvents(qid: QuineId): Future[Unit] = {
    domainIndexEvents.remove(qid)
    Future.unit
  }

  def getNodeChangeEventsWithTime(
    id: QuineId,
    startingAt: EventTime,
    endingAt: EventTime
  ): Future[Iterable[NodeEvent.WithTime[NodeChangeEvent]]] = {
    val eventsMap = journals.get(id)
    Future.successful(
      if (eventsMap == null)
        Iterable.empty
      else
        eventsMap
          .subMap(startingAt, true, endingAt, true)
          .entrySet()
          .iterator
          .asScala
          .flatMap(a => Iterator.single(NodeEvent.WithTime(a.getValue, a.getKey)))
          .toSeq
    )
  }

  def getDomainIndexEventsWithTime(
    id: QuineId,
    startingAt: EventTime,
    endingAt: EventTime
  ): Future[Iterable[NodeEvent.WithTime[DomainIndexEvent]]] = {
    val eventsMap = domainIndexEvents.get(id)
    Future.successful(
      if (eventsMap == null)
        Iterable.empty
      else
        eventsMap
          .subMap(startingAt, true, endingAt, true)
          .entrySet()
          .iterator
          .asScala
          .flatMap(a => Iterator.single(NodeEvent.WithTime(a.getValue, a.getKey)))
          .toSeq
    )
  }

  def deleteDomainIndexEventsByDgnId(dgnId: DomainGraphNodeId): Future[Unit] = {

    domainIndexEvents.asScala.map { case (_: QuineId, m: ConcurrentNavigableMap[EventTime, DomainIndexEvent]) =>
      m.entrySet()
        .removeIf(entry =>
          entry.getValue match {
            case event: DomainIndexEvent if event.dgnId == dgnId => true
            case _ => false
          }
        )
    }

    Future.unit
  }

  def enumerateJournalNodeIds(): Source[QuineId, NotUsed] =
    Source.fromIterator(() => journals.keySet().iterator.asScala)

  def enumerateSnapshotNodeIds(): Source[QuineId, NotUsed] =
    Source.fromIterator(() => snapshots.keySet().iterator.asScala)

  def persistSnapshot(id: QuineId, atTime: EventTime, state: Array[Byte]): Future[Unit] = {
    snapshots
      .computeIfAbsent(id, (_: QuineId) => new ConcurrentSkipListMap())
      .put(atTime, state)
    Future.unit
  }

  override def deleteSnapshots(qid: QuineId): Future[Unit] = {
    snapshots.remove(qid)
    Future.unit
  }

  def getLatestSnapshot(id: QuineId, upToTime: EventTime): Future[Option[Array[Byte]]] = {
    val snapshotsMap = snapshots.get(id)
    Future.successful(
      if (snapshotsMap == null) None
      else
        Option
          .apply(snapshotsMap.floorEntry(upToTime))
          .map(e => e.getValue)
    )
  }

  def persistStandingQuery(standingQuery: StandingQuery): Future[Unit] = {
    standingQueries.put(standingQuery.id, standingQuery)
    Future.unit
  }

  def removeStandingQuery(standingQuery: StandingQuery): Future[Unit] = {
    standingQueries.remove(standingQuery.id)
    Future.unit
  }

  def getMultipleValuesStandingQueryStates(
    id: QuineId
  ): Future[Map[(StandingQueryId, MultipleValuesStandingQueryPartId), Array[Byte]]] =
    Future.successful(
      Option
        .apply(multipleValuesStandingQueryStates.get(id))
        .fold(Map.empty[(StandingQueryId, MultipleValuesStandingQueryPartId), Array[Byte]])(m => m.asScala.toMap)
    )

  def setMultipleValuesStandingQueryState(
    standingQuery: StandingQueryId,
    id: QuineId,
    standingQueryId: MultipleValuesStandingQueryPartId,
    state: Option[Array[Byte]]
  ): Future[Unit] = {
    state match {
      case Some(bytes) =>
        multipleValuesStandingQueryStates
          .computeIfAbsent(id, (_: QuineId) => new ConcurrentHashMap())
          .put((standingQuery, standingQueryId), bytes)

      case None =>
        Option
          .apply(multipleValuesStandingQueryStates.get(id))
          .map(states => states.remove((standingQuery, standingQueryId)))
    }
    Future.unit
  }

  override def deleteMultipleValuesStandingQueryStates(id: QuineId): Future[Unit] = {
    multipleValuesStandingQueryStates.remove(id)
    Future.unit
  }

  def getStandingQueries: Future[List[StandingQuery]] =
    Future.successful(standingQueries.values.asScala.toList)

  def getMetaData(key: String): Future[Option[Array[Byte]]] =
    Future.successful(Option(metaData.get(key)))

  def getAllMetaData(): Future[Map[String, Array[Byte]]] =
    Future.successful(metaData.asScala.toMap)

  def setMetaData(key: String, newValue: Option[Array[Byte]]): Future[Unit] = {
    newValue match {
      case None => metaData.remove(key)
      case Some(bytes) => metaData.put(key, bytes)
    }
    Future.unit
  }

  def persistDomainGraphNodes(domainGraphNodes: Map[DomainGraphNodeId, DomainGraphNode]): Future[Unit] = {
    this.domainGraphNodes.putAll(domainGraphNodes.asJava)
    Future.unit
  }

  def removeDomainGraphNodes(domainGraphNodes: Set[DomainGraphNodeId]): Future[Unit] = {

    for { domainGraphNodesId <- domainGraphNodes } this.domainGraphNodes.remove(domainGraphNodesId)

    Future.unit
  }

  def getDomainGraphNodes(): Future[Map[DomainGraphNodeId, DomainGraphNode]] =
    Future.successful(domainGraphNodes.asScala.toMap)

  def shutdown(): Future[Unit] = Future.unit
}

object InMemoryPersistor {

  /** Create a new empty in-memory persistor */
  def empty = new InMemoryPersistor()
}
