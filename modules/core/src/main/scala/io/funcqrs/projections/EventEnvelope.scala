package io.funcqrs.projections

case class EventEnvelope[O](offset: O, sequenceNr: Long, event: Any)
