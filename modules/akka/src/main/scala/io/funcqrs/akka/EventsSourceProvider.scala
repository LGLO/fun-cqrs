package io.funcqrs.akka

import akka.NotUsed
import akka.actor.ActorContext
import akka.persistence.query.EventEnvelope2
import akka.stream.scaladsl.{ Sink, Source }
import io.funcqrs.projections.PublisherFactory
import org.reactivestreams.Publisher

/**
  * Provides an Akka-Streams [[Source]] that produces [[EventEnvelope2]]s.
  * TODO: document it with implementation example
  */
trait EventsSourceProvider {
  def source(offset: Long)(implicit context: ActorContext): Source[EventEnvelope2, NotUsed]
}

//object EventsSourceProvider {
//  def toPublisher(srcProvider: EventsSourceProvider): PublisherFactory[Long, EventEnvelope2] = {
//    new PublisherFactory[Long, EventEnvelope2] {
//      override def from(offset: Option[Long], query: String): Publisher[EventEnvelope2] = {
//        srcProvider.source(offset.get).runWith(Sink.asPublisher(false))
//      }
//    }
//  }
//}
