/*
 * Copyright 2018, Aeneas Platform.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package network

import akka.actor.{Actor, ActorRef}
import block.PowBlock
import history.AeneasHistory
import network.BlockchainDownloader.{DownloadEnded, Idle, SendBlockRequest}
import network.messagespec.{EndDownloadSpec, FullBlockChainRequestSpec, PoWBlocksMessageSpec}
import scorex.core.mainviews.NodeViewHolder
import scorex.core.mainviews.NodeViewHolder.ReceivableMessages.{GetNodeViewChanges, Subscribe}
import scorex.core.network.NetworkController.ReceivableMessages.{RegisterMessagesHandler, SendToNetwork}
import scorex.core.network.NetworkControllerSharedMessages.ReceivableMessages.DataFromPeer
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, NodeViewHolderEvent}
import scorex.core.network.message.BasicMsgDataTypes.InvData
import scorex.core.network.message.{Message, RequestModifierSpec}
import scorex.core.network.{ConnectedPeer, NetworkController, SendToPeer}
import scorex.core.settings.NetworkSettings
import scorex.core.utils.ScorexLogging
import scorex.core.{ModifierId, ModifierTypeId}

import scala.annotation.tailrec

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 06.03.18.
  */
class BlockchainDownloader(networkControllerRef : ActorRef,
                           viewHolderRef : ActorRef,
                           networkSettings: NetworkSettings) extends Actor with ScorexLogging {

   protected val requestModifierSpec = new RequestModifierSpec(networkSettings.maxInvObjects)
   protected val batchMessageSpec = new PoWBlocksMessageSpec
   protected val chainSpec = new FullBlockChainRequestSpec
   protected val endDownloadSpec = new EndDownloadSpec

   protected var historyReaderOpt: Option[AeneasHistory] = None

   override def preStart(): Unit = {
      networkControllerRef !
         RegisterMessagesHandler(Seq(chainSpec, endDownloadSpec, batchMessageSpec, requestModifierSpec), self)

      val vhEvents = Seq(NodeViewHolder.EventType.HistoryChanged)
      viewHolderRef ! Subscribe(vhEvents)
      viewHolderRef ! GetNodeViewChanges(history = true, state = false, vault = false, mempool = false)
   }

   /**
     * Handles `SendBlockRequest` entity and sends request to download batch of blocks,
     * where first block is block with `id` identificator.
     */
   def onDownloadBlocks : Receive = {
      case SendBlockRequest(typeId, id, remotePeer) =>
         requestBlockToDownload(typeId, id, remotePeer)
   }

   /**
     * Makes request to well-known `peer` to download batch of blocks,
     * where first block is block with `id` identificator.
     * @param typeId
     * @param modifierId
     * @param peer
     */
   def requestBlockToDownload(typeId: ModifierTypeId, modifierId: ModifierId, peer : ConnectedPeer): Unit = {
      val msg = Message(requestModifierSpec, Right(typeId -> Seq(modifierId)), None)
      networkControllerRef ! SendToNetwork(msg, SendToPeer(peer))
      // TODO: track delivery
   }

   /**
     * Handles download request message with concrete blocks on well-known `peer`.
     * If genesis block was downloaded, send Stop signal to downloading
     */
   def receiveRequestToDownload : Receive = {
      case DataFromPeer(spec, data : InvData@unchecked, remotePeer) =>
         if (spec.messageCode == requestModifierSpec.messageCode)
            historyReaderOpt match {
               case Some(reader) =>
                  val historyReader = reader.asInstanceOf[AeneasHistory]
                  if (historyReader.genesis() == data._2.head)
                     networkControllerRef ! SendToNetwork(Message(endDownloadSpec, Right("end"), None), SendToPeer(remotePeer))
                  else {
                     val acquiredBlocks = acquireBlocksFromBlockchain(data._2.head, Seq(), historyReader)
                     val msg = Message(batchMessageSpec, Right(acquiredBlocks), None)
                     networkControllerRef ! SendToNetwork(msg, SendToPeer(remotePeer))
                  }

               case _ =>
            }
   }

   /**
     * Receiving blocks from well-known peer and applying them to blockchain.
     * It also sends response to this peer to download MOAR blocks.
     */
   def receiveBlocksFromWellKnownPeer : Receive = {
      case DataFromPeer(spec, data : Seq[PowBlock]@unchecked, remotePeer) =>
         if (spec.messageCode == batchMessageSpec.messageCode) {
            applyBlocksToBlockchain(data)
            val lastBlock = data.reverse.head
            // TODO : check if all blocks was applied to database.
            // TODO : probably it would be better to cache last block.
            if (historyReaderOpt.get.lastBlock.get.equals(lastBlock))
               requestBlockToDownload(lastBlock.modifierTypeId, lastBlock.parentId, remotePeer)
            else requestBlockToDownload(data.head.modifierTypeId, data.head.id, remotePeer)
         }
   }

   def receiveStopSignal : Receive = {
      case DataFromPeer(spec, data : String@unchecked, remotePeer) =>
         if (spec.messageCode == endDownloadSpec.messageCode) {
            self ! Idle
            viewHolderRef ! DownloadEnded(historyReaderOpt)
         }
   }

   def handleIdleSignal : Receive = {
      case Idle =>
         viewHolderRef ! DownloadEnded
   }

   def historyChanged : Receive = {
      case ChangedHistory(reader: AeneasHistory@unchecked) if reader.isInstanceOf[AeneasHistory] =>
         //TODO isInstanceOf & typeErasure??
         historyReaderOpt = Some(reader)
   }

   /**
     * Storage blocks getter for downloader actor.
     * @param acquiringId current block
     * @param acquiredBlocks collection of already acquired blocks for sending.
     * @param historyReader
     */
   @tailrec
   final def acquireBlocksFromBlockchain(acquiringId : ModifierId,
                                         acquiredBlocks : Seq[PowBlock],
                                         historyReader: AeneasHistory) : Seq[PowBlock] = {
      if (acquiredBlocks.lengthCompare(BlockchainDownloader.maxBlockResponce) == 0)
         acquiredBlocks
      else {
         historyReader.modifierById(acquiringId) match {
            case Some(b) =>
               b match {
                  case block : PowBlock =>
                     if (historyReader.storage.isGenesis(block))
                        acquiredBlocks :+ block
                     else acquireBlocksFromBlockchain(block.parentId, acquiredBlocks :+ block, historyReader)
                  case _ =>
                     acquiredBlocks
               }
            case _ =>
               acquiredBlocks
         }
      }
   }

   /**
     * Store sequence of new blocks to current blockchain.
     * Operation of first-time launched node.
     * @param blocks new blocks from original blockchain
     */
   final def applyBlocksToBlockchain(blocks : Seq[PowBlock]) : Unit = {
      historyReaderOpt match {
         case Some(reader) =>
            var historyReader = reader.asInstanceOf[AeneasHistory]
            historyReader = applyBlocks(blocks, historyReader)
            historyReaderOpt = Option(historyReader)
         case _ =>
      }
   }

   @tailrec
   private def applyBlocks(blocks: Seq[PowBlock], historyReader : AeneasHistory) : AeneasHistory = {
      if (blocks.isEmpty)
         historyReader
      else {
         // test shows that it always gets an object.
         val newHistoryReader = historyReader.append(blocks.head).get._1
         applyBlocks(blocks.tail, newHistoryReader)
      }
   }

   override def receive: Receive = {
      historyChanged orElse
      onDownloadBlocks orElse
      receiveBlocksFromWellKnownPeer orElse
      receiveRequestToDownload orElse
      receiveStopSignal orElse 
      handleIdleSignal
   }
}

object BlockchainDownloader {

   val maxBlockResponce = 100

   sealed trait DownloaderEvent extends NodeViewHolderEvent

   /**
     * SendBlockRequest entity for next blocks batch.
     * Its happens when node was first-time launched.
     * It sends request to remote `peer` which is well-known.
     * @param modifierTypeId - size of `id` in bytes.
     * @param id of pow block
     * @param peer remote peer which
     */
   case class SendBlockRequest(modifierTypeId: ModifierTypeId, id : ModifierId, peer : ConnectedPeer) extends DownloaderEvent

   case object Idle extends DownloaderEvent

   case class DownloadEnded(hisReader : Option[AeneasHistory]) extends DownloaderEvent
}