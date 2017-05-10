package io.funcqrs.test.backend

import io.funcqrs._
import io.funcqrs.backend.{ Backend, QueryByTag, QueryByTags, QuerySelectAll }
import io.funcqrs.behavior._
import io.funcqrs.config.{ AggregateConfig, ProjectionConfig }
import io.funcqrs.interpreters.{ Identity, IdentityInterpreter }
import io.funcqrs.projections.EventEnvelope
import org.reactivestreams.Publisher
import rx.RxReactiveStreams
import rx.lang.scala.{ Observable, Subject }
import rx.lang.scala.subjects.PublishSubject
import rx.lang.scala.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.collection.{ concurrent, immutable }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag

class InMemoryBackend extends Backend[Identity] {

  private var aggregateConfigs: concurrent.Map[ClassTag[_], AggregateConfig[_, _, _, _]] = concurrent.TrieMap()
  private var aggregates: concurrent.Map[AggregateId, IdentityAggregateRef[_, _, _]]     = TrieMap()

  private var eventsOffset: Long = 0

  private val eventStream: Subject[EventEnvelope[Long]] = PublishSubject()

  def eventsPublisher(): Publisher[EventEnvelope[Long]] = {
    val obs = eventStream.asJavaObservable.asInstanceOf[rx.Observable[EventEnvelope[Long]]]
    RxReactiveStreams.toPublisher(obs)
  }

  private val stream: Stream[EventEnvelope[Long]] = Stream()

  protected def aggregateRefById[A: ClassTag, C, E, I <: AggregateId](id: I): InMemoryAggregateRef[A, C, E, I] = {

    type ConfigType = AggregateConfig[A, C, E, I]

    aggregates
      .getOrElseUpdate(
        id, { // build new aggregateRef if not existent

          val config = configLookup[A, C, E, I] {
            aggregateConfigs(ClassTagImplicits[A]).asInstanceOf[ConfigType]
          }

          val behavior = config.behavior(id)
          new InMemoryAggregateRef(id, behavior)
        }
      )
      .asInstanceOf[InMemoryAggregateRef[A, C, E, I]]
  }

  def configure[A: ClassTag, C, E, I](config: AggregateConfig[A, C, E, I]): Backend[Identity] = {
    aggregateConfigs += (ClassTagImplicits[A] -> config)
    this
  }

  def configure[O](config: ProjectionConfig[O]): Backend[Identity] = {

    val rxStream = toScalaObservable(RxReactiveStreams.toObservable(config.publisherFactory.from(None)))

    rxStream.subscribe { envelope =>
      // send even to projections
      val res = config.projection.onEvent(envelope)
      // TODO: projections should be interpreted as well to avoid this
      Await.ready(res, 10.seconds)
      ()
    }

    this
  }

  private def publishEvents(evts: immutable.Seq[AnyEvent]): Unit = {
    evts foreach { evt =>
      eventsOffset = eventsOffset + 1
      eventStream.onNext(EventEnvelope(eventsOffset, eventsOffset, evt))
    }
  }

  class InMemoryAggregateRef[A, C, E, I <: AggregateId](id: I, behavior: Behavior[A, C, E]) extends IdentityAggregateRef[A, C, E] { self =>

    private var aggregateState: Option[A] = None

    val interpreter = IdentityInterpreter(behavior)

    def ask(cmd: C): Identity[immutable.Seq[E]] =
      handle(aggregateState, cmd)

    def tell(cmd: C): Unit = {
      ask(cmd)
      () // omit events
    }

    private def handle(state: Option[A], cmd: C): immutable.Seq[E] = {
      val (events, updatedAgg) = interpreter.applyCommand(state, cmd)
      aggregateState = updatedAgg
      publishEvents(events)
      events
    }

    def state(): Identity[A] =
      aggregateState.getOrElse(sys.error("Aggregate is not initialized"))

    def exists(): Identity[Boolean] = aggregateState.isDefined

    def withAskTimeout(timeout: FiniteDuration): AggregateRef[A, C, E, Future] = new AsyncAggregateRef[A, C, E] {

      def timeoutDuration: FiniteDuration = timeout

      def withAskTimeout(timeout: FiniteDuration): AggregateRef[A, C, E, Future] = self.withAskTimeout(timeout)

      def tell(cmd: C): Unit = self.tell(cmd)

      def ask(cmd: C): Future[immutable.Seq[E]] = Future.successful(self.ask(cmd))

      def state(): Future[A] = Future.successful(self.state())

      def exists(): Future[Boolean] = Future.successful(self.exists())
    }
  }
}
