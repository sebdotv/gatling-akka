package com.chatwork.gatling.akka

import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef._
import com.chatwork.gatling.akka.Predef._

import scala.concurrent.duration._

class BasicSimulation extends Simulation {

  val config          = ConfigFactory.load()
  implicit val system = ActorSystem("BasicSimulation", config)

  // gatling-akka protocol configuration
  val akkaConfig = akkaActor.askTimeout(3 seconds)

  // recipient actorRef
  val ponger = system.actorOf(PingerPonger.ponger)

  // proxy actorRef
  val pongerProxy = system.actorOf(PingerPonger.pongerProxy)

  // scenario definition
  val s = scenario("ping-pong-ping-pong")
    .exec {
      // ask a request to ponger actor and check its response with expectMsg and then save it in session if the check passes
      akkaActor("ping-1").to(ponger) ? PingerPonger.Ping check expectMsg(PingerPonger.Pong).saveAs("pong")
    }
    .exec {
      val pf: PartialFunction[Any, Unit] = {
        case PingerPonger.Pong =>
      }

      akkaActor("ping-2")
        .to(ponger)
        .ask { session =>
          // ask based on session value
          session("pong").as[PingerPonger.Pong.type] match {
            case PingerPonger.Pong => PingerPonger.Ping
          }
        }
        .check(expectMsgPF(pf)) // check a response with partial function with expectMsgPF
    }
    .exec {
      // message content check, same as expectMsg
      akkaActor("ping-3").to(ponger) ? PingerPonger.Ping check message.is(PingerPonger.Pong)
    }
    .exec {
      // find recipient and save it in session
      akkaActor("ping-4").to(ponger) ? PingerPonger.Ping check recipient.find.exists.saveAs("recipient")
    }
    .exec {
      // use extended ask pattern to get a ref to sender (test) actor
      akkaActor("ping-5").to(pongerProxy).extendedAsk(PingerPonger.ExtendedPing) check expectMsg(PingerPonger.Pong)
        .saveAs("proxyPong")
    }

  // inject configurations
  setUp(
    s.inject(constantUsersPerSec(10) during (10 seconds))
  ).protocols(akkaConfig)
    .maxDuration(10 seconds)
    .assertions(global.failedRequests.count is 0)
}

// simple ping-pong actor definition
object PingerPonger {

  def ponger: Props = Props(new Ponger)

  class Ponger extends Actor {
    override def receive: Receive = {
      case Ping => sender() ! Pong
      case ExtendedPing(replyTo) =>
        assert(sender() != replyTo)
        replyTo ! Pong
    }
  }

  def pongerProxy: Props = Props(new PongerProxy)

  class PongerProxy extends Actor {
    override def receive: Receive = {
      case msg => context.actorOf(ponger) ! msg
    }
  }

  case object Ping

  case object Pong

  case class ExtendedPing(xreplyTo: ActorRef)
}
