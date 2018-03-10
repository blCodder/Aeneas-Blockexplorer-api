package api.auth

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import api.Protocol
import api.auth.NewAccountEvents.GeneratedConfirmationPassPhrase
import io.circe.Json
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings

import scala.concurrent.ExecutionContext
import scala.util.Failure

/**
  * @author luger. Created on 01.03.18.
  * @version ${VERSION}
  */
//sealed trait
class AuthApi(aeneasSettings: AeneasSettings)(implicit system: ActorSystem, executionContext: ExecutionContext) extends ScorexLogging{
  val passPhraseMixingService = new PassPhraseMixingService(aeneasSettings)

  val accFlow = CreateAccountFlow(passPhraseMixingService)

  def route: Route = path("aeneas_register") {
      parameters('password, 'passwordConfirm) { (password, passwordConfirm) =>
        handleWebSocketMessages(websocketRegWithPwdFlow(pwd = password, pwdConfirm = passwordConfirm))
      }
    } ~ path("signup") {
      handleWebSocketMessages(websocketSignUpFlow())
    } ~ path("passphrasesaved") {
      handleWebSocketMessages(websocketSavedFlow())
    } ~ path("confirmpassphrase") {
    parameter('passphrase) {passPhraseConfirm =>
      val ppConfirm = passPhraseConfirm.split("\\,")
      handleWebSocketMessages(websocketSavedFlow())
    }
  }

  def websocketSavedFlow (): Flow[Message, Message, NotUsed] =
    Flow[Message]
        .map(_ => NewAccountEvents.SavedPassPhrase())
        .via(accFlow.savedPassPhraseFlow())
          .map{
            case x:Seq[String] =>
              TextMessage.Strict(Json.fromValues(x.map(Json.fromString)).toString())
            case _ =>
              TextMessage.Strict(Json.fromFields(Seq("error"->Json.fromString ("data isn't correct confirmation passphrase"))).toString())
          }
        .via(reportErrorsFlow)


  def websocketSignUpFlow () : Flow[Message, Message, NotUsed] =
    Flow[Message]
      .map(_ => NewAccountEvents.CallToSignUp()) /** Ignore any data sent from the client**/
      .via(accFlow.newAccountFlow())
      .map {
        case x:Seq[String] =>
          TextMessage.Strict(Json.fromValues(x.map(Json.fromString)).toString())
        case _ =>
          TextMessage.Strict(Json.fromFields(Seq("error"->Json.fromString ("data isn't correct passphrase"))).toString())
      }.via(reportErrorsFlow)

  def websocketRegWithPwdFlow(pwd: String, pwdConfirm:String): Flow[Message, Message, Any] =
    Flow[Message]
      .collect {
        case TextMessage.Strict(msg) => msg
      }.via(accFlow.newAccountFlowWithPwd(pwd))
      .map {
        case msg: Protocol.CreateAccount =>
          TextMessage.Strict(msg.password)
    }.via(reportErrorsFlow)

  def reportErrorsFlow[T]: Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) => f.onComplete {
        case Failure(cause) =>
          log.error(s"WS stream failed with $cause")
        case _ => // ignore regular completion
      })
}