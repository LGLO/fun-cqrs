package raffle.app

import akka.actor.ActorSystem
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.{PersistenceQuery, Sequence}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import io.funcqrs.akka.backend.AkkaBackend
import io.funcqrs.config.Api.{projection, _}
import io.funcqrs.projections.{EventEnvelope, PublisherFactory}
import io.funcqrs.test.backend.InMemoryBackend
import org.reactivestreams.Publisher
import raffle.domain.model.Raffle
import raffle.domain.service.{RaffleViewProjection, RaffleViewRepo}

import scala.language.higherKinds

object AppContext {

  private var isAkkaConfigured = false
  val raffleViewRepo = new RaffleViewRepo

  lazy val akkaBackend = {
    isAkkaConfigured = true

    val actorSys: ActorSystem = ActorSystem("FunCQRS")
    implicit val materializer = ActorMaterializer()(actorSys)

    val backend = new AkkaBackend {
      val actorSystem: ActorSystem = actorSys
    }

    val readJournal =
      PersistenceQuery(actorSys).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)


    backend
      .configure {
        // aggregate config - write model
        aggregate(Raffle.behavior)
      }
      .configure {
        projection(
          projection = new RaffleViewProjection(raffleViewRepo),
          publisherFactory = new PublisherFactory[Long] {
            override def from(offset: Option[Long]): Publisher[EventEnvelope[Long]] = {

              val akkaOffset = offset.map(Sequence).getOrElse(Sequence(0))
              readJournal
                .eventsByTag(Raffle.tag.value, akkaOffset)
                .map { akkaEnvelope =>
                  val Sequence(value) = akkaEnvelope.offset
                  EventEnvelope(value, akkaEnvelope.sequenceNr, akkaEnvelope.event)
                }
                .runWith(Sink.asPublisher(false))
            }
          }
        )
      }
  }

  lazy val inMemoryBackend = {
    val backend = new InMemoryBackend
    backend
      .configure {
        // aggregate config - write model
        aggregate(Raffle.behavior)
      }
      .configure {
        projection(
          projection = new RaffleViewProjection(raffleViewRepo),
          publisherFactory = new PublisherFactory[Long] {
            override def from(offset: Option[Long]): Publisher[EventEnvelope[Long]] =
              backend.eventsPublisher()
          }
        )
      }
  }

  def close = {
    if (isAkkaConfigured) akkaBackend.actorSystem.terminate()
  }
}
