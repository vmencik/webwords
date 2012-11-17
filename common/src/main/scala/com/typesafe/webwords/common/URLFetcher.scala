package com.typesafe.webwords.common

import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import com.ning.http.client._
import akka.actor._
import akka.dispatch._
import akka.event.Logging
import akka.pattern.pipe
import akka.event.LoggingAdapter
import akka.util.Timeout
import akka.util.duration._

sealed trait URLFetcherIncoming
case class FetchURL(u: URL) extends URLFetcherIncoming

sealed trait URLFetcherOutgoing
case class URLFetched(status: Int, headers: Map[String, String], body: String) extends URLFetcherOutgoing

/**
 * This is an actor which encapsulates the AsyncHttpClient library.
 */
class URLFetcher extends Actor {

  private val asyncHttpClient = URLFetcher.makeClient
  private val log = Logging(context.system, this)

  override def receive = {
    case incoming: URLFetcherIncoming => {
      import context.system
      val f = incoming match {
        case FetchURL(u) =>
          URLFetcher.fetchURL(asyncHttpClient, u, log)
      }

      // FIXME timeout used by the receiver
      f pipeTo sender
    }
  }

  override def postStop = {
    asyncHttpClient.close()
  }
}

object URLFetcher {
  // This field is just used for debug/logging/testing
  val httpInFlight = new AtomicInteger(0)

  // note: an AsyncHttpClient is a heavy object with a thread
  // and connection pool associated with it, it's supposed to
  // be shared among lots of requests, not per-http-request
  private def makeClient() = {
    val executor = Executors.newCachedThreadPool()

    val builder = new AsyncHttpClientConfig.Builder()
    val config = builder.setMaximumConnectionsTotal(1000)
      .setMaximumConnectionsPerHost(15)
      .setExecutorService(executor)
      .setFollowRedirects(true)
      .build
    new AsyncHttpClient(config)
  }

  private def fetchURL(asyncHttpClient: AsyncHttpClient, u: URL, log: LoggingAdapter)(implicit executor: ExecutionContext): Future[URLFetched] = {
    val promise: Promise[URLFetched] = Promise() 
      
    val httpHandler = new AsyncHandler[Unit]() {
      httpInFlight.incrementAndGet()

      val builder =
        new Response.ResponseBuilder()

      var finished = false

      // We can have onThrowable called because onCompleted
      // throws, and other complex situations, so to handle everything
      // we use this
      private def finish(body: => Unit): Unit = {
        if (!finished) {
          try {
            body
          } catch {
            case t: Throwable => {
              log.debug(t.getMessage)
              promise.complete(Left(t))
              throw t // rethrow for benefit of AsyncHttpClient
            }
          } finally {
            finished = true
            httpInFlight.decrementAndGet()
            assert(promise.isCompleted)
          }
        }
      }

      // this can be called if any of our other methods throws,
      // including onCompleted.
      def onThrowable(t: Throwable) {
        finish { throw t }
      }

      def onBodyPartReceived(bodyPart: HttpResponseBodyPart) = {
        builder.accumulate(bodyPart)

        AsyncHandler.STATE.CONTINUE
      }

      def onStatusReceived(responseStatus: HttpResponseStatus) = {
        builder.accumulate(responseStatus)

        AsyncHandler.STATE.CONTINUE
      }

      def onHeadersReceived(responseHeaders: HttpResponseHeaders) = {
        builder.accumulate(responseHeaders)

        AsyncHandler.STATE.CONTINUE
      }

      def onCompleted() = {
        import scala.collection.JavaConverters._

        finish {
          val response = builder.build()

          val headersJavaMap = response.getHeaders()

          var headers = Map.empty[String, String]
          for (header <- headersJavaMap.keySet.asScala) {
            // sometimes getJoinedValue() would be more correct.
            headers += (header -> headersJavaMap.getFirstValue(header))
          }

          val body = response.getResponseBody()
          promise.complete(Right(URLFetched(response.getStatusCode(), headers, body)))
        }
      }
    }

    asyncHttpClient.prepareGet(u.toExternalForm()).execute(httpHandler)
    promise
  }
}
