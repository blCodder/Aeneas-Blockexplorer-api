package mining

import akka.actor.Actor
import block.PowBlock
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool, Value}
import mining.PreStartMiningSettingsSetup.LaunchCPULoader
import scorex.core.MerkleHash
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.LeafData
import scorex.crypto.authds.merkle.MerkleTree
import scorex.crypto.hash.{Blake2b256, Digest32}
import settings.SimpleMiningSettings

import scala.annotation.tailrec
import scala.util.Random

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 18.05.18.
  */
case class PreStartMiningSettingsSetup(settings : SimpleMiningSettings) extends Actor with ScorexLogging {

   val privateKey = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)._1
   val publicKey = PrivateKey25519Companion.generateKeys("Blockgenesis".getBytes)._2
   private implicit val cryptographicHash = Blake2b256

   val typicalTransaction = SimpleBoxTransaction.apply(
      IndexedSeq(privateKey -> Value @@ 40.toLong),
      IndexedSeq(publicKey -> Value @@ 40.toLong),
      0,
      System.currentTimeMillis()
   )

   val txBox = Seq(typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction,
      typicalTransaction
   )

   /**
     * Function which is similar to `Miner.mineBlock`.
     * It counts number of mining tryings for averaging count
     * of wasted time for mining on all nodes.
     * @param tryCount
     * @return
     */
   @tailrec
   private def loadCPU (tryCount : Int) : PowBlock = {
      var hash : Digest32 = Digest32 @@ Array.fill(32) (1 : Byte)
      val tree: MerkleTree[MerkleHash] = MerkleTree.apply(LeafData @@ txBox.map(tx => tx.id))
      hash = tree.rootHash

      if (tryCount % 50000 == 0)
         log.debug(s"Iteration : $tryCount")

      val block = PowBlock(
         settings.GenesisParentId,
         System.currentTimeMillis(),
         Math.abs(Random.nextLong()),
         hash,
         publicKey,
         txBox
      )
      if (tryCount == PreStartMiningSettingsSetup.testHashRate) block
      else loadCPU(tryCount + 1)
   }

   private def loadResolver() : Long = {
      import PreStartMiningSettingsSetup._
      val timeBefore = System.currentTimeMillis()
      val b = loadCPU(0)
      log.debug(s"Tested blockID is : ${b.encodedId}")
      val time = (System.currentTimeMillis() - timeBefore) / 1000
      println(s"Time : $time")

      val hashRate = testHashRate * regressionConstant / time
      settings.setLoad(hashRate)
      sender() ! true

      time
   }

   override def receive: Receive = {
      case LaunchCPULoader =>
         loadResolver()
   }
}

object PreStartMiningSettingsSetup {
   // it is time of one planned mining iteration +
   // + account (record) of code regression in test case and benchmarking accuracy.
   val regressionConstant = 120

   val testHashRate = 500000
   case object LaunchCPULoader
}

// y(sec) * x = 3*10^6 => x = 3*10^6 / y
