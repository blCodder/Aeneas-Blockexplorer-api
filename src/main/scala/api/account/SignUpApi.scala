package api.account

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import commons.SimpleBoxTransactionMemPool
import history.AeneasHistory
import io.iohk.iodb.LSMStore
import scorex.core.mainviews.NodeViewHolder.CurrentView
import scorex.core.mainviews.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings
import state.SimpleMininalState
import wallet.AeneasWallet

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, ExecutionContext}

/**
  * @author luger. Created on 01.03.18.
  * @version ${VERSION}
  */
class SignUpApi(minerRef: ActorRef, nodeViewHolderRef: ActorRef, aeneasSettingsVal: AeneasSettings, store: LSMStore)(
  implicit systemVal: ActorSystem, executionContextVal: ExecutionContext) extends SignUpService with ScorexLogging {
  private implicit val currentViewTimer: FiniteDuration = 75.second
  private implicit val currentTimeout = new Timeout(currentViewTimer)

  override lazy val aeneasSettings: AeneasSettings = aeneasSettingsVal

  override protected lazy val nodeViewHolder: ActorRef = nodeViewHolderRef

  val currentViewAwait = ask(nodeViewHolderRef, GetDataFromCurrentView(SignUpApi.applyHistory)).mapTo[AeneasHistory]
  val history = Await.result(currentViewAwait, currentViewTimer)

  override protected lazy val miner: ActorRef = minerRef

  override protected implicit lazy val system: ActorSystem = systemVal

  override implicit val executionContext: ExecutionContext = executionContextVal

  override val newAccActor: ActorRef = system.actorOf(Props(new NewAccActor(store)))
  override val loginActor: ActorRef = system.actorOf(Props(new LoginActor(history, aeneasSettings.scorexSettings, store)))

  def route: Route = path("aeneas") {
      handleWebSocketMessages(flowByEventType())
    }

}

object SignUpApi {
  def applyHistory(currentView: CurrentView[AeneasHistory,
     SimpleMininalState, AeneasWallet, SimpleBoxTransactionMemPool]) : AeneasHistory = {
    currentView.history
  }
}

