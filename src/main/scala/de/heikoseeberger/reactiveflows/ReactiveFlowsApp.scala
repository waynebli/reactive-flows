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

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props, SupervisorStrategy, Terminated }
import akka.cluster.Cluster
import akka.event.Logging

object ReactiveFlowsApp extends {

  private val opt = """-D(\S+)=(\S+)""".r

  def main(args: Array[String]) {
    applySystemProperties(args.toList)

    val system = ActorSystem("reactive-flows")
    val log = Logging.apply(system, getClass)

    log.debug("Waiting to become a cluster member ...")
    Cluster(system).registerOnMemberUp {
      Flows(system).start()
      system.actorOf(Reaper.props, Reaper.Name)
      log.info("App up and running")
    }

    system.awaitTermination()
  }

  def applySystemProperties(args: Seq[String]): Unit =
    for ((key, value) <- collectOpts(args))
      System.setProperty(key, value)

  def collectOpts(args: Seq[String]): Seq[(String, String)] = args.collect { case opt(key, value) => key -> value }
}

object Reaper {

  val Name = "reaper"

  def props: Props = Props(new Reaper)
}

class Reaper
    extends Actor
    with ActorLogging
    with SettingsActor {

  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  context.watch(createHttpService())

  override def receive: Receive = {
    case Terminated(actorRef) =>
      log.warning("Shutting down, because {} has been terminated!", actorRef.path)
      context.system.shutdown()
  }

  protected def createHttpService(): ActorRef = {
    import settings.httpService._
    context.actorOf(HttpService.props(interface, port, askTimeout), HttpService.Name)
  }
}
