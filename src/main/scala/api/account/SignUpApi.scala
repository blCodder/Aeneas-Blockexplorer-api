package api.account

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.iohk.iodb.LSMStore
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings

import scala.concurrent.ExecutionContext

/**
  * @author luger. Created on 01.03.18.
  * @version ${VERSION}
  */
class SignUpApi(minerRef: ActorRef, nodeViewHolderRef: ActorRef, aeneasSettingsVal: AeneasSettings, store: LSMStore)(
  implicit systemVal: ActorSystem, executionContextVal: ExecutionContext) extends SignUpService with ScorexLogging {

  override lazy val aeneasSettings: AeneasSettings = aeneasSettingsVal

  override protected lazy val nodeViewHolder: ActorRef = nodeViewHolderRef

  override protected lazy val miner: ActorRef = minerRef

  override protected implicit lazy val system: ActorSystem = systemVal

  override implicit val executionContext: ExecutionContext = executionContextVal

  override val newAccActor: ActorRef = system.actorOf(Props(new NewAccActor(store)))
  override val loginActor: ActorRef = system.actorOf(Props(new LoginActor(aeneasSettings.scorexSettings, store)))

  def route: Route = path("aeneas") {
      handleWebSocketMessages(flowByEventType())
    }

}

