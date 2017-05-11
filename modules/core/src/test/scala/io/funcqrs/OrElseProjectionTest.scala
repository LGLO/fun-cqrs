package io.funcqrs

import io.funcqrs.projections.EventEnvelope
import org.scalatest.concurrent.{ Futures, ScalaFutures }
import org.scalatest.{ FlatSpec, Matchers, OptionValues }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure

class OrElseProjectionTest extends FlatSpec with Matchers with Futures with ScalaFutures with OptionValues {

  implicit val patienceConf = patienceConfig

  behavior of "OrElseProjection"

  case class FooEvent(value: String)

  case class BarEvent(num: Int)

  it should "Events are not propagated to second Projection if first can handle event" in {

    val fooProjection1 = newFooProjection()
    val fooProjection2 = newFooProjection()

    val orElseProjection = fooProjection1 orElse fooProjection2

    whenReady(orElseProjection.onEvent(EventEnvelope(0, 0, FooEvent("abc")))) { _ =>
      fooProjection1.result.value shouldBe "abc"
      fooProjection2.result shouldBe None
    }
  }

  it should "propagate events to second Projection if first Projection is not defined for passed Event" in {

    val fooProjection = newFooProjection()
    val barProjection = newBarProjection()

    val orElseProjection = fooProjection orElse barProjection

    whenReady(orElseProjection.onEvent(EventEnvelope(0, 0, BarEvent(10)))) { _ =>
      fooProjection.result shouldBe None
      barProjection.result.value shouldBe 10
    }

  }

  it should "stop propagating Event if first Projection fails" in {

    val barProjection = newBarProjection()

    val orElseProjection = newFailingBarProjection() orElse barProjection

    // we must recover it in other to use with ScalaTest
    val recovered =
      orElseProjection
        .onEvent(EventEnvelope(0, 0, BarEvent(10)))
        .recover { case _ => () }

    whenReady(recovered) { _ =>
      barProjection.result shouldBe None
    }
  }

  def newFailingBarProjection() = new projections.Projection[Int] {
    def handle = attempt.HandleEvent {
      case _ => Failure(new IllegalArgumentException("this projection should not receive events"))
    }
  }

  def newFailingFooProjection() = new projections.Projection[Int] {
    def handle = attempt.HandleEvent {
      case _ => Failure(new IllegalArgumentException("this projection should not receive events"))
    }
  }

  class FooProjection extends projections.Projection[Int] {
    var result: Option[String] = None

    def handle = just.HandleEvent {
      case evt: FooEvent => result = Some(evt.value)
    }
  }

  def newFooProjection() = new FooProjection

  class BarProjection extends projections.Projection[Int] {
    var result: Option[Int] = None

    def handle = just.HandleEvent {
      case evt: BarEvent => result = Some(evt.num)
    }
  }

  def newBarProjection() = new BarProjection
}
