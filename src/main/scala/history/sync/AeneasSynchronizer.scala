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
import akka.pattern.ask
import akka.util.Timeout
import block.PowBlock
import history.AeneasHistory
import history.sync.AeneasSynchronizer.{PreStartDownloadRequest, RequestPeerManager, SendMessageSpec, SynchronizerAlive}
import network.BlockchainDownloader.SendBlockRequest
import network.messagespec.{FullBlockChainRequestSpec, PoWBlockMessageSpec}
import scorex.core.ModifierTypeId
import scorex.core.block.Block.BlockId
import scorex.core.consensus.{HistoryReader, SyncInfo}
import scorex.core.mainviews.NodeViewHolder.ReceivableMessages.{GetNodeViewChanges, Subscribe}
import scorex.core.mainviews.{NodeViewHolder, PersistentNodeViewModifier}
import scorex.core.network.NetworkController.ReceivableMessages.{RegisterMessagesHandler, SendToNetwork, SubscribePeerManagerEvent}
import scorex.core.network.NetworkControllerSharedMessages.ReceivableMessages.DataFromPeer
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, NodeViewHolderEvent}
import scorex.core.network._
import scorex.core.network.message.{Message, MessageSpec, ModifiersSpec, SyncInfoMessageSpec}
import scorex.core.network.peer.PeerManager.ReceivableMessages.GetConnectedPeers
import scorex.core.network.peer.PeerManager.{DisconnectedEvent, HandshakedEvent}
import scorex.core.settings.NetworkSettings
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.{MempoolReader, Transaction}
import scorex.core.utils.NetworkTimeProvider
import viewholder.AeneasNodeViewHolder
import viewholder.AeneasNodeViewHolder.AeneasSubscribe

import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, _}

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
   var peerManager : ActorRef = ActorRef.noSender
   implicit lazy val timeout = new Timeout(500.millisecond)

   override def preStart(): Unit = {
      //register as a handler for synchronization-specific types of messages
      val messageSpecs = Seq(invSpec, powBlockMessageSpec, requestModifierSpec, ModifiersSpec, syncInfoSpec, chainSpec)
      networkControllerRef ! RegisterMessagesHandler(messageSpecs, self)

      val pmEvents  = Seq(
         HandshakedEvent,
         DisconnectedEvent
      )
      networkControllerRef ! SubscribePeerManagerEvent(pmEvents)

      val vhEvents = Seq(
         // superclass events
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
         AeneasNodeViewHolder.NodeViewEvent.PreStartDownloadRequest,
         AeneasNodeViewHolder.NodeViewEvent.UpdateHistory
      )

      viewHolderRef ! AeneasSubscribe(aeneasEvents)

      // We enable state change for downloading blocks process.
      viewHolderRef ! GetNodeViewChanges(history = true, state = true, vault = false, mempool = true)

      downloader ! SendMessageSpec(requestModifierSpec)

      val peerManagerRequest = ask(networkControllerRef, RequestPeerManager).mapTo[ActorRef]
      peerManager = Await.result(peerManagerRequest, 10.second)

      viewHolderRef ! SynchronizerAlive

      log.debug(s"Aeneas Events was registered : ${aeneasEvents.length}")
   }

   /**
     * React on `PreStartDownloadRequest` message and request blockchain download.
     * It is start of so-called "blockchain handshake"
     * It happens when current node has first-time launch.
     */
   def onDownloadRequest: Receive = {
      case PreStartDownloadRequest =>
         val msg = Message(chainSpec, Right(), None)
         log.debug(s"Synchronizer : PreStartDownloadRequest was coming with message : ${msg.data.get}.")

         Thread.sleep(2500)

         val peersHandshakeFuture = ask(peerManager, GetConnectedPeers).mapTo[Seq[ConnectedPeer]]
         val peers = Await.result(peersHandshakeFuture, timeout.duration)

         networkControllerRef ! SendToNetwork(msg, SendToPeers(peers))
   }

   /**
     * * Well-known peer action *
     * It handles `PreStartDownloadRequest` was sent from peer which begins its work.
     * Also it can be imagined as "blockchain handshake" procedure!
     * It happens if current node has well-known status.
     * // TODO: Check of well-known status?
     */
   def onDownloadRequestReceived : Receive = {
      case DataFromPeer(spec, request : Unit@unchecked, remotePeer) =>
         if (spec.messageCode == chainSpec.messageCode) {
            log.debug(s"AeneasSynchronizer : Download blockchain request received from ${remotePeer.socketAddress.toString}")

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
   }

   /**
     * * New peer action *
     * It sends request to send request from `downloader` actor to ask well-known peer
     * to send batch of blocks from correct and original blockchain to this node.
     * @param modifierTypeId
     * @param parentId
     * @param remotePeer
     */
   def sendRequestToDownloader(modifierTypeId: ModifierTypeId, parentId: BlockId, remotePeer: ConnectedPeer): Unit = {
      downloader ! SendBlockRequest(modifierTypeId, parentId, remotePeer)
   }

   /**
     * * New peer action *
     *  It receives last block from connected well-known peer and apply it to the history.
     */
   def onDownloadReceive : Receive = {
      case DataFromPeer(spec, block : PowBlock@unchecked, remotePeer) =>
         block match {
            case b : PowBlock =>
               if (spec.messageCode == powBlockMessageSpec.messageCode) {
                  log.debug(s"Block was received : ${b.encodedId}")
                  historyReaderOpt match {
                     case Some (history) =>
                        val historyReader = history.asInstanceOf[AeneasHistory]
                        historyReader.append(b)
                        log.debug(s"History was read, it`s height : ${historyReader.height}")
                        sendRequestToDownloader(b.modifierTypeId, b.parentId, remotePeer)
                     case None =>
                  }
               }
               else log.debug(s"Incorrect spec : ${spec.messageCode}, name : ${spec.messageName}")
            case _ => log.debug(s"Incorrect type : ${block.getClass.toString}")
         }
   }

   /** It handles `СhangedHistory` was sent after mining start. */
   def onChangedHistory : Receive = {
      case ChangedHistory(reader) =>
         reader match {
            case history: HR =>
               log.debug(s"Synchronizer : successfully updated history reader.")
               historyReaderOpt = Some(history)
            case _ => throw new ClassCastException(s"Can't cast ${reader.getClass.toString} to ${AeneasHistory.toString} ")
         }
   }

   override def receive: Receive =
      onDownloadRequest orElse
         onDownloadReceive orElse
         onChangedHistory orElse
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

   /** Signal which request peer manager actor reference. */
   case object RequestPeerManager extends SyncronizerEvent
}