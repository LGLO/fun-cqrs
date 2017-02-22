package io.funcqrs.config

import io.funcqrs.CommandException
import io.funcqrs.backend.Query
import io.funcqrs.behavior._
import io.funcqrs.projections.{ Projection, PublisherFactory }

import scala.language.higherKinds

object Api {

  /** Initiates the configuration of an Aggregate */
  def aggregate[A, C, E, I](behaviorFunc: I => Behavior[A, C, E]): AggregateConfig[A, C, E, I] = {

    val fallback: Behavior[A, C, E] = {
      case anyOtherState =>
        Actions[A, C, E]()
          .rejectCommand {
            case anyCommand => new CommandException(s"No actions defined for current state: $anyOtherState")
          }
    }
    // enrich user defined behavior with a fallback
    // this will prevent MatchErrors if user fails to define an exhaustive match
    val behaviorWithFallback = (id: I) => {
      behaviorFunc(id) orElse fallback
    }

    AggregateConfig[A, C, E, I](None, behaviorWithFallback)
  }

  /** Initiates the configuration of a Projection */
  def projection[O](query: Query, projection: Projection[O], name: String, publisherFactory: PublisherFactory[O]): ProjectionConfig[O] = {
    ProjectionConfig(query, projection, name, publisherFactory)
  }

  def projection[O](query: Query, projection: Projection[O], publisherFactory: PublisherFactory[O]): ProjectionConfig[O] = {
    ProjectionConfig(query, projection, projection.name, publisherFactory)
  }
}
