package bench_preparings

import akka.util.Timeout
import block.PowBlock
import commons.{SimpleBoxTransaction, SimpleBoxTransactionGenerator}
import scorex.core.block.Block.BlockId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.utils.ScorexLogging
import scorex.core.{MerkleHash, TxId}
import scorex.crypto.authds.LeafData
import scorex.crypto.authds.merkle.MerkleTree
import scorex.crypto.hash.{Blake2b256, Digest32}
import settings.SimpleMiningSettings

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, Future}
import scala.util.Random

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 18.04.18.
  *
  * Fake Miner non-actor class
  */
class NonActorMiner(settings: SimpleMiningSettings,
                    generator: SimpleBoxTransactionGenerator) extends ScorexLogging {

   private var cancellableOpt: Future[PowBlock] = _
   private implicit val currentViewTimer: FiniteDuration = 1.second
   // maximum available time offset for block : 60 seconds.
   private val minerTimer: FiniteDuration = 70.second
   private implicit val timeoutView = new Timeout(currentViewTimer)
   private implicit val cryptographicHash = Blake2b256

   // Should equals to processor's ticks for this thread per 60 seconds.
   private val maxCycles = 200000

   // Should equals to processor's ticks for this thread per 60 seconds.
   private val minHashLiterals = 2
   // Best fitness block if can't find any better.
   var bestFitnessBlock : PowBlock = _

   private val blockStorage = new PowBlock(
      settings.GenesisParentId,
      System.currentTimeMillis(),
      1 << 3,
      Digest32 @@ Array.emptyByteArray,
      PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)._2,
      Seq()
   )

   /**
     * Method defines if block can participate in blockchain competitive process.
     * It counts 'a' letters at the beginning of the hash recursively.
     * @param hash : current block hash.
     * @param hits : count of letters 'a' at the beginning of the hash in the previous step.
     * @return final count of letters 'a' at the beginning of the hash.
     */
//   @tailrec
   private def checkFitness(hash : String, hits : Int) : Int = {
      if (hash.head == 'a')
         checkFitness(hash.tail, hits + 1)
      else hits
   }

   @tailrec
   private def mineBlock(firstBlockTry : PowBlock, tryCount : Int) : PowBlock = {
      val currentUnconfirmed : Seq[SimpleBoxTransaction] = generator.txPool

      val tree : MerkleTree[MerkleHash] = MerkleTree.apply(LeafData @@ currentUnconfirmed.map(tx => tx.id))

      val block = PowBlock(
         firstBlockTry.parentId,
         System.currentTimeMillis(),
         firstBlockTry.nonce + 1,
         tree.rootHash,
         firstBlockTry.generatorProposition,
         currentUnconfirmed
      )

      val currentFitness = checkFitness(block.encodedId, 0)
      if (currentFitness > minHashLiterals - 1)
         bestFitnessBlock = block

      if (checkFitness(block.encodedId, 0) > 2) {
         generator.txPool = ArrayBuffer.empty
         block
      }
      else if (tryCount == maxCycles) {
         generator.txPool = ArrayBuffer.empty
         if (bestFitnessBlock == null)
            block
         else bestFitnessBlock
      }
      else mineBlock(block, tryCount + 1)
   }

   private def powIteration(parentId: BlockId,
                    difficulty: BigInt,
                    settings: SimpleMiningSettings,
                    proposition: PublicKey25519Proposition,
                    blockGenerationDelay: FiniteDuration,
                   ): Option[PowBlock] = {
      val rand = Random.nextLong()
      val nonce = if (rand > 0) rand else rand * -1
      val ts = System.currentTimeMillis()
      // Sleep for emulating of txPool update from actor.
      Thread.sleep(10)

      // id is already hashed transaction.
      val currentUnconfirmed : Seq[SimpleBoxTransaction] = generator.txPool

      val tree : MerkleTree[MerkleHash] = MerkleTree.apply(LeafData @@ currentUnconfirmed.map(tx => tx.id))
      val b = mineBlock(PowBlock(parentId, ts, nonce, tree.rootHash, proposition, Seq()), 0)

      val foundBlock = if (b.correctWork(difficulty, settings))
         Some(b) else None

      foundBlock
   }

   //noinspection ScalaStyle
   def beginMining(difficulty : BigInt) : Long = {
      generator.generatingProcess()
      // Emulation on actor sending information and serializing/deserializing.
      Thread.sleep(30)

      val bestPowBlock = blockStorage
      val parentId = blockStorage.id  //new step
      log.info(s"Starting new block mining for ${bestPowBlock.encodedId}")

      val pubkey = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)._2
      cancellableOpt = Future {
         var foundBlock: Option[PowBlock] = None
         while (foundBlock.isEmpty)
            foundBlock = powIteration(parentId, difficulty, settings, pubkey, settings.blockGenDelay)

         foundBlock.get
      }
      val block = Await.result(cancellableOpt, minerTimer)
      log.info(s"New block : ${block.encodedId}")
      block.nonce
   }

}
