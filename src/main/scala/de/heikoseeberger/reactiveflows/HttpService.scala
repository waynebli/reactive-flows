/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.heikoseeberger.reactiveflows

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.http.Http
import akka.http.server.{ Directives, Route }
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.{ ImplicitFlowMaterializer, Source }
import de.heikoseeberger.akkasse.{ EventStreamMarshalling, ServerSentEvent }
import scala.concurrent.duration.DurationInt
import spray.json.{ PrettyPrinter, jsonWriter }

object HttpService {

  val Name = "http-service"

  private case object Shutdown

  def props(interface: String, port: Int): Props = Props(new HttpService(interface, port))

  implicit def flowEventToServerSentEvent(event: Flow.Event): ServerSentEvent =
    event match {
      case messageAdded: Flow.MessageAdded =>
        val data = PrettyPrinter(jsonWriter[Flow.MessageAdded].write(messageAdded))
        ServerSentEvent(data, "added")
    }
}

class HttpService(interface: String, port: Int)
    extends Actor
    with ActorLogging
    with Directives
    with ImplicitFlowMaterializer
    with EventStreamMarshalling {

  import HttpService._
  import context.dispatcher

  Http()(context.system).bind(interface, port).startHandlingWith(route)
  log.info(s"Listening on $interface:$port")
  log.info(s"To shutdown, send GET request to http://$interface:$port/shutdown")

  override def receive: Receive = {
    case Shutdown => context.system.shutdown()
  }

  protected def createFlowEventPublisher(): ActorRef = context.actorOf(FlowEventPublisher.props)

  private def route: Route = assets ~ shutdown ~ messages

  private def assets: Route =
    // format: OFF
    path("") {
      getFromResource("web/index.html")
    } ~
    getFromResourceDirectory("web")
  // format: ON

  private def shutdown: Route =
    path("shutdown") {
      get {
        complete {
          context.system.scheduler.scheduleOnce(500 millis, self, Shutdown)
          log.info("Shutting down now ...")
          "Shutting down now ..."
        }
      }
    }

  private def messages: Route =
    path("messages") {
      get {
        complete {
          Source(ActorPublisher[Flow.Event](createFlowEventPublisher()))
        }
      }
    }
}
