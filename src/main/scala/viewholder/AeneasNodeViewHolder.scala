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

import akka.actor.ActorRef
import block.{AeneasBlock, PowBlock, PowBlockCompanion}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool, SimpleBoxTransactionSerializer}
import history.AeneasHistory
import history.sync.AeneasSynchronizer.{PreStartDownloadRequest, SynchronizerAlive}
import history.sync.VerySimpleSyncInfo
import mining.Miner.{MinerAlive, StartMining, StopMining}
import network.BlockchainDownloader.DownloadEnded
import scorex.core.NodeViewHolder.NodeViewHolderEvent
import scorex.core.serialization.Serializer
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.{ModifierTypeId, NodeViewHolder, NodeViewModifier}
import settings.SimpleMiningSettings
import state.SimpleMininalState
import viewholder.AeneasNodeViewHolder.{NodeViewEvent, NotifySubscribersOnRestore}
import wallet.AeneasWallet

import scala.collection.mutable


/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.01.18.
  */
//noinspection ScalaStyle
class AeneasNodeViewHolder(settings : ScorexSettings, minerSettings: SimpleMiningSettings)
  extends NodeViewHolder[PublicKey25519Proposition, SimpleBoxTransaction, AeneasBlock] {
   override type SI = VerySimpleSyncInfo
   override type HIS = AeneasHistory
   override type MS = SimpleMininalState
   override type VL = AeneasWallet
   override type MP = SimpleBoxTransactionMemPool

   private var synchronizerStatus = false
   private var minerStatus = false
   /**
     * Restore a local view during a node startup.
     * If no any stored view or other peers in network found, None is to be returned.
     *
     * If it is the first launch, history should be empty and
     * specific signal will be send to sychronizer.
     */
   override def restoreState(): Option[(HIS, MS, VL, MP)] = {
      log.debug(s"AeneasWallet.exists : ${AeneasWallet.exists(settings)}")
      if (AeneasWallet.exists(settings) && AeneasWallet.nonEmpty(settings)) {
         val history =  AeneasHistory.readOrGenerate(settings, minerSettings)
         val minState = SimpleMininalState.readOrGenerate(settings)
         val wallet =   AeneasWallet.readOrGenerate(settings, 1)
         val memPool =  SimpleBoxTransactionMemPool.emptyPool

         // if history exists, it will compare blockchains through the SyncInfo.
         history match {
            case content =>
               Some(content, minState, wallet, memPool)
            // otherwise, it requests the history donwload and send message
            case _ =>
               self ! NotifySubscribersOnRestore
               Some(AeneasHistory.emptyHistory(minerSettings), minState, wallet, memPool)
         }

      } else None
   }

   /**
     * Hard-coded initial view all the honest nodes in a network are making progress from.
     */
   override protected def genesisState: (HIS, MS, VL, MP) = {
      log.debug("start genesisState")
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

      (history, mininalState, wallet, SimpleBoxTransactionMemPool.emptyPool)
   }

   /**
     * Serializers for modifiers, to be provided by a concrete instantiation
     */
   override val modifierSerializers: Map[ModifierTypeId, Serializer[_ <: NodeViewModifier]] =
      Map(PowBlock.ModifierTypeId -> PowBlockCompanion,
      Transaction.ModifierTypeId -> SimpleBoxTransactionSerializer)
   /**
     *
     */
   override val networkChunkSize: Int = settings.network.networkChunkSize

   private val aeneasSubscribers = mutable.Map[AeneasNodeViewHolder.NodeViewEvent.Value, Seq[ActorRef]]()

   protected def notifyAeneasSubscribers[E <: NodeViewHolderEvent](eventType: NodeViewEvent.Value, signal: E): Unit = {
      aeneasSubscribers.getOrElse(eventType, Seq()).foreach(_ ! signal)
   }

   /**
     * Handler for specific Aeneas signals.
     * @see AeneasSynchronizer companion object for more signals.
     * @see Miner companion object for more signals.
     */
   protected def handleAeneasSubscribe: Receive = {
      case AeneasNodeViewHolder.AeneasSubscribe(events) =>
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
         if (synchronizerStatus && minerStatus) {
            notifyAeneasSubscribers(NodeViewEvent.PreStartDownloadRequest, PreStartDownloadRequest)
            notifyAeneasSubscribers(NodeViewEvent.StopMining, StopMining)
         }
      case _ =>
   }

   protected def onDownloadEnded : Receive = {
      case DownloadEnded =>
         notifyAeneasSubscribers(NodeViewEvent.StartMining, StartMining)
   }

   /**
     * Signal is sent when synchronizer actor is alive.
     * It happens when application has first-time launch
     * and full blockchain download should be requested.
     */
   protected def onSynchronizerAlive : Receive = {
      case SynchronizerAlive =>
         synchronizerStatus = true
         self ! NotifySubscribersOnRestore
   }

   /**
     * Signal is sent when miner actor is alive.
     * It happens when application has first-time launch
     * and full blockchain download should be requested.
     */
   protected def onMinerAlive : Receive = {
      case MinerAlive =>
         minerStatus = true
         self ! NotifySubscribersOnRestore
   }

   override def receive: Receive =
      super.handleSubscribe orElse
         super.compareViews orElse
         super.processRemoteModifiers orElse
         super.processLocallyGeneratedModifiers orElse
         super.getCurrentInfo orElse
         super.getNodeViewChanges orElse
         handleAeneasSubscribe orElse
         onSynchronizerAlive orElse
         onMinerAlive orElse {
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

}
