package com.typesafe.webwords.common

import akka.actor._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import akka.util.Timeout

/**
 * This actor wraps the work queue on the "client" side (in the web process).
 */
class WorkQueueClientActor extends Actor {

  // FIXME remoting
  private[this] val worker = context.actorFor("PATH to remote actor")
  
  private[this] implicit val timeout = Timeout(60 seconds)

  override def receive = {
    case request: WorkQueueRequest =>
      worker ? request pipeTo sender
  }
  
}
