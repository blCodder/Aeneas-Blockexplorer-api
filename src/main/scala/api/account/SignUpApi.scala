package api.account

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import akka.util.Timeout
import api.Protocol
import api.account.CreateAccountFlow.UserKey
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import api.account.circe._
import api.account.circe.json.codecs._
import java.time._

import block.PowBlock
import io.circe.syntax._
import io.circe.parser._
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
//sealed trait
class SignUpApi(aeneasSettings: AeneasSettings, store:LSMStore)(implicit system: ActorSystem, executionContext: ExecutionContext) extends ScorexLogging with ActorHelper{
  val passPhraseMixingService = new PassPhraseMixingService(aeneasSettings)

  val accFlow = CreateAccountFlow(passPhraseMixingService, store)

  private val newAccActor = system.actorOf(Props(new NewAccActor(store)))

  implicit val materializer = ActorMaterializer()

  val bufferSize = 256

  //if the buffer fills up then this strategy drops the oldest elements
  //upon the arrival of a new element.
  val overflowStrategy = akka.stream.OverflowStrategy.dropHead

  val source: Source[String, SourceQueueWithComplete[String]] = Source.queue(
    bufferSize, overflowStrategy
  )

  val queue: RunnableGraph[(SourceQueueWithComplete[String], Source[String, NotUsed])] =
    source.toMat( BroadcastHub.sink(bufferSize) )(Keep.both)

  val producer2: (SourceQueueWithComplete[String], Source[String, NotUsed]) = queue.run()

  producer2._2.runWith(Sink.ignore)


  def route: Route = path("signup") {
      handleWebSocketMessages(flowByEventType())
    } ~ path("broadcast") {
      handleWebSocketMessages(broadcast())
    } ~ path("confirmpassphrase") {
      parameter('passphrase) {passPhraseConfirm =>
        val ppConfirm = passPhraseConfirm.split("\\,")
        handleWebSocketMessages(webSocketComparingPassPhraseFlow(ppConfirm.toList))
      }
    }

  def websocketSavedFlow (): Flow[Message, Message, NotUsed] =
    Flow[Message]
        .map(_ => NewAccountEvents.SavedPassPhrase())
        .via(accFlow.savedPassPhraseFlow())
          .map{
            case x:Seq[String] =>
              TextMessage.Strict(x.asJson.noSpaces)
            case _ =>
              TextMessage.Strict(Seq("response"->"error".asJson, "error"->"data isn't correct confirmation passphrase".asJson).asJson.noSpaces)
          }
        .via(reportErrorsFlow)

  def webSocketComparingPassPhraseFlow (confirmationPassPhrase: List[String]): Flow[Message, Message, NotUsed] =
    Flow[Message]
        .map(_ => NewAccountEvents.ConfirmPassPhrase(confirmationPassPhrase))
          .via(accFlow.confirmPassPhraseFlow(confirmationPassPhrase))
          .map{
            case Some(x:UserKey) =>
              TextMessage.Strict(Map ("key" -> x.key, "seed" -> x.seed).asJson.noSpaces)
            case None =>
              TextMessage.Strict(Seq("response"->"error".asJson, "error"->"data isn't correct key-pair".asJson).asJson.noSpaces)
          }


/*

  def websocketSignUpFlow () : Flow[Message, Message, NotUsed] =
    Flow[Message]
        .map (_ => NotUsed)
      .via(accFlow.newAccountFlow())
      .map {
        case x:Seq[String] =>
          TextMessage.Strict(x.asJson.noSpaces)
        case _ =>
          TextMessage.Strict(Seq("response"->"error".asJson, "error"->"data isn't correct passphrase".asJson).asJson.noSpaces)
      }.via(reportErrorsFlow)
*/
/*


  val newAccSource = Source.actorRef[NewAccountEvents.NewAccountEvent](10, OverflowStrategy.fail)
*/
/*
    Flow.fromGraph(GraphDSL.create(){implicit builder =>
      import GraphDSL.Implicits._

      //val merge = builder.add(Merge[NewAcflowByEventTypecountEvents.NewAccountEvent](3))

      val matConfirmation = builder.materializedValue.map{actorRef =>
        NewAccountEvents.ConfirmationOutActorRef(actorRef)
      }

      val matSeed = builder.materializedValue.map{actorRef =>
        NewAccountEvents.SeedOutActorRef(actorRef)
      }

      mapclientEventToAccountEvent().via()
      val messagesToAccountEvent = builder.add(mapclientEventToAccountEvent())

      val accountEventsToMessage = builder.add(mapAccountEventsToMessage())

      mapclientEventToAccountEvent().map{x => newAccActor ! x}

/*
      val accountConfirm =
        Sink.actorRef[NewAccountEvents.NewAccountEvent](newAccActor, NewAccountEvents.ConfirmPassPhrase(confirmationPassPhrase))
*/

