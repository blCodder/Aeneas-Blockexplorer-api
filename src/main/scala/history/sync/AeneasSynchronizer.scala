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

package history.sync

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import history.sync.AeneasSynchronizer.{PreStartDownloadRequest, SynchronizerAlive}
import scorex.core.NodeViewHolder.{GetNodeViewChanges, NodeViewHolderEvent, Subscribe}
import scorex.core._
import scorex.core.consensus.{HistoryReader, SyncInfo}
import scorex.core.network.NetworkController.SendToNetwork
import scorex.core.network._
import scorex.core.network.message.{Message, ModifiersSpec, SyncInfoMessageSpec}
import scorex.core.network.peer.PeerManager
import scorex.core.settings.NetworkSettings
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.{MempoolReader, Transaction}
import scorex.core.utils.NetworkTimeProvider
import viewholder.AeneasNodeViewHolder
import viewholder.AeneasNodeViewHolder.AeneasSubscribe

import scala.concurrent.duration.FiniteDuration

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 22.02.18.
  */

class AeneasSynchronizer [P <: Proposition,
TX <: Transaction[P],
SI <: SyncInfo,
SIS <: SyncInfoMessageSpec[SI],
PMOD <: PersistentNodeViewModifier,
HR <: HistoryReader[PMOD, SI],
MR <: MempoolReader[TX]] (networkControllerRef: ActorRef,
                         viewHolderRef: ActorRef,
                         localInterfaceRef: ActorRef,
                         syncInfoSpec: SIS,
                         networkSettings: NetworkSettings,
                         timeProvider: NetworkTimeProvider) extends
  NodeViewSynchronizer [P, TX, SI, SIS, PMOD, HR, MR] (networkControllerRef,
    viewHolderRef, localInterfaceRef, syncInfoSpec, networkSettings, timeProvider) {


   override def preStart(): Unit = {
      //register as a handler for synchronization-specific types of messages
      val messageSpecs = Seq(invSpec, requestModifierSpec, ModifiersSpec, syncInfoSpec)
      networkControllerRef ! NetworkController.RegisterMessagesHandler(messageSpecs, self)

      val pmEvents = Seq(
         PeerManager.EventType.Handshaked,
         PeerManager.EventType.Disconnected
      )
      networkControllerRef ! NetworkController.SubscribePeerManagerEvent(pmEvents)

      val vhEvents = Seq(
         // superclass events
         NodeViewHolder.EventType.HistoryChanged,
         NodeViewHolder.EventType.MempoolChanged,
         NodeViewHolder.EventType.FailedTransaction,
         NodeViewHolder.EventType.SuccessfulTransaction,
         NodeViewHolder.EventType.SyntacticallyFailedPersistentModifier,
         NodeViewHolder.EventType.SemanticallyFailedPersistentModifier,
         NodeViewHolder.EventType.SuccessfulSyntacticallyValidModifier,
         NodeViewHolder.EventType.SuccessfulSemanticallyValidModifier
      )

      viewHolderRef ! Subscribe(vhEvents)

      val aeneasEvents = Seq(
         AeneasNodeViewHolder.NodeViewEvent.PreStartDownloadRequest
      )

      viewHolderRef ! AeneasSubscribe(aeneasEvents)

      // We enable state change for downloading blocks process.
      viewHolderRef ! GetNodeViewChanges(history = true, state = true, vault = false, mempool = true)

      statusTracker.scheduleSendSyncInfo()

      viewHolderRef ! SynchronizerAlive
   }

   /**
     * React on `PreStartDownloadRequest` message,
     * and calls `findModifiersToDownload` method.
     */

   def onDownloadRequest: Receive = {
      case PreStartDownloadRequest =>
   }

   def requestDownload(typeId: ModifierTypeId, modifierId: ModifierId) = {
      val msg = Message(requestModifierSpec, Right(typeId -> Seq(modifierId)), None)
      networkControllerRef ! SendToNetwork(msg, Broadcast)
      // TODO: track delivery
   }


   override protected def viewHolderEvents: Receive =
         onDownloadRequest orElse
         super.viewHolderEvents

   override def receive: Receive =
      getLocalSyncInfo orElse
         processSync orElse
         processSyncStatus orElse
         processInv orElse
         modifiersReq orElse
         requestFromLocal orElse
         responseFromLocal orElse
         modifiersFromRemote orElse
         viewHolderEvents orElse
         peerManagerEvents orElse
         checkDelivery orElse {
         case a: Any => log.error("Strange input: " + a)
      }
}

object AeneasSynchronizer {

   private val toDownloadCheckInterval = new FiniteDuration(3, TimeUnit.SECONDS)

   sealed trait SyncronizerEvent extends NodeViewHolderEvent

   case object SynchronizerAlive extends NodeViewHolderEvent

   case object PreStartDownloadRequest extends SyncronizerEvent

   case class DonwloadStart(typeId: ModifierTypeId, modifierId: ModifierId) extends SyncronizerEvent

   case object CheckModifiersToDownload extends SyncronizerEvent

   case object RequestHeight extends SyncronizerEvent

}