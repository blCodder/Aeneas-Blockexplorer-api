package api.account

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import api.account.circe.json.codecs._
import io.circe.parser.decode
import io.circe.syntax._
import io.iohk.iodb.LSMStore
import scorex.core.api.http.ActorHelper
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

/**
  * @author luger. Created on 01.03.18.
  * @version ${VERSION}
  */
class SignUpApi(aeneasSettings: AeneasSettings, store:LSMStore)(
  implicit system: ActorSystem, executionContext: ExecutionContext) extends ScorexLogging with ActorHelper{
  val passPhraseMixingService = new PassPhraseMixingService(aeneasSettings)

  val accFlow = CreateAccountFlow(passPhraseMixingService, store)

  private val newAccActor = system.actorOf(Props(new NewAccActor(store)))

  def route: Route = path("signup") {
      handleWebSocketMessages(flowByEventType())
    }


  private def flowByEventType(): Flow [Message, Message, Any] ={
    implicit val askTimeout: Timeout = Timeout(30.seconds)
    mapclientEventToAccountEvent()
      .mapAsync(1){
        case x@NewAccountEvents.ErrorEvent(_) =>
          Future.successful(x).map(mapAccountEventsToMessage(_))
        case event =>
          log.debug(s"event:$event, actr:$newAccActor")
          val asked = askActor[NewAccountEvents.NewAccountEvent](newAccActor, event)
          log.debug(s"asked:$asked")
          askActor[NewAccountEvents.NewAccountEvent ](newAccActor, event)
            .map(x => mapAccountEventsToMessage (x) )
    }.via(reportErrorsFlow)
  }


  private def mapAccountEventsToMessage (event : NewAccountEvents.NewAccountEvent): TextMessage.Strict = event match {
      case csu@NewAccountEvents.CallToSignUp(_) =>
        TextMessage (csu.passPhrase.asJson.noSpaces)
      case pp@NewAccountEvents.GeneratedConfirmationPassPhrase(_) =>
        TextMessage (pp.passPhrase.asJson.noSpaces)
      case NewAccountEvents.GeneratedSeed(Some(gs)) =>
        TextMessage.Strict(Map ("key" -> gs.key, "seed" -> gs.seed).asJson.noSpaces)
      case NewAccountEvents.GeneratedSeed(None) =>
        TextMessage.Strict(
          Seq(
            "response"->"error".asJson,
            "error"->"data isn't correct key-pair".asJson)
            .asJson.noSpaces)
      case NewAccountEvents.ReceivedPassword(_) =>
        TextMessage.Strict(
          Map(
            "response"->"success".asJson)
            .asJson.noSpaces)
      case x@NewAccountEvents.ErrorEvent(_) =>
        TextMessage.Strict(
          Map(
            "response"->"error".asJson,
            "error"->x.msg.asJson)
            .asJson.noSpaces)
      case _ =>
        TextMessage.Strict(
          Map(
            "response"->"error".asJson,
            "error"->"unknown event".asJson)
            .asJson.noSpaces)
    }



  private def mapclientEventToAccountEvent (): Flow[Message, NewAccountEvents.NewAccountEvent, NotUsed] =
    Flow[Message].collect {
      case TextMessage.Strict(t) => t
    }.map { msg =>
      import SignUpMessagesType._
      log.debug(s">>>>>>>> MSG:$msg, msgs:${SignUpMessage(Signup()).asJson.noSpaces}")
      decode[SignUpMessage](msg) match {
        case Left(_) =>
          NewAccountEvents.ErrorEvent(
            Seq(
              "response" -> "error".asJson,
              "error" -> "unknown message type".asJson).asJson.noSpaces
          )
        case Right(signUpMsg) => signUpMsg.msg match {
          case Signup() =>
            val phrase = passPhraseMixingService.generateRandomPassPhrase()
            NewAccountEvents.CallToSignUp(phrase)
          case CancelSignUp() =>
            NewAccountEvents.SignUpCancelled()
          case PassPhraseSaved() =>
            log.debug("PassPhraseSaved")
            NewAccountEvents.SavedPassPhrase()
          case cpp@ConfirmPassPhrase(_) =>
            NewAccountEvents.ConfirmPassPhrase(cpp.passphrase)
          case slp@SetLocalPass(p1, p2) if p1 == p2 =>
            NewAccountEvents.ReceivedPassword(slp.password)
          case login@Login(_, _) =>
            NewAccountEvents.SignIn(login.seed, login.pwd)
          case SetLocalPass(_, _) =>
            NewAccountEvents.ErrorEvent(
              Seq(
                "response" -> "error".asJson,
                "error" -> "password equals not confirmation password".asJson)
                .asJson.noSpaces
            )
          case ia@ImportAccont(_) =>
            NewAccountEvents.ImportAccont(ia.passPhrase)
          case _ =>
            NewAccountEvents.ErrorEvent(
              Seq(
                "response" -> "error".asJson,
                "error" -> "unknown message type".asJson).asJson.noSpaces
            )
        }
      }
    }

  private def reportErrorsFlow[T]: Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) => f.onComplete {
        case Failure(cause) =>
          log.error(s"WS stream failed with $cause")
        case _ => // ignore regular completion
      })

}

