package com.thatdot.quine.graph

import com.thatdot.quine.model.DomainGraphNode.DomainGraphNodeId
import com.thatdot.quine.model.{HalfEdge, PropertyValue, QuineId}

sealed trait NodeEvent

object NodeEvent {

  /** Event along with the time it occurs at
    *
    * @param event what happened to the node?
    * @param atTime when did it happen?
    */
  final case class WithTime[+E <: NodeEvent](
    event: E,
    atTime: EventTime
  )
}

/** Event which affects the local node state (properties or edges)
  *
  * Storing node state as a series of time-indexed events (aka. event sourcing),
  * it becomes straightforward to:
  *
  *   - re-create node state for any timestamp by applying or unapplying events
  *     until the desired timestamp is reached (eg. for historical queries)
  *
  *   - design event-driven systems for triggering on changes to node state (eg.
  *     standing queries)
  *
  *   - persist the changes to durable storage without necessarily needing
  *     expensive updates (append often suffices)
  */
sealed abstract class NodeChangeEvent extends NodeEvent
sealed abstract class PropertyEvent extends NodeChangeEvent {
  val key: Symbol
}
object PropertyEvent {
  final case class PropertySet(key: Symbol, value: PropertyValue) extends PropertyEvent

  final case class PropertyRemoved(key: Symbol, previousValue: PropertyValue) extends PropertyEvent

}
sealed abstract class EdgeEvent extends NodeChangeEvent {
  val edge: HalfEdge
}
object EdgeEvent {
  final case class EdgeAdded(edge: HalfEdge) extends EdgeEvent
  final case class EdgeRemoved(edge: HalfEdge) extends EdgeEvent

}

sealed trait DomainIndexEvent extends NodeEvent {
  val dgnId: DomainGraphNodeId
}

object DomainIndexEvent {
  final case class CreateDomainNodeSubscription(
    dgnId: DomainGraphNodeId,
    replyTo: QuineId,
    relatedQueries: Set[StandingQueryId]
  ) extends DomainIndexEvent

  final case class CreateDomainStandingQuerySubscription(
    dgnId: DomainGraphNodeId,
    replyTo: StandingQueryId,
    relatedQueries: Set[StandingQueryId]
  ) extends DomainIndexEvent

  final case class DomainNodeSubscriptionResult(
    from: QuineId,
    dgnId: DomainGraphNodeId,
    result: Boolean
  ) extends DomainIndexEvent

  final case class CancelDomainNodeSubscription(
    dgnId: DomainGraphNodeId,
    alreadyCancelledSubscriber: QuineId
  ) extends DomainIndexEvent
}
