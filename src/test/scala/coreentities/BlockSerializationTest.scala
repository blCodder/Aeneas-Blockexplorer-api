package coreentities

import block.{PowBlock, PowBlockCompanion, PowBlockHeader}
import commons.SimpleBoxTransactionGenerator
import org.scalatest.{FunSuite, Matchers}
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.hash.Digest32
import settings.AeneasSettings
import wallet.AeneasWallet

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 01.05.18.
  */
class BlockSerializationTest extends FunSuite with Matchers {
   test("Transaction extraction test") {
      val generator = new SimpleBoxTransactionGenerator(AeneasWallet.readOrGenerate(AeneasSettings.read().scorexSettings))
      val txPool = generator.syncGeneratingProcess(5).toSeq
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val settings = AeneasSettings.read()

      val block = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         100 << 20,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txPool
      )
      val txPoolSize = txPool.foldLeft(0) {(acc, v) => acc + v.size() + 4}
      val blockByteArray  = PowBlockCompanion.toBytes(block)
      val txBlockBytePart = blockByteArray.slice(PowBlockHeader.PowHeaderSize, blockByteArray.length)
      println(txBlockBytePart.length)

      val extractedTxPool = PowBlockCompanion.extractTransactions(
            blockByteArray.slice(PowBlockHeader.PowHeaderSize, blockByteArray.length), Seq(), 0)

      txPoolSize shouldBe txBlockBytePart.length
      println(txPool.size  + " and " + extractedTxPool.size)
      txPool.size shouldBe extractedTxPool.size
      txPool.zip(extractedTxPool).foreach(pair => pair._1 shouldBe pair._2)
   }

   test("Non-empty block serialization") {
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val settings = AeneasSettings.read()
      val generator = new SimpleBoxTransactionGenerator(AeneasWallet.readOrGenerate(AeneasSettings.read().scorexSettings))

      val txPool = generator.syncGeneratingProcess(100).toSeq

      val block = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         100 << 20,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txPool
      )

      val serialized = PowBlockCompanion.toBytes(block)
      val deserialized = PowBlockCompanion.parseBytes(serialized)

      block shouldBe deserialized.get
   }

   test("Empty block serialization") {
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val settings = AeneasSettings.read()
      val generator = new SimpleBoxTransactionGenerator(AeneasWallet.readOrGenerate(AeneasSettings.read().scorexSettings))

      val block = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         100 << 20,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         Seq()
      )

      val serialized = PowBlockCompanion.toBytes(block)
      val deserialized = PowBlockCompanion.parseBytes(serialized)

      block shouldBe deserialized.get
   }
}


