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

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorRef
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
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.utils.ScorexLogging
import settings.SimpleMiningSettings
import state.SimpleMininalState
import viewholder.AeneasNodeViewHolder.{AeneasSubscribe, NodeViewEvent, NotifySubscribersOnRestore}
import wallet.AeneasWallet

import scala.collection.mutable


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

   private lazy val synchronizerStatus : AtomicBoolean = new AtomicBoolean(false)
   private lazy val minerStatus : AtomicBoolean = new AtomicBoolean(false)
   private lazy val minerActivation : AtomicBoolean = new AtomicBoolean(false)

   override protected var nodeView = restoreState().getOrElse(updateChainState().getOrElse(genesisState))

   private def checkGenesisAvaliable() : Boolean = {
      val genesisEnv = Option(System.getenv("AENEAS_GENESIS"))
      val genesisFile = new File(genesisEnv.getOrElse(""))
      if (genesisFile.exists() && genesisEnv.isDefined) {
         genesisFile.createNewFile()
         true
      }
      false
   }

   private def checkShouldUpdate() : Boolean = {
      val history = AeneasHistory.readOrGenerate(settings, minerSettings)
      if (history.height <= 0) {
         true
      }
      false
   }

   /**
     * Restore a local view during a node startup.
     * If no any stored view or other peers in network found, None is to be returned.
     *
     * If it is the first launch, history should be empty and
     * specific signal will be send to synchronizer.
     */
   override def restoreState(): Option[(HIS, MS, VL, MP)] = {
      minerActivation.set(false)
      if (checkGenesisAvaliable()) None
      if (checkShouldUpdate()) None

      AeneasWallet.walletFile(settings)
      log.debug(s"AeneasWallet.exists : ${AeneasWallet.exists(settings)}")
      val history = AeneasHistory.readOrGenerate(settings, minerSettings)
      val minState = SimpleMininalState.readOrGenerate(settings)
      val wallet = AeneasWallet.readOrGenerate(settings, 1)
      val memPool = SimpleBoxTransactionMemPool.emptyPool

      log.debug(s"AeneasViewHolder.restoreState : history length is ${history.height}")
      Some(history, minState, wallet, memPool)
   }

   /**
     * Restore a local view during a node startup.
     * If no any stored view or other peers in network found, None is to be returned.
     *
     * If it is the first launch, history should be empty and
     * specific signal will be send to synchronizer.
     */

   def updateChainState() : Option[(HIS, MS, VL, MP)] = {
      if (checkGenesisAvaliable()) None
      self ! NotifySubscribersOnRestore

      // should be empty
      val history = AeneasHistory.readOrGenerate(settings, minerSettings)
      val minState = SimpleMininalState.readOrGenerate(settings)
      val wallet = AeneasWallet.readOrGenerate(settings, 1)
      val memPool = SimpleBoxTransactionMemPool.emptyPool
   }

   /**
     * Hard-coded initial view all the honest nodes in a network are making progress from.
     */
   override protected def genesisState: (HIS, MS, VL, MP) = {
      log.debug("AeneasNodeViewHolder : Genesis – started")
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val genesisBlock = new PowBlock(minerSettings.GenesisParentId,
         System.currentTimeMillis(),
         1,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      var history = AeneasHistory.readOrGenerate(settings, minerSettings)
      history = history.append(genesisBlock).get._1

      log.debug(s"NodeViewHolder : Genesis Block : ${genesisBlock.json.toString()}")
      log.info(s"NodeViewHolder : History height is ${history.storage.height}, ${history.height}")

      val mininalState = SimpleMininalState.genesisState(settings, Seq(genesisBlock))
      val wallet = AeneasWallet.genesisWallet(settings, Seq(genesisBlock))

      minerActivation.compareAndSet(false, true)

      log.debug(s"AeneasNodeViewHolder : Genesis is ended, miner alive : ${minerStatus.get()}, " +
                s"genesis miner: ${minerActivation.get()} ")

      (history, mininalState, wallet, SimpleBoxTransactionMemPool.emptyPool)
   }

   /**
     * Serializers for modifiers, to be provided by a concrete instantiation
     */
   override val modifierSerializers: Map[ModifierTypeId, Serializer[_ <: NodeViewModifier]] =
      Map(PowBlock.ModifierTypeId -> PowBlockCompanion,
      Transaction.ModifierTypeId -> SimpleBoxTransactionSerializer)

   override val networkChunkSize: Int = settings.network.networkChunkSize

   private var aeneasSubscribers = mutable.Map[AeneasNodeViewHolder.NodeViewEvent.Value, Seq[ActorRef]]()

   protected def notifyAeneasSubscribers[E <: NodeViewHolderEvent](eventType: NodeViewEvent.Value, signal: E): Unit = {
      log.debug(s"Aeneas notify was called with signal ${signal.toString}")
      val filtered = aeneasSubscribers.getOrElse(eventType, Seq())
      aeneasSubscribers.getOrElse(eventType, Seq()).foreach(_ ! signal)
   }

   /**
     * Handler for specific Aeneas signals.
     * @see AeneasSynchronizer companion object for more signals.
     * @see Miner companion object for more signals.
     */
   protected def handleAeneasSubscribe: Receive = {
      case AeneasSubscribe(events) =>
         log.debug(s"Registered ${events.size} events")
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
         log.debug(s"OnRestore message was received with " +
            s"sync : ${synchronizerStatus.get()} && " +
            s"miner : ${minerStatus.get()}")
         if (synchronizerStatus.get() && minerStatus.get()) {
            notifyAeneasSubscribers(NodeViewEvent.PreStartDownloadRequest, PreStartDownloadRequest)
            notifyAeneasSubscribers(NodeViewEvent.StopMining, StopMining)
            notifySubscribers(EventType.HistoryChanged, ChangedHistory(history()))
            synchronizerStatus.compareAndSet(true, false)
            minerStatus.compareAndSet(true, false)
         }
      case _ =>
   }

   protected def onDownloadEnded : Receive = {
      case DownloadEnded(hisReader) =>
         hisReader match {
           case Some(reader) =>
              updateNodeView(hisReader, None, None, None)
              notifyAeneasSubscribers(NodeViewEvent.StartMining, StartMining)
           case None =>
               self ! NotifySubscribersOnRestore
         }
   }

   /**
     * Signal is sent when synchronizer actor is alive.
     * It happens when application has first-time launch
     * and full blockchain download should be requested.
     */
   protected def onSynchronizerAlive : Receive = {
      case SynchronizerAlive =>
         log.debug("AeneasViewHolder : Synchronizer is alive")
         synchronizerStatus.compareAndSet(false, true)
         if (!minerActivation.get() || !minerStatus.get()) {
            log.debug("AeneasViewHolder : Synchronizer will send restore message")
            self ! NotifySubscribersOnRestore
         }
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
            log.debug(s"AeneasViewHolder : miner is alive with genesis state")
            notifyAeneasSubscribers(NodeViewEvent.StartMining, StartMining)
         }
         else if (!synchronizerStatus.get())
            self ! NotifySubscribersOnRestore
   }

   override protected def getCurrentInfo: Receive = {
      case GetDataFromCurrentView(f) =>
         log.debug("AeneasViewHolder: Data from Miner was received")
         sender() ! f(CurrentView(history(), minimalState(), vault(), memoryPool()))
         log.debug("AeneasViewHolder: CurrentView was send back to Miner")
   }

   override def receive: Receive =
      onSynchronizerAlive orElse
         onMinerAlive orElse
         handleAeneasSubscribe orElse
         onRestoreMessage orElse
         getCurrentInfo orElse
         onDownloadEnded orElse
         super.processLocallyGeneratedModifiers orElse
         super.handleSubscribe orElse
         super.compareViews orElse
         super.processRemoteModifiers orElse
         super.getNodeViewChanges orElse {
         case a: Any => log.error("Strange input: " + a)
      }
}

object AeneasNodeViewHolder {
   object NodeViewEvent extends Enumeration {
      // miner events
      val StartMining : NodeViewEvent.Value = Value(1)
      val StopMining : NodeViewEvent.Value = Value(2)

      // synchronizer events
      val PreStartDownloadRequest : NodeViewEvent.Value = Value(3)
      val PreStartDownloadResponce : NodeViewEvent.Value = Value(4)
   }
   case class AeneasSubscribe(minerEvents : Seq[NodeViewEvent.Value])

   case object NotifySubscribersOnRestore

   case object ActivateMining
}
