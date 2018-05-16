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

package mining

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import block.{AeneasBlock, PowBlock}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import history.AeneasHistory
import history.storage.AeneasHistoryStorage
import scorex.core.LocallyGeneratedModifiersMessages.ReceivableMessages.LocallyGeneratedModifier
import scorex.core.MerkleHash
import scorex.core.block.Block.BlockId
import scorex.core.mainviews.NodeViewHolder.CurrentView
import scorex.core.mainviews.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.NodeViewHolderEvent
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.LeafData
import scorex.crypto.authds.merkle.MerkleTree
import scorex.crypto.hash.{Blake2b256, Digest32}
import settings.SimpleMiningSettings
import state.SimpleMininalState
import viewholder.AeneasNodeViewHolder.{AeneasSubscribe, NodeViewEvent}
import wallet.AeneasWallet

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Random

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 30.01.18.
  */
class Miner(viewHolderRef: ActorRef,
            settings: SimpleMiningSettings,
            storage : AeneasHistoryStorage) extends Actor with ScorexLogging {

   import Miner._
   private var clientInformatorRef : Option[ActorRef] = None
   private var cancellableOpt: Option[Cancellable] = None
   private val mining = new AtomicBoolean(false)
   private implicit val currentViewTimer: FiniteDuration = 5.millisecond
   private implicit val timeoutView = new Timeout(currentViewTimer)
   private implicit val cryptographicHash = Blake2b256

   // Should equals to processor's ticks for this thread per 60 seconds.
   // TODO: rework for each specific user machine processor frequency.
   private val maxCycles = 2000000

   // Should equals to processor's ticks for this thread per 60 seconds.
   private val minHashLiterals = 2
   // Best fitness block if can't find any better.
   var bestFitnessBlock : PowBlock = _

   private var currentMemPool : SimpleBoxTransactionMemPool = _
   private var currentUnconfirmed : Seq[SimpleBoxTransaction] = Seq()


   override def preStart(): Unit = {
      viewHolderRef ! AeneasSubscribe(Seq(NodeViewEvent.StartMining, NodeViewEvent.StopMining))
      viewHolderRef ! MinerAlive
   }

   @tailrec
   private def checkFitness(hash : String, hits : Int) : Int = {
      if (hash.head == 'a')
         checkFitness(hash.tail, hits + 1)
      else hits
   }

   /**
     * Update current mempool from nodeView.
     * It happens where mining process is active.
     * @param viewHolderRef
     * @return
     */
   private def updateMempool(viewHolderRef : ActorRef) : SimpleBoxTransactionMemPool = {
      val currentViewAwait = ask(viewHolderRef, GetDataFromCurrentView(applyMempool)).mapTo[SimpleBoxTransactionMemPool]
      Await.result(currentViewAwait, currentViewTimer)
   }

   @tailrec
   private def mineBlock(firstBlockTry : PowBlock, activeMempool: SimpleBoxTransactionMemPool, tryCount : Int) : PowBlock = {
      // Check goodness for first block trying.
      if (checkFitness(firstBlockTry.encodedId, 0) > minHashLiterals && tryCount == 0)
         return firstBlockTry

      currentMemPool = activeMempool

      if (tryCount % 100000 == 0) {
         log.debug(s"Iteration #$tryCount")
         currentMemPool = updateMempool(viewHolderRef)
      }
      currentUnconfirmed = currentMemPool.getUnconfirmed()
      var hash : Digest32 = Digest32 @@ Array.fill(32) (1 : Byte)

      if (currentUnconfirmed.nonEmpty) {
         val tree: MerkleTree[MerkleHash] = MerkleTree.apply(LeafData @@ currentUnconfirmed.map(tx => tx.id))
         log.debug(s"Root hash of Merkle tree : ${tree.rootHash}")
         hash = tree.rootHash
      }

      val block = PowBlock(
         firstBlockTry.parentId,
         System.currentTimeMillis(),
         Math.abs(Random.nextLong()),
         hash,
         firstBlockTry.generatorProposition,
         currentUnconfirmed
      )

      val currentFitness = checkFitness(block.encodedId, 0)
      if (currentFitness > minHashLiterals - 1)
         bestFitnessBlock = block

      if (checkFitness(block.encodedId, 0) > minHashLiterals) {
         block
      }
      else if (tryCount == maxCycles) {
         if (bestFitnessBlock == null)
            block
         else bestFitnessBlock
      }
      else mineBlock(block, activeMempool, tryCount + 1)
   }

   /**
     * It fills mining mempool with specific transactions.
     * They are described here : @see
     * @see https://github.com/AeneasPlatform/Aeneas/issues/49
     * @param memPool
     * @return
     */
   // TODO: Implement it.
   private def fillMempool(memPool: SimpleBoxTransactionMemPool) = ???

   def miningProcess(parentId: BlockId,
                     difficulty: BigInt,
                     settings: SimpleMiningSettings,
                     proposition: PublicKey25519Proposition,
                     blockGenerationDelay: FiniteDuration,
                   ): Option[PowBlock] = {
      val nonce = Math.abs(Random.nextLong())
      val ts = System.currentTimeMillis()

      val currentViewAwait = ask(viewHolderRef, GetDataFromCurrentView(applyMempool)).mapTo[SimpleBoxTransactionMemPool]
      currentMemPool = Await.result(currentViewAwait, currentViewTimer)

      currentUnconfirmed = currentMemPool.getUnconfirmed()
      log.debug(s"Current unconfirmed transaction pool size : ${currentUnconfirmed.size}")

      var hash : Digest32 = Digest32 @@ Array.fill(32) (1 : Byte)

      if (currentUnconfirmed.nonEmpty) {
         val tree: MerkleTree[MerkleHash] = MerkleTree.apply(LeafData @@ currentUnconfirmed.map(tx => tx.id))
         log.debug(s"Root hash of Merkle tree : ${tree.rootHash}")
         hash = tree.rootHash
      }

      val b : PowBlock = mineBlock(PowBlock(parentId, ts, nonce, hash, proposition, currentUnconfirmed), currentMemPool, 0)

      val foundBlock =
         if (b.correctWork(difficulty, settings)) {
            Some(b)
         } else {
            None
         }
      foundBlock
   }

   //noinspection ScalaStyle
   override def receive: Receive = {
      case StartMining =>
         mining.compareAndSet(false, true)
         log.debug(s"Miner : Mining was ${if (!mining.get()) "not"} enabled")
         if (settings.blockGenDelay >= 1.minute) {
            log.info("Mining is disabled for blockGenerationDelay >= 1 minute")
         } else {
            if (mining.get()) {
               log.debug("Mining should begins")
               self ! MineBlock
            }
            else StartMining
         }

      case MineBlock =>
         if (mining.get()) {
            log.info("Mining of previous PoW block stopped")
            cancellableOpt.forall(_.cancel())
            log.debug(s"MineBlock : Cancellable count : ${cancellableOpt.size}")
            // TODO: See here!!
            context.system.scheduler.scheduleOnce(250.millis) {
               log.debug(s"MineBlock : is in scheduler state")
               if (cancellableOpt.forall(_.status.isCancelled) || cancellableOpt.isEmpty) {
                  log.debug(s"MineBlock : it sends required data")
                  val dataFromCurrentView = getRequiredData
                  viewHolderRef ! dataFromCurrentView
               }
               else self ! StartMining
            }
         }

      case Some (pmi: MiningInfo) =>
         if (!cancellableOpt.forall(_.status.isCancelled)) {
            log.warn("Trying to run miner when the old one is still running")
         } else {
            val difficulty = pmi.powDifficulty
            val bestPowBlock = pmi.bestPowBlock

            val parentId = bestPowBlock.id //new step
            log.info(s"Starting new block mining for ${bestPowBlock.encodedId}")

            val pubkey = pmi.pubkey

            val p = Promise[Option[PowBlock]]()
            cancellableOpt = Some(Cancellable.run() { status =>
               Future {
                  var foundBlock: Option[PowBlock] = None
                  while (status.nonCancelled && foundBlock.isEmpty) {
                     foundBlock = miningProcess(parentId, difficulty, settings, pubkey, settings.blockGenDelay)
                     log.info(s"New block status : ${if (foundBlock.isDefined) "mined" else "in process"}")
                  }
                  p.success(foundBlock)
                  log.info(s"New block : ${foundBlock.get.encodedId}")
               }
            })
            p.future.onComplete { toBlock =>
               toBlock.getOrElse(None).foreach { block =>
                  log.info(s"Locally generated PoW block: $block with difficulty $difficulty")
                  self ! block
               }
            }
         }

      case b: PowBlock =>
         cancellableOpt.foreach(_.cancel())
         viewHolderRef ! LocallyGeneratedModifier[AeneasBlock](b)
         clientInformatorRef.foreach(_ ! b)
      case StopMining =>
         log.debug(s"Pre-stop miner state : ${mining.toString}")
         mining.set(false)
         log.debug(s"Miner : Mining was disabled")

      case UiInformatorSubscribe =>
         if (clientInformatorRef.isEmpty)
            clientInformatorRef = Option(sender())
      case a: Any =>
         log.warn(s"Strange input: $a")
   }
}

