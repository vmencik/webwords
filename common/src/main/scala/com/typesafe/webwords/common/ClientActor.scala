package com.typesafe.webwords.common

import java.net.URL
import akka.actor._
import akka.dispatch._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import akka.util.Timeout

sealed trait ClientActorIncoming
case class GetIndex(url: String, skipCache: Boolean) extends ClientActorIncoming

sealed trait ClientActorOutgoing
case class GotIndex(url: String, index: Option[Index], cacheHit: Boolean) extends ClientActorOutgoing

/**
 * This actor encapsulates:
 *  - checking the cache for an index of a certain URL
 *  - asking the indexer worker process to index the URL if it's not cached
 *  - checking the cache again when the worker is done
 * It coordinates a WorkQueueClientActor and IndexStorageActor to accomplish
 * this.
 */
class ClientActor(config: WebWordsConfig) extends Actor {
  import ClientActor._

  private val client = context.actorOf(Props[WorkQueueClientActor])
  private val cache = context.actorOf(Props(new IndexStorageActor(config.mongoURL)))
  

  override def receive = {
    case incoming: ClientActorIncoming =>
      incoming match {
        case GetIndex(url, skipCache) =>
          import context.system
          // we look in the cache, if that fails, ask spider to
          // spider and then notify us, and then we look in the
          // cache again.
          def getWithoutCache = {
            getFromWorker(client, url) flatMap { _ =>
              getFromCacheOrElse(cache, url, cacheHit = false)(Promise.successful(GotIndex(url, index = None, cacheHit = false)))
            }
          }

          val futureGotIndex = if (skipCache)
            getWithoutCache
          else
            getFromCacheOrElse(cache, url, cacheHit = true) { getWithoutCache }

          futureGotIndex pipeTo sender
      }
  }

}

object ClientActor {
  private def getFromCacheOrElse(cache: ActorRef, url: String, cacheHit: Boolean)(fallback: => Future[GotIndex])(implicit executor: ExecutionContext): Future[GotIndex] = {
    implicit val timeout = Timeout(30 seconds)
    cache ? FetchCachedIndex(url) flatMap {
      case CachedIndexFetched(Some(index)) =>
        Promise.successful(GotIndex(url, Some(index), cacheHit))
      case CachedIndexFetched(None) =>
        fallback
    }
  }

  private def getFromWorker(client: ActorRef, url: String)(implicit executor: ExecutionContext): Future[Unit] = {
    implicit val timeout = Timeout(30 seconds)
    client ? SpiderAndCache(url) map {
      case SpideredAndCached(returnedUrl) =>
        Unit
    }
  }
}
