package io.funcqrs.config

import io.funcqrs.projections.{ Projection, PublisherFactory }
import org.reactivestreams.{ Publisher, Subscriber }

case class StreamConfig[O](
    // how and what to read?
    publisherFactory: PublisherFactory[O],
    // how to consume?
    projection: Projection[O],
    // how to identify it
    name: String,
    // how to manage offsets
    offsetPersistenceStrategy: OffsetPersistenceStrategy[O] = NoOffsetPersistenceStrategy
)
