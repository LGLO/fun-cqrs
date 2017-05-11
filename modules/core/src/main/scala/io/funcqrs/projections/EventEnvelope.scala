package io.funcqrs.projections

import scala.concurrent.Future

case class EventEnvelope[O](offset: O, sequenceNr: Long, event: Any)

class EventOnlyOpt(val env: EventEnvelope[_]) extends AnyVal {
  def isEmpty: Boolean = false
  def get: Any         = env.event
}

object EventOnly {
  def unapply(arg: EventEnvelope[_]): EventOnlyOpt =
    new EventOnlyOpt(arg)
}

class EventWithOffsetOpt(val env: EventEnvelope[_]) extends AnyVal {
  def isEmpty: Boolean = false
  def get: (Any, Any)  = (env.event, env.offset)
}

object EventWithOffset {
  def unapply(arg: EventEnvelope[_]): EventWithOffsetOpt =
    new EventWithOffsetOpt(arg)
}
