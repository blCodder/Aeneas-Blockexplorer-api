/*
 * Copyright 2018, Aeneas Platform.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package viewholder

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.actor.ActorRef
import api.account.SignUpMessagesType.{LoggedIn, Logout, PwdConfirmed}
import block.{AeneasBlock, PowBlock, PowBlockCompanion}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool, SimpleBoxTransactionSerializer}
import history.AeneasHistory
import history.sync.AeneasSynchronizer.{PreStartDownloadRequest, SynchronizerAlive}
import history.sync.VerySimpleSyncInfo
import mining.Miner.{MinerAlive, StartMining, StopMining}
import network.BlockchainDownloader.DownloadEnded
import scorex.core.ModifierTypeId
import scorex.core.mainviews.NodeViewHolder.{CurrentView, EventType}
import scorex.core.mainviews.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.core.mainviews.{NodeViewHolder, NodeViewModifier}
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, NodeViewHolderEvent}
import scorex.core.serialization.Serializer
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.{ByteStr, ScorexLogging}
import scorex.crypto.encode.Base58
import settings.SimpleMiningSettings
import state.SimpleMininalState
import viewholder.AeneasNodeViewHolder.{AeneasSubscribe, NodeViewEvent, NotifySubscribersOnRestore}
import wallet.AeneasWallet

import scala.collection.mutable
import scala.util.Success


/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.01.18.
  */
