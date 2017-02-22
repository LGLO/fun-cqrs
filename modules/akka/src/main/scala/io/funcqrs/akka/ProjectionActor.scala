package io.funcqrs.akka

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.pattern._
import akka.persistence.query.EventEnvelope2
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorSubscriber
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.Timeout
import io.funcqrs.EventWithCommandId
import io.funcqrs.akka.ProjectionActor.Start
import io.funcqrs.akka.util.ConfigReader.projectionConfig
import io.funcqrs.config.CustomOffsetPersistenceStrategy
import io.funcqrs.projections.{ Projection, PublisherFactory }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

abstract class ProjectionActor[O](
    projection: Projection[O],
    publisherFactory: PublisherFactory[O]
) extends Actor
    with ActorLogging {

  implicit val timeout = Timeout(5 seconds)

  var lastProcessedOffset: Option[O] = None

  private val projectionTimeout =
    projectionConfig(projection.name).getDuration("async-projection-timeout", 5.seconds)

  def saveCurrentOffset(offset: O): Future[Unit]

  def recoveryCompleted(): Unit = {
    log.debug("ProjectionActor: starting projection... {}", projection)

    implicit val mat = ActorMaterializer()

    val subscriber = ActorSubscriber[EventEnvelope2](self)
    val actorSink  = Sink.fromSubscriber(subscriber)

  }

  private def eventuallySendToParent(event: Any) = {
    event match {
      // send last processed event to parent
      case e: EventWithCommandId =>
        log.debug("Processed {}, sending to parent {}", event, context.parent)
        context.parent ! event
      case _ => // do nothing otherwise
    }
  }

  override def receive: Receive = streaming

  def streaming: Receive = {
    case Start => startStreaming()
  }

  private def withTimeout[A](fut: Future[A], contextLog: String): Future[A] = {
    // defines a timeout in case projection Future never terminates
    val eventualTimeout =
      after(duration = projectionTimeout, using = context.system.scheduler) {
        Future.failed(new TimeoutException(s"Timed out projection ${projection.name}. $contextLog"))
      }
    // one or another, first wins
    Future firstCompletedOf Seq(fut, eventualTimeout)
  }

  def startStreaming() = {
    Source
      .fromPublisher(publisherFactory.from(lastProcessedOffset))
      .mapAsync(1) { // process the event
        case (evt, offset) =>
          withTimeout(
            projection.onEvent(evt, offset),
            s"Processing offset $offset - event: $evt"
          ).map(_ => (evt, offset))
      }
      .mapAsync(1) { // save the offset
        case (lastEvent, offset) =>
          log.debug("Processed {}, sending to parent {}", lastEvent, context.parent)
          context.parent ! lastEvent // send last processed event to parent

          withTimeout(
            saveCurrentOffset(offset),
            s"Saving offset $offset - event: $lastEvent"
          )
      }
      .runWith(Sink.ignore)
      .onFailure {
        case e => // receive an error from the stream
          log.error(e, "Error while processing stream for projection [{}]", projection.name)
          context.stop(self)
      }
  }

}

object ProjectionActor {

  case object Start
  case class Done(evt: Any)
  case class OffsetPersisted(offset: Long)

}

/**
  * A ProjectionActor that never saves the offset
  * causing the event stream to be read from start on each app restart
  */
class ProjectionActorWithoutOffsetPersistence[O](
    projection: Projection[O],
    publisherFactory: PublisherFactory[O]
) extends ProjectionActor(projection, publisherFactory)
    with OffsetNotPersisted[O]

object ProjectionActorWithoutOffsetPersistence {

  def props[O](
      projection: Projection[O],
      publisherFactory: PublisherFactory[O]
  ): Props = {

    Props(new ProjectionActorWithoutOffsetPersistence(projection, publisherFactory))
  }

}

/**
  * A ProjectionActor that saves the offset as a snapshot in Akka Persistence
  *
  * This implementation is a quick win for those that simply want to persist the offset without caring about
  * the persistence layer.
  *
  * However, the drawback is that most (if not all) akka-persistence snapshot plugins will
  * save it as binary data which make it difficult to inspect the DB to get to know the last processed event.
  */
class ProjectionActorWithOffsetManagedByAkkaPersistence[O](
    projection: Projection[O],
    publisherFactory: PublisherFactory[O],
    val persistenceId: String
) extends ProjectionActor(projection, publisherFactory)
    with PersistedOffsetAkka[O]

object ProjectionActorWithOffsetManagedByAkkaPersistence {

  def props[O](
      projection: Projection[O],
      publisherFactory: PublisherFactory[O],
      persistenceId: String
  ): Props = {

    Props(new ProjectionActorWithOffsetManagedByAkkaPersistence(projection, publisherFactory, persistenceId))
  }
}

/** A ProjectionActor that saves the offset using a [[CustomOffsetPersistenceStrategy]] */
class ProjectionActorWithCustomOffsetPersistence[O](
    projection: Projection[O],
    publisherFactory: PublisherFactory[O],
    customOffsetPersistence: CustomOffsetPersistenceStrategy[O]
) extends ProjectionActor(projection, publisherFactory)
    with PersistedOffsetCustom[O] {

  def saveCurrentOffset(offset: O): Future[Unit] = {
    customOffsetPersistence.saveCurrentOffset(offset)
  }

  /** Returns the current offset as persisted in DB */
  def readOffset: Future[Option[O]] = customOffsetPersistence.readOffset
}

object ProjectionActorWithCustomOffsetPersistence {

  def props[O](
      projection: Projection[O],
      publisherFactory: PublisherFactory[O],
      customOffsetPersistence: CustomOffsetPersistenceStrategy[O]
  ): Props = {

    Props(new ProjectionActorWithCustomOffsetPersistence(projection, publisherFactory, customOffsetPersistence))
  }

}