object Miner extends App with ScorexLogging {

   def applyMempool(currentView: CurrentView[AeneasHistory,
      SimpleMininalState, AeneasWallet, SimpleBoxTransactionMemPool]) : SimpleBoxTransactionMemPool = {
      currentView.pool
   }

   sealed trait MinerEvent extends NodeViewHolderEvent

   case object MinerAlive extends NodeViewHolderEvent

   case object StartMining extends MinerEvent

   case object UiInformatorSubscribe

   case object StopMining extends MinerEvent

   case object MineBlock extends MinerEvent

   case class MiningInfo(powDifficulty: BigInt, bestPowBlock: PowBlock, pubkey: PublicKey25519Proposition) extends MinerEvent

   def getRequiredData: GetDataFromCurrentView[AeneasHistory,
     SimpleMininalState,
     Option[AeneasWallet],
     SimpleBoxTransactionMemPool,
     Option[MiningInfo]] = {
      val f: CurrentView[AeneasHistory, SimpleMininalState, Option[AeneasWallet], SimpleBoxTransactionMemPool] => Option[MiningInfo] = {
         view: CurrentView[AeneasHistory, SimpleMininalState, Option[AeneasWallet], SimpleBoxTransactionMemPool] =>
           view.vault match {
              case Some(vault) =>
                 log.debug(s"Miner.requiredData : work begins")

                 val bestBlock = view.history.storage.bestBlock
                 val difficulty = view.history.storage.getPoWDifficulty(None)

                 val pubkey = if (vault.publicKeys.nonEmpty) {
                    vault.publicKeys.head
                 } else {
                    view.vault.get.generateNewSecret().publicKeys.head
                 }
                 log.info(s"miningInfo: ${MiningInfo(difficulty, bestBlock, pubkey)}")
                 Some (MiningInfo(difficulty, bestBlock, pubkey))
              case _ => None
           }
      }
      GetDataFromCurrentView[AeneasHistory,
        SimpleMininalState,
        Option[AeneasWallet],
        SimpleBoxTransactionMemPool,
        Option[MiningInfo]](f)
   }

}