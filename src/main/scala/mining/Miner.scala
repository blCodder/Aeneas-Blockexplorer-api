package mining

import akka.actor.{Actor, ActorRef}
import block.{AeneasBlock, PowBlock, PowBlockCompanion, PowBlockHeader}
import commons.SimpleBoxTransactionMemPool
import history.SimpleHistory
import history.storage.SimpleHistoryStorage
import scorex.core.LocalInterface.LocallyGeneratedModifier
import scorex.core.NodeViewHolder.{CurrentView, GetDataFromCurrentView}
import scorex.core.block.Block.BlockId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import scorex.crypto.hash.Blake2b256
import settings.SimpleMiningSettings
import state.SimpleMininalState
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
            storage : SimpleHistoryStorage) extends Actor with ScorexLogging {

   import Miner._

   private var cancellableOpt: Option[Cancellable] = None
   private var mining = false
   private val requriredData = Miner.getRequiredData


   override def preStart(): Unit = {
      //todo: check for a last block (for what?)
      if (settings.offlineGen) {
         context.system.scheduler.scheduleOnce(1.second)(self ! StartMining)
      }
   }

   //noinspection ScalaStyle
   override def receive: Receive = {
      case StartMining =>
         if (settings.blockGenDelay >= 1.minute) {
            log.info("Mining is disabled for blockGenerationDelay >= 1 minute")
         } else {
            mining = true
            self ! MineBlock
         }

      case MineBlock =>
         if (mining) {
            log.info("Mining of previous PoW block stopped")
            cancellableOpt.forall(_.cancel())

            context.system.scheduler.scheduleOnce(50.millis) {
               if (cancellableOpt.forall(_.status.isCancelled)) viewHolderRef ! getRequiredData
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

      case StopMining =>
         mining = false

      case a: Any =>
         log.warn(s"Strange input: $a")
   }
}

object Miner extends App with ScorexLogging {

   case object StartMining

   case object StopMining

   case object MineBlock

   case class MiningInfo(powDifficulty: BigInt, bestPowBlock: PowBlock, pubkey: PublicKey25519Proposition)

   def powIteration(parentId: BlockId,
                    brothers: Seq[PowBlockHeader],
                    difficulty: BigInt,
                    settings: SimpleMiningSettings,
                    proposition: PublicKey25519Proposition,
                    blockGenerationDelay: FiniteDuration,
                    storage : SimpleHistoryStorage
                   ): Option[PowBlock] = {
      val rand = Random.nextLong()
      val nonce = if (rand > 0) rand else rand * -1

      val ts = System.currentTimeMillis()

      val bHash = if (brothers.isEmpty)
         Array.fill(32)(0: Byte)
      else Blake2b256(PowBlockCompanion.brotherBytes(brothers))

      val b = PowBlock(parentId, ts, nonce, brothers.size, bHash, proposition, brothers)

      val foundBlock =
         if (b.correctWork(difficulty, settings)) {
            Some(b)
         } else {
            None
         }
      Thread.sleep(blockGenerationDelay.toMillis)
      foundBlock
   }

   def getRequiredData: GetDataFromCurrentView[SimpleHistory,
     SimpleMininalState,
     AeneasWallet,
     SimpleBoxTransactionMemPool,
     MiningInfo] = {
      val f: CurrentView[SimpleHistory, SimpleMininalState, AeneasWallet, SimpleBoxTransactionMemPool] => MiningInfo = {
         view: CurrentView[SimpleHistory, SimpleMininalState, AeneasWallet, SimpleBoxTransactionMemPool] =>

            val bestBlock = view.history.storage.bestBlock
            val difficulty = view.history.storage.getPoWDifficulty(None)
            val pubkey = if (view.vault.publicKeys.nonEmpty) {
               view.vault.publicKeys.head
            } else {
               view.vault.generateNewSecret().publicKeys.head
            }
            log.info(MiningInfo(difficulty, bestBlock, pubkey).toString)
            MiningInfo(difficulty, bestBlock, pubkey)
      }
      GetDataFromCurrentView[SimpleHistory,
        SimpleMininalState,
        AeneasWallet,
        SimpleBoxTransactionMemPool,
        MiningInfo](f)
   }

}