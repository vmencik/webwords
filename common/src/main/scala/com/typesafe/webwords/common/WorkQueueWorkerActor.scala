package com.typesafe.webwords.common

import akka.actor._
import akka.dispatch.Future
import akka.pattern.pipe

/**
 * This actor wraps the work queue on the worker process side.
 */
abstract class WorkQueueWorkerActor extends Actor {

  protected def handleRequest(request: WorkQueueRequest): Future[WorkQueueReply]

  override def receive = {
    case request: WorkQueueRequest =>
      handleRequest(request) pipeTo sender
  }

}
