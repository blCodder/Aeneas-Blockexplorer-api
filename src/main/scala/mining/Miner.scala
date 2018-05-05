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
import block.{AeneasBlock, PowBlock, PowBlockCompanion, PowBlockHeader}
import commons.SimpleBoxTransactionMemPool
import history.AeneasHistory
import history.storage.AeneasHistoryStorage
import scorex.core.LocallyGeneratedModifiersMessages.ReceivableMessages.LocallyGeneratedModifier
import scorex.core.ModifierId
import scorex.core.block.Block.BlockId
import scorex.core.mainviews.NodeViewHolder.CurrentView
import scorex.core.mainviews.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.NodeViewHolderEvent
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import scorex.crypto.hash.Blake2b256
import settings.SimpleMiningSettings
import state.SimpleMininalState
import viewholder.AeneasNodeViewHolder.{AeneasSubscribe, NodeViewEvent}
import wallet.AeneasWallet

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


   override def preStart(): Unit = {
      viewHolderRef ! AeneasSubscribe(Seq(NodeViewEvent.StartMining, NodeViewEvent.StopMining))
      viewHolderRef ! MinerAlive
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

      case pmi: MiningInfo =>
         if (!cancellableOpt.forall(_.status.isCancelled)) {
            log.warn("Trying to run miner when the old one is still running")
         } else {
            val difficulty = pmi.powDifficulty
            val bestPowBlock = pmi.bestPowBlock

            val (parentId, brothers) = (bestPowBlock.id, Seq()) //new step
            log.info(s"Starting new block mining for ${bestPowBlock.encodedId}")

            val pubkey = pmi.pubkey

            val p = Promise[Option[PowBlock]]()
            cancellableOpt = Some(Cancellable.run() { status =>
               Future {
                  var foundBlock: Option[PowBlock] = None
                  var attemps = 0

                  while (status.nonCancelled && foundBlock.isEmpty) {
                     foundBlock = powIteration(parentId, brothers, difficulty, settings, pubkey, settings.blockGenDelay, storage)
                     log.info(s"New block status : ${if (foundBlock.isDefined) "mined" else "in process"} at $attemps iteration")
                     attemps = attemps + 1
                     if (attemps % 100 == 99) {
                        log.debug(s"100 hashes tried, difficulty is $difficulty")
                     }
                  }
                  p.success(foundBlock)
                  log.info(s"New block : ${foundBlock.get.encodedId}")
               }
            })
            p.future.onComplete { toBlock =>
               log.info(s"New block precomplete time")
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

   sealed trait MinerEvent extends NodeViewHolderEvent

   case object MinerAlive extends NodeViewHolderEvent

   case object StartMining extends MinerEvent

   case object UiInformatorSubscribe

   case object StopMining extends MinerEvent

   case object MineBlock extends MinerEvent

   case class MiningInfo(powDifficulty: BigInt, bestPowBlock: PowBlock, pubkey: PublicKey25519Proposition) extends MinerEvent

   def powIteration(parentId: BlockId,
                    brothers: Seq[PowBlockHeader],
                    difficulty: BigInt,
                    settings: SimpleMiningSettings,
                    proposition: PublicKey25519Proposition,
                    blockGenerationDelay: FiniteDuration,
                    storage : AeneasHistoryStorage
                   ): Option[PowBlock] = {
      val rand = Random.nextLong()
      val nonce = if (rand > 0) rand else rand * -1

      val ts = System.currentTimeMillis()

      val b = PowBlock(parentId, ts, nonce, ModifierId @@ Array.emptyByteArray, proposition, Seq())

      val foundBlock =
         if (b.correctWork(difficulty, settings)) {
            Some(b)
         } else {
            None
         }
      Thread.sleep(blockGenerationDelay.toMillis)
      foundBlock
   }

   def getRequiredData: GetDataFromCurrentView[AeneasHistory,
     SimpleMininalState,
     AeneasWallet,
     SimpleBoxTransactionMemPool,
     MiningInfo] = {
      val f: CurrentView[AeneasHistory, SimpleMininalState, AeneasWallet, SimpleBoxTransactionMemPool] => MiningInfo = {
         view: CurrentView[AeneasHistory, SimpleMininalState, AeneasWallet, SimpleBoxTransactionMemPool] =>
            log.debug(s"Miner.requiredData : work begins")

            val bestBlock = view.history.storage.bestBlock
            val difficulty = view.history.storage.getPoWDifficulty(None)
            val pubkey = if (view.vault.publicKeys.nonEmpty) {
               view.vault.publicKeys.head
            } else {
               view.vault.generateNewSecret().publicKeys.head
            }
            log.info(s"miningInfo: ${MiningInfo(difficulty, bestBlock, pubkey)}")
            MiningInfo(difficulty, bestBlock, pubkey)
      }
      GetDataFromCurrentView[AeneasHistory,
        SimpleMininalState,
        AeneasWallet,
        SimpleBoxTransactionMemPool,
        MiningInfo](f)
   }

}