//noinspection ScalaStyle
class AeneasNodeViewHolder(settings : ScorexSettings, minerSettings: SimpleMiningSettings)
  extends NodeViewHolder[PublicKey25519Proposition, SimpleBoxTransaction, AeneasBlock]
   with ScorexLogging {
   override type SI = VerySimpleSyncInfo
   override type HIS = AeneasHistory
   override type MS = SimpleMininalState
   override type VL = AeneasWallet
   override type MP = SimpleBoxTransactionMemPool
   override type NodeView = (HIS, MS, Option[VL], MP)

   private lazy val synchronizerStatus : AtomicBoolean = new AtomicBoolean(false)
   private lazy val minerStatus : AtomicBoolean = new AtomicBoolean(false)
   private lazy val minerActivation : AtomicBoolean = new AtomicBoolean(false)
   private lazy val prestartDownloadEnded : AtomicBoolean = new AtomicBoolean(false)

   //var nodeView = restoreState().getOrElse(updateChainState().get)


   private def checkShouldUpdate() : Boolean =
      AeneasHistory.readOrGenerate(settings, minerSettings).height <= 0

   /**
     * Restore a local view during a node startup.
     * If no any stored view or other peers in network found, None is to be returned.
     *
     * If it is the first launch, history should be empty and
     * specific signal will be send to synchronizer.
     */
   override def restoreState(): Option[NodeView] = {
      minerActivation.set(false)
      if (checkShouldUpdate()) None

      AeneasWallet.walletFile(settings)
      log.debug(s"AeneasWallet.exists : ${AeneasWallet.exists(settings)}")
      val history = AeneasHistory.readOrGenerate(settings, minerSettings)
      val minState = SimpleMininalState.readOrGenerate(settings)
      val wallet = None //AeneasWallet.readOrGenerate(settings, 1)
      val memPool = SimpleBoxTransactionMemPool.emptyPool

      log.debug(s"AeneasViewHolder.restoreState : history length is ${history.height}")
      notifySubscribers(EventType.HistoryChanged, ChangedHistory(history))
      Some(history, minState, wallet, memPool)
   }

   /**
     * Restore a local view during a node startup.
     * If no any stored view or other peers in network found, None is to be returned.
     *
     * If it is the first launch, history should be empty and
     * specific signal will be send to synchronizer.
     */

   def updateChainState() : Option[NodeView] = {
      self ! NotifySubscribersOnRestore

      // should be empty
      val history = AeneasHistory.readOrGenerate(settings, minerSettings)
      val minState = SimpleMininalState.readOrGenerate(settings)
      val wallet = None//AeneasWallet.readOrGenerate(settings, 1)
      val memPool = SimpleBoxTransactionMemPool.emptyPool
      notifyAeneasSubscribers(NodeViewEvent.UpdateHistory, ChangedHistory(history))
      Some(history, minState, wallet, memPool)
   }

   /**
     * Hard-coded initial view all the honest nodes in a network are making progress from.
     * It doesn't care for user-nodes.
     */
   override protected def genesisState: NodeView = ???

   /**
     * Serializers for modifiers, to be provided by a concrete instantiation
     */
   override val modifierSerializers: Map[ModifierTypeId, Serializer[_ <: NodeViewModifier]] =
      Map(PowBlock.ModifierTypeId -> PowBlockCompanion,
      Transaction.ModifierTypeId -> SimpleBoxTransactionSerializer)

   override val networkChunkSize: Int = settings.network.networkChunkSize

   private var aeneasSubscribers = mutable.Map[AeneasNodeViewHolder.NodeViewEvent.Value, Seq[ActorRef]]()

   protected def notifyAeneasSubscribers[E <: NodeViewHolderEvent](eventType: NodeViewEvent.Value, signal: E): Unit = {
      aeneasSubscribers.getOrElse(eventType, Seq()).foreach(_ ! signal)
   }

   /**
     * Handler for specific Aeneas signals.
     * @see AeneasSynchronizer companion object for more signals.
     * @see Miner companion object for more signals.
     */
   protected def handleAeneasSubscribe: Receive = {
      case AeneasSubscribe(events) =>
         events.foreach { evt =>
            val current = aeneasSubscribers.getOrElse(evt, Seq())
            aeneasSubscribers.put(evt, current :+ sender())
         }
   }

   /**
     * Signal happens when application starts at first time
     * and full blockchain download should be requested.
     */
   protected def onRestoreMessage : Receive = {
      case NotifySubscribersOnRestore =>
         log.debug(
           s"""OnRestore message was received with
              |sync : ${synchronizerStatus.get()} &&
              |miner : ${minerStatus.get()}
            """.stripMargin)
         if (synchronizerStatus.get() && minerStatus.get()) {
            notifyAeneasSubscribers(NodeViewEvent.PreStartDownloadRequest, PreStartDownloadRequest)
            notifyAeneasSubscribers(NodeViewEvent.StopMining, StopMining)
            nodeView._1.downloadProcess.compareAndSet(false, true)
            notifyAeneasSubscribers(NodeViewEvent.UpdateHistory, ChangedHistory(history()))
            synchronizerStatus.compareAndSet(true, false)
            minerStatus.compareAndSet(true, false)
         }
   }

   protected def onDownloadEnded : Receive = {
      case DownloadEnded(hisReader) =>
         hisReader match {
           case Some(reader) =>
              updateNodeView(hisReader, None, None, None)
              nodeView._1.downloadProcess.compareAndSet(true, false)
              log.debug(s"vault value: ${nodeView._3}")
              if (nodeView._3.nonEmpty) notifyAeneasSubscribers(NodeViewEvent.StartMining, StartMining)// check Vault
           case None =>
               self ! NotifySubscribersOnRestore
         }
   }

   protected def onLoggedIn :Receive = {
      case logged:LoggedIn =>
         Base58.decode(logged.seed) match {
            case Success(seedBytes) =>
               val newVault = AeneasWallet.readOrGenerate(settings, ByteStr(seedBytes), 1)
               updateNodeView(None, None, Option(newVault), None)
               log.debug(
                 s"""set new vault value: ${nodeView._3}
                    |prestartDownloadEnded.get() : ${prestartDownloadEnded.get()}
                  """.stripMargin)
               if (prestartDownloadEnded.get()) notifyAeneasSubscribers(NodeViewEvent.StartMining, StartMining)
            case _ =>
         }
   }

   protected def onLogOut :Receive = {
      case Logout(_) =>
         updateNodeView(None, None, None, None)
         notifyAeneasSubscribers(NodeViewEvent.StopMining, StopMining)
      case _ =>
   }
   /**
     * Signal is sent when synchronizer actor is alive.
     * It happens when application has first-time launch
     * and full blockchain download should be requested.
     */
   protected def onSynchronizerAlive : Receive = {
      case SynchronizerAlive =>
         synchronizerStatus.compareAndSet(false, true)
         if (!minerActivation.get() || !minerStatus.get())
            self ! NotifySubscribersOnRestore
         notifyAeneasSubscribers(NodeViewEvent.UpdateHistory, ChangedHistory(history()))
   }

   /**
     * Signal is sent when miner actor is alive.
     * It happens when application has first-time launch
     * and full blockchain download should be requested.
     */
   protected def onMinerAlive : Receive = {
      case MinerAlive =>
         log.debug("AeneasViewHolder : Miner is alive")
         minerStatus.compareAndSet(false, true)
         if (minerActivation.get()) {
            notifyAeneasSubscribers(NodeViewEvent.StartMining, StartMining)
         }
         if (!synchronizerStatus.get())
            self ! NotifySubscribersOnRestore
   }

   override protected def getCurrentInfo: Receive = {
      case GetDataFromCurrentView(f) =>
         sender() ! f(CurrentView(history(), minimalState(), vault(), memoryPool()))
   }

   override def receive: Receive =
      onSynchronizerAlive orElse
         onMinerAlive orElse
         handleAeneasSubscribe orElse
         onRestoreMessage orElse
         getCurrentInfo orElse
         onDownloadEnded orElse
         onLoggedIn orElse
         onLogOut orElse
         super.processLocallyGeneratedModifiers orElse
         super.handleSubscribe orElse
         super.compareViews orElse
         super.processRemoteModifiers orElse
         super.getNodeViewChanges orElse {
         case a: Any => log.error("Strange input: " + a)
      }

   /**
     * The main data structure a node software is taking care about, a node view consists
     * of four elements to be updated atomically: history (log of persistent modifiers),
     * state (result of log's modifiers application to pre-historical(genesis) state,
     * user-specific information stored in vault (it could be e.g. a wallet), and a memory pool.
     */
   private val nodeViewState = new AtomicReference(restoreState().getOrElse(updateChainState().get))
   override protected def updateNodeViewState(newNodeView:NodeView):Unit = nodeViewState.set(newNodeView)
   override protected def nodeView: NodeView = nodeViewState.get()
}

object AeneasNodeViewHolder {
   object NodeViewEvent extends Enumeration {
      // miner events
      val StartMining : NodeViewEvent.Value = Value(1)
      val StopMining : NodeViewEvent.Value = Value(2)

      // synchronizer events
      val PreStartDownloadRequest : NodeViewEvent.Value = Value(3)
      val UpdateHistory : NodeViewEvent.Value = Value(4)
   }
   case class AeneasSubscribe(minerEvents : Seq[NodeViewEvent.Value])

   case object NotifySubscribersOnRestore

   case object ActivateMining
}
