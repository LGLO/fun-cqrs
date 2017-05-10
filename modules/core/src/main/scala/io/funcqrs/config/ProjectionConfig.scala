package io.funcqrs.config

import io.funcqrs.projections.{ Projection, PublisherFactory }
import io.funcqrs.backend.Query

import scala.concurrent.Future

case class ProjectionConfig[O](
    projection: Projection[O],
    name: String,
    publisherFactory: PublisherFactory[O],
    offsetPersistenceStrategy: OffsetPersistenceStrategy[O] = NoOffsetPersistenceStrategy
) {

  def withoutOffsetPersistence(): ProjectionConfig[O] = {
    copy(offsetPersistenceStrategy = NoOffsetPersistenceStrategy)
  }

  def withBackendOffsetPersistence(): ProjectionConfig[O] = {
    copy(offsetPersistenceStrategy = BackendOffsetPersistenceStrategy(name))
  }

  def withCustomOffsetPersistence(strategy: CustomOffsetPersistenceStrategy[O]): ProjectionConfig[O] = {
    copy(offsetPersistenceStrategy = strategy)
  }

}

trait OffsetPersistenceStrategy[+O]

case object NoOffsetPersistenceStrategy extends OffsetPersistenceStrategy[Nothing]

case class BackendOffsetPersistenceStrategy[O](persistenceId: String) extends OffsetPersistenceStrategy[O]

trait CustomOffsetPersistenceStrategy[O] extends OffsetPersistenceStrategy[O] {

  def saveCurrentOffset(offset: O): Future[Unit]

  /** Returns the current offset as persisted in DB */
  def readOffset: Future[Option[O]]

}