      //matConfirmation ~> merge
      //matSeed ~> merge
      //messagesToAccountEvent ~> merge
      //merge ~> accountEventsToMessage
      //localAccActor ~> accountEventsToMessage
      messagesToAccountEvent ~> accountEventsToMessage
      FlowShape(messagesToAccountEvent.in, accountEventsToMessage.out)
    })*/

  def broadcast(): Flow[Message, Message, NotUsed] =
    Flow[Message]
      .mapConcat(_ => List.empty[String]) // Ignore any data sent from the client
      .merge(producer2._2)
      .map(l => TextMessage(l.toString))

  def flowByEventType(): Flow [Message, Message, Any] ={
    implicit val askTimeout: Timeout = Timeout(30.seconds)
    mapclientEventToAccountEvent()
      .mapAsync(1){
        case x@NewAccountEvents.ErrorEvent(_) =>
          Future.successful(x).map(mapAccountEventsToMessage(_))
        case NewAccountEvents.SavedPassPhrase() =>
          log.debug("SavedPassPhrase")
          askActor[NewAccountEvents.SavedPassPhrase](newAccActor, NewAccountEvents.SavedPassPhrase())
            .map(mapAccountEventsToMessage)
        case event =>
          askActor[NewAccountEvents.NewAccountEvent ](newAccActor, event)
            .map(mapAccountEventsToMessage)
    }
  }


  def mapAccountEventsToMessage (event : NewAccountEvents.NewAccountEvent): TextMessage.Strict = event match {
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



  def mapclientEventToAccountEvent (): Flow[Message, NewAccountEvents.NewAccountEvent, NotUsed] =
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


  def websocketSignUpFlow () : Flow[Message, Message, NotUsed] =
    Flow[Message]
        .collect{
          case TextMessage.Strict(msg) => msg
        }
      .map{msg =>
          NotUsed
      }
      .via(accFlow.newAccountFlow())
      .map {
        case x:Seq[String] =>
          TextMessage.Strict(x.asJson.noSpaces)
        case _ =>
          TextMessage.Strict(Seq("response"->"error".asJson, "error"->"data isn't correct passphrase".asJson).asJson.noSpaces)
      }.via(reportErrorsFlow)


  def websocketRegWithPwdFlow(pwd: String): Flow[Message, Message, NotUsed] =
    Flow[Message]
      .map { _ =>
        pwd
      }.via(accFlow.newAccountFlowWithPwd(pwd))
      .map {
        case msg: Protocol.CreateAccount =>
          TextMessage.Strict(Seq("response"->"success".asJson).asJson.noSpaces)
        case _ =>
          TextMessage.Strict(Seq("response"->"error".asJson, "error"->"data isn't correct pwd".asJson).asJson.noSpaces)
    }.via(reportErrorsFlow)

  def reportErrorsFlow[T]: Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) => f.onComplete {
        case Failure(cause) =>
          log.error(s"WS stream failed with $cause")
        case _ => // ignore regular completion
      })

  def publishBlock (pb:PowBlock) = {
    log.debug(s"pb : $pb")
    producer2._1.offer(pb.json.noSpaces)
  }
}

