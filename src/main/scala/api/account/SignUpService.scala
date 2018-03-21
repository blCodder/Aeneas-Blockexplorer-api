package api.account

import akka.NotUsed
import akka.actor.ActorRef
import io.circe.parser.decode
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import api.FlowError
import api.account.SignUpMessagesType.{ReturnPowBlock, SignupMessages}
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings
import api.account.circe.Codecs._
import block.PowBlock
import scorex.core.api.http.ActorHelper

import scala.concurrent.duration._
import scala.concurrent.Future
import io.circe.syntax._
/**
  * @author luger. Created on 19.03.18.
  * @version ${VERSION}
  */
trait SignUpService extends ActorHelper with PowBlocksBroadcast with ScorexLogging with FlowError{
  protected def aeneasSettings:AeneasSettings
  protected def newAccActor:ActorRef
  protected def loginActor:ActorRef

  val passPhraseMixingService = new PassPhraseMixingService(aeneasSettings)

  protected [account] def flowByEventType(): Flow [Message, Message, Any] ={
    implicit val askTimeout: Timeout = Timeout(30.seconds)
    mapclientEventToAccountEvent()
      .merge(producer._2)
      .mapAsync(1){
        case pb:PowBlock =>
          Future.successful(pb).map { pb =>
            TextMessage.Strict((pb.toReturnPowBlock:SignupMessages).asJson.noSpaces)
          }
        case x@NewAccountEvents.ErrorEvent(_) =>
          Future.successful(x).map { ev =>
            TextMessage.Strict(mapAccountEventsToMessage(ev).asJson.noSpaces)
          }
        case event@(NewAccountEvents.GetSavedSeeds() | NewAccountEvents.Logout(_) | NewAccountEvents.SignIn (_, _)) =>
          askActor[NewAccountEvents.NewAccountEvent](loginActor, event)
            .map{x =>
              TextMessage.Strict (mapAccountEventsToMessage(x).asJson.noSpaces)
            }
        case event =>
          log.debug(s"event:$event")
          askActor[NewAccountEvents.NewAccountEvent ](newAccActor, event)
            .map{x =>
              TextMessage.Strict ( mapAccountEventsToMessage(x).asJson.noSpaces)
            }
      }.
      via(reportErrorsFlow)
  }

  protected [account] def mapAccountEventsToMessage (event : NewAccountEvents.NewAccountEvent):SignUpMessagesType.SignupMessages  = {
    import SignUpMessagesType._
    event match {
      case csu@NewAccountEvents.CallToSignUp(_) =>
        PassPhrase(csu.passPhrase)
      case pp@NewAccountEvents.GeneratedConfirmationPassPhrase(_) =>
        ConfirmationPassPhrase(pp.passPhrase)
      case NewAccountEvents.GeneratedSeed(Some(gs)) =>
        Seed(gs.seed)
      case NewAccountEvents.GeneratedSeed(None) =>
        ErrorResponse("data isn't correct key-pair")
      case NewAccountEvents.ReceivedPassword(_) =>
        PwdConfirmed()
      case NewAccountEvents.ErrorEvent(msg) =>
        ErrorResponse(msg)
      case NewAccountEvents.Logout(seed) =>
        Logout(seed)
      case NewAccountEvents.ImportAccont(phrase) =>
        ImportAccont(phrase)
      case NewAccountEvents.ReturnSavedSeeds(seeds) =>
        SavedSeeds(seeds)
      case _ =>
        ErrorResponse("unknown event")
    }
  }

  protected [account] def mapclientEventToAccountEvent (): Flow[Message, NewAccountEvents.NewAccountEvent, NotUsed] =
    Flow[Message].collect {
      case TextMessage.Strict(t) => t
    }.map { msg =>
      import SignUpMessagesType._
      log.debug(s">>>>>>>> MSG:$msg")
      decode[SignUpMessage](msg) match {
        case Left(_) =>
          log.error("epic fail")
          NewAccountEvents.ErrorEvent("unknown message type")
        case Right(signUpMsg) => signUpMsg.msg match {
          case Signup() =>
            val phrase = passPhraseMixingService.generateRandomPassPhrase()
            NewAccountEvents.CallToSignUp(phrase)
          case CancelSignUp() =>
            NewAccountEvents.SignUpCancelled()
          case PassPhraseSaved() =>
            NewAccountEvents.SavedPassPhrase()
          case cpp@ConfirmPassPhrase(_) =>
            NewAccountEvents.ConfirmPassPhrase(cpp.passphrase)
          case slp@SetLocalPass(p1, p2) if p1 == p2 =>
            NewAccountEvents.ReceivedPassword(slp.password)
          case SetLocalPass(_, _) =>
            NewAccountEvents
              .ErrorEvent("password equals not confirmation password")
          case login:Login =>
            NewAccountEvents.SignIn(login.seed, login.pwd)
          case ia@ImportAccont(_) =>
            NewAccountEvents.ImportAccont(ia.passPhrase)
          case GetSavedSeeds() =>
            NewAccountEvents.GetSavedSeeds()
          case _ =>
            NewAccountEvents.ErrorEvent("unknown message type")
        }
      }
    }

}
