package io.funcqrs.projections

import io.funcqrs.AnyEvent
import io.funcqrs.projections.Projection._

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait Projection[O] {

  type Handle    = PartialFunction[EventEnvelope[O], Future[Unit]]
  type OnFailure = PartialFunction[(EventEnvelope[O], Throwable), Future[Unit]]

  def name: String = this.getClass.getSimpleName

  def handle: Handle

  def onFailure: OnFailure = PartialFunction.empty

  final def onEvent(evt: EventEnvelope[O]): Future[Unit] = {
    if (handle.isDefinedAt(evt)) {
      import scala.concurrent.ExecutionContext.Implicits.global
      handle(evt)
        .recoverWith {
          case NonFatal(exp) if onFailure.isDefinedAt(evt, exp) => onFailure(evt, exp)
        }
    } else {
      Future.successful(())
    }
  }

  /**
    * Builds a [[AndThenProjection]] composed of this Projection and the passed Projection.
    *
    * Events will be send to both projections. One after the other starting by this followed by the passed Projection.
    *
    * NOTE: In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    */
  def andThen(projection: Projection[O]) = new AndThenProjection(this, projection)

  /**
    * Builds a [[OrElseProjection]] composed of this Projection and the passed Projection.
    *
    * If this Projection is defined for a given incoming Event, then this Projection will be applied,
    * otherwise we fallback to the passed Projection.
    */
  def orElse(fallbackProjection: Projection[O]) = new OrElseProjection(this, fallbackProjection)

  object just {

    /** Handles an incoming Event synchronously.
      * Other [[EventEnvelope]] fields are ignored.
      */
    object HandleEvent {
      def apply(handle: PartialFunction[AnyEvent, Unit]): PartialFunction[EventEnvelope[O], Future[Unit]] = {
        case envelop if handle.isDefinedAt(envelop.event) =>
          Future.successful(handle(envelop.event))
      }
    }

    /** Handles an incoming EventEnvelope synchronously.
      */
    object HandleEnvelope {
      def apply(handle: PartialFunction[EventEnvelope[O], Unit]): PartialFunction[EventEnvelope[O], Future[Unit]] = {
        case envelop if handle.isDefinedAt(envelop) =>
          Future.successful(handle(envelop))
      }
    }

  }

  object attempt {

    /** Handles an incoming Event synchronously using a [[Try]].
      * Other [[EventEnvelope]] fields are ignored.
      */
    object HandleEvent {
      def apply(handle: PartialFunction[AnyEvent, Try[Unit]]): PartialFunction[EventEnvelope[O], Future[Unit]] = {
        case envelop if handle.isDefinedAt(envelop.event) =>
          Future.fromTry(handle(envelop.event))
      }
    }

    /** Handles an incoming EventEnvelope synchronously using a [[Try]].
      */
    object HandleEnvelope {
      def apply(handle: PartialFunction[EventEnvelope[O], Try[Unit]]): PartialFunction[EventEnvelope[O], Future[Unit]] = {
        case envelop if handle.isDefinedAt(envelop) =>
          Future.fromTry(handle(envelop))
      }
    }

  }

  /** Handles an incoming Event asynchronously.
    * Other [[EventEnvelope]] fields are ignored
    */
  object HandleEvent {
    def apply(handle: PartialFunction[AnyEvent, Future[Unit]]): PartialFunction[EventEnvelope[O], Future[Unit]] = {
      case envelop if handle.isDefinedAt(envelop.event) =>
        handle(envelop.event)
    }
  }

  /** Handles an incoming EventEnvelope asynchronously.
    * This handler is defined only for API symmetry. There is no PartialFunction conversion in this case.
    */
  object HandleEnvelope {
    def apply(handle: PartialFunction[EventEnvelope[O], Future[Unit]]): PartialFunction[EventEnvelope[O], Future[Unit]] =
      handle
  }

}

object Projection {

  /** Projection with empty domain */
  def empty[O] = new Projection[O] {
    def handle: Handle = PartialFunction.empty
  }

  /**
    * A [[Projection]] composed of two other Projections to each Event will be sent.
    *
    * Note that the second Projection is only applied once the first is completed successfully.
    *
    * In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    *
    * If none of the underlying Projections is defined for a given DomainEvent,
    * then this Projection is considered to be not defined for this specific DomainEvent.
    * As such a [[AndThenProjection]] can be combined with a [[OrElseProjection]].
    *
    * For example:
    * {{{
    *   val projection1 : Projection = ...
    *   val projection2 : Projection = ...
    *   val projection3 : Projection = ...
    *
    *   val finalProjection = (projection1 andThen projection2) orElse projection3
    *
    *   finalProjection.onEvent(SomeEvent("abc"))
    *   // if SomeEvent("abc") is not defined for projection1 nor for projection2, projection3 will be applied
    * }}}
    *
    */
  private[funcqrs] class AndThenProjection[O](firstProj: Projection[O], secondProj: Projection[O])
      extends ComposedProjection(firstProj, secondProj)
      with Projection[O] {

    import scala.concurrent.ExecutionContext.Implicits.global

    val projections = Seq(firstProj, secondProj)

    override def name: String = s"${firstProj.name}-and-then-${secondProj.name}"

    def handle: Handle = {
      // note that we only broadcast if at least one of the underlying
      // projections is defined for the incoming event
      // as such we make it possible to compose using orElse
      case (envelope) if composedHandleEvent.isDefinedAt(envelope) =>
        // send event to all projections
        firstProj.onEvent(envelope).flatMap { _ =>
          secondProj.onEvent(envelope)
        }
    }
  }

  /**
    * A [[Projection]] composed of two other Projections.
    *
    * Its `receiveEvent` is defined in terms of the `receiveEvent` method form the first Projection
    * with fallback to the `receiveEvent` method of the second Projection.
    *
    * As such the second Projection is only applied if the first Projection is not defined
    * for the given incoming Events
    *
    */
  private[funcqrs] class OrElseProjection[O](firstProj: Projection[O], secondProj: Projection[O])
      extends ComposedProjection(firstProj, secondProj)
      with Projection[O] {
    override def name: String = s"${firstProj.name}-or-then-${secondProj.name}"

    def handle = composedHandleEvent
  }

  private[funcqrs] class ComposedProjection[O](firstProj: Projection[O], secondProj: Projection[O]) {
    // compose underlying receiveEvents PartialFunction in order
    // to decide if this Projection is defined for given incoming DomainEvent
    private[funcqrs] def composedHandleEvent = firstProj.handle orElse secondProj.handle
  }

}
