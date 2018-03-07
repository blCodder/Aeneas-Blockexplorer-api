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
import block.PowBlock
import history.AeneasHistory
import history.sync.AeneasSynchronizer.{PreStartDownloadRequest, SendMessageSpec, SynchronizerAlive}
import network.BlockchainDownloader.SendBlockRequest
import network.messagespec.{FullBlockChainRequestSpec, PoWBlockMessageSpec}
import scorex.core.NodeViewHolder.{GetNodeViewChanges, NodeViewHolderEvent, Subscribe}
import scorex.core.block.Block.BlockId
import scorex.core.consensus.{HistoryReader, SyncInfo}
import scorex.core.network.NetworkController.{DataFromPeer, SendToNetwork}
import scorex.core.network._
import scorex.core.network.message.{Message, MessageSpec, ModifiersSpec, SyncInfoMessageSpec}
import scorex.core.network.peer.PeerManager
import scorex.core.settings.NetworkSettings
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.{MempoolReader, Transaction}
import scorex.core.utils.NetworkTimeProvider
import scorex.core.{ModifierTypeId, NodeViewHolder, PersistentNodeViewModifier}
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
                         timeProvider: NetworkTimeProvider,
                         downloader : ActorRef) extends
  NodeViewSynchronizer [P, TX, SI, SIS, PMOD, HR, MR] (networkControllerRef,
    viewHolderRef, localInterfaceRef, syncInfoSpec, networkSettings, timeProvider) {

   val powBlockMessageSpec = new PoWBlockMessageSpec
   val chainSpec = new FullBlockChainRequestSpec

   override def preStart(): Unit = {
      //register as a handler for synchronization-specific types of messages
      val messageSpecs = Seq(invSpec, requestModifierSpec, ModifiersSpec, syncInfoSpec, powBlockMessageSpec, chainSpec)
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

      downloader ! SendMessageSpec(requestModifierSpec)

      viewHolderRef ! SynchronizerAlive
   }

   /**
     * React on `PreStartDownloadRequest` message and request blockchain download.
     * It is start of so-called "blockchain handshake"
     * It happens when current node has first-time launch.
     */
   def onDownloadRequest: Receive = {
      case PreStartDownloadRequest =>
         val msg = Message(chainSpec, Right("blockchain"), None)
         networkControllerRef ! SendToNetwork(msg, Broadcast)
   }

   /**
     * It handles `PreStartDownloadRequest` was sent from peer which begins its work.
     * Also it can be imagined as "blockchain handshake" procedure!
     * It happens if current node has well-known status.
     * // TODO: Check of well-known status?
     */
   def onDownloadRequestReceived : Receive = {
      case DataFromPeer(spec, request : String@unchecked, remotePeer) =>
         if (spec.messageCode == chainSpec.messageCode && request.equals("blockchain")) {
            historyReaderOpt match {
               // TODO: be sure that we can cast traited object to AeneasHistory.
               case Some(history) =>
                  val historyReader = history.asInstanceOf[AeneasHistory]
                  val lastBlock = historyReader.bestBlock()
                  val msg = Message (powBlockMessageSpec, Right(lastBlock), None)
                  networkControllerRef ! SendToNetwork(msg, SendToPeer(remotePeer))

               case None =>
            }
         }
      case _ =>
   }

   /**
     * It sends request to send request from `downloader` actor to ask well-known peer
     * to send batch of blocks from correct and original blockchain to this node.
     * @param modifierTypeId
     * @param parentId
     * @param remotePeer
     */
   def sendRequestToDownloader(modifierTypeId: ModifierTypeId, parentId: BlockId, remotePeer: ConnectedPeer): Unit = {
      downloader ! SendBlockRequest(modifierTypeId, parentId, remotePeer)
   }

   /** It handles `PreStartDownloadRequest` was sent from peer which begins its work. */
   def onDownloadReceive : Receive = {
      case DataFromPeer(spec, block : PowBlock@unchecked, remotePeer) =>
         if (spec.messageCode == powBlockMessageSpec.messageCode) {
            historyReaderOpt match {
               // TODO: be sure that we can cast traited object to AeneasHistory.
               case Some(history) =>
                  val historyReader = history.asInstanceOf[AeneasHistory]
                  historyReader.append(block)
                  sendRequestToDownloader(block.modifierTypeId, block.parentId, remotePeer)
               case None =>
            }
         }
      case _ =>
   }


   override protected def viewHolderEvents: Receive =
         onDownloadRequest orElse
         onDownloadReceive orElse
         super.viewHolderEvents

   override def receive: Receive =
      onDownloadRequest orElse
         onDownloadReceive orElse
         onDownloadRequestReceived orElse
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

   /**
     * Signal is sent when synchronizer actor are alive to `AeneasViewHolder` actor
     * @see AeneasViewHolder.onRestoreMessage
     */
   case object SynchronizerAlive extends NodeViewHolderEvent

   /**
     * Signal will send when first node's launch happens.
     * It requests whole blockchain download from well-known peers.
     */
   case object PreStartDownloadRequest extends SyncronizerEvent

   /**
     * Signal with message spec is sent to downloader actor.
     * @param spec concrete message spec.
     */
   case class SendMessageSpec(spec: MessageSpec[_]) extends NodeViewHolderEvent
}