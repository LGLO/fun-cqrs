package io.funcqrs.projections

import org.reactivestreams.Publisher

trait PublisherFactory[O] {
  def from(offset: Option[O]): Publisher[(EventEnvelope[O])]
}
