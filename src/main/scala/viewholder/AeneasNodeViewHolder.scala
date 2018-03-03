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

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import block.{AeneasBlock, PowBlock, PowBlockCompanion}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool, SimpleBoxTransactionSerializer}
import history.SimpleHistory
import history.sync.AeneasSynchronizer.PreStartDownloadRequest
import history.sync.VerySimpleSyncInfo
import mining.Miner.StopMining
import scorex.core.{ModifierTypeId, NodeViewHolder, NodeViewModifier}
import scorex.core.NodeViewHolder.{EventType, NodeViewHolderEvent}
import scorex.core.serialization.Serializer
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import settings.SimpleMiningSettings
import state.SimpleMininalState
import viewholder.AeneasNodeViewHolder.NodeViewEvent
import wallet.AeneasWallet

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.01.18.
  */
//noinspection ScalaStyle
class AeneasNodeViewHolder(settings : ScorexSettings, minerSettings: SimpleMiningSettings)
  extends NodeViewHolder[PublicKey25519Proposition, SimpleBoxTransaction, AeneasBlock] {
   override type SI = VerySimpleSyncInfo
   override type HIS = SimpleHistory
   override type MS = SimpleMininalState
   override type VL = AeneasWallet
   override type MP = SimpleBoxTransactionMemPool
   /**
     * Restore a local view during a node startup. If no any stored view found
     * (e.g. if it is a first launch of a node) None is to be returned
     */
   override def restoreState(): Option[(HIS, MS, VL, MP)] = {
      log.debug(s"AeneasWallet.exists : ${AeneasWallet.exists(settings)}")
      if (AeneasWallet.exists(settings) && AeneasWallet.nonEmpty(settings)) {
         val history =  SimpleHistory.readOrGenerate(settings, minerSettings)
         val minState = SimpleMininalState.readOrGenerate(settings)
         val wallet =   AeneasWallet.readOrGenerate(settings, 1)
         val memPool =  SimpleBoxTransactionMemPool.emptyPool

         // crutch for the Miner and Synchronizer actors startup delay
         context.system.scheduler.scheduleOnce(new FiniteDuration(1250, TimeUnit.MILLISECONDS))

         history match {
            case content =>
               Some(content, minState, wallet, memPool)
            case _ =>
               notifyAeneasSubscribers(NodeViewEvent.PreStartDownloadRequest, PreStartDownloadRequest)
               notifyAeneasSubscribers(NodeViewEvent.StopMining, StopMining)
               Some(SimpleHistory.emptyHistory(minerSettings), minState, wallet, memPool)
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

      var history = SimpleHistory.readOrGenerate(settings, minerSettings)
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

   protected def notifyAeneasSubscribers[E <: NodeViewHolderEvent](eventType: NodeViewEvent.Value, signal: E) = {
      aeneasSubscribers.getOrElse(eventType, Seq()).foreach(_ ! signal)
   }

   protected def handleAeneasSubscribe: Receive = {
      case AeneasNodeViewHolder.AeneasSubscribe(events) =>
         events.foreach { evt =>
            val current = aeneasSubscribers.getOrElse(evt, Seq())
            aeneasSubscribers.put(evt, current :+ sender())
         }
   }
}


object AeneasNodeViewHolder {
   object NodeViewEvent extends Enumeration {
      // miner events
      val StartMining : NodeViewEvent.Value = Value(1)
      val StopMining : NodeViewEvent.Value = Value(2)

      // syncronizer events
      val PreStartDownloadRequest : NodeViewEvent.Value = Value(3)
      val DowloadStart : NodeViewEvent.Value = Value(4)
      val RequestHeight : NodeViewEvent.Value = Value(5)
      val CheckModifiersToDownload : NodeViewEvent.Value = Value(6)
      val DonwloadEnded : NodeViewEvent.Value = Value(7)
   }
   case class AeneasSubscribe(minerEvents : Seq[NodeViewEvent.Value])

}
