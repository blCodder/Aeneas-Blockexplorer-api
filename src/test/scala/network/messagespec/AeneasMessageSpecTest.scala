package network.messagespec

import block.PowBlock
import commons.{SimpleBoxTransaction, SimpleBoxTransactionGenerator, SimpleBoxTransactionSerializer}
import org.scalatest.{FunSuite, Matchers}
import scorex.core.ModifierId
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.hash.Digest32
import settings.AeneasSettings
import wallet.AeneasWallet

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 28.03.18.
  */
class AeneasMessageSpecTest extends FunSuite with Matchers {
   test("End download request spec serialize/deserialize correctly") {
      val endDownloadMsgSpec = new EndDownloadSpec()
      val msg : String = "END"
      val deserialized = endDownloadMsgSpec.parseBytes(endDownloadMsgSpec.toBytes(msg)).getOrElse(None)
      deserialized shouldBe msg
   }

   test("PoW block request spec serialize/deserialize correctly") {
      val settings = AeneasSettings.read()
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)

      val onlyBlock = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         1 << 3,
         Digest32 @@ Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val powBlockMessageSpec = new PoWBlockMessageSpec()
      val deserialized =  powBlockMessageSpec.parseBytes(powBlockMessageSpec.toBytes(onlyBlock)).getOrElse(None)
      deserialized shouldEqual onlyBlock
   }

//   test("Seq of PoW blocks with empty txPool + request spec serialize/deserialize correctly") {
//      val settings = AeneasSettings.read()
//      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
//
//      val block1 = new PowBlock(
//         settings.miningSettings.GenesisParentId,
//         System.currentTimeMillis(),
//         1 << 3,
//         Digest32 @@ Array.fill(32) (0 : Byte),
//         genesisAccount._2,
//         Seq()
//      )
//
//      val block2 = new PowBlock(
//         ModifierId @@ block1.id,
//         System.currentTimeMillis(),
//         2 << 3,
//         Digest32 @@ Array.fill(32) (0 : Byte),
//         genesisAccount._2,
//         Seq()
//      )
//
//      val block3 = new PowBlock(
//         ModifierId @@ block2.id,
//         System.currentTimeMillis(),
//         3 << 3,
//         Digest32 @@ Array.fill(32) (0 : Byte),
//         genesisAccount._2,
//         Seq()
//      )
//
//      val blockSeq = Seq(block1, block2, block3)
//
//      val powBlocksMessageSpec = new PoWBlocksMessageSpec()
//      val serialized = powBlocksMessageSpec.toBytes(blockSeq)
//      val deserialized = powBlocksMessageSpec.parseBytes(serialized).getOrElse(None)
//
//      deserialized shouldBe blockSeq
//   }

   test("Seq of PoW blocks with various txs + request spec serialize/deserialize correctly") {
      val settings = AeneasSettings.read()
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val txGenerator = new SimpleBoxTransactionGenerator(AeneasWallet.readOrGenerate(settings.scorexSettings))

      val txPool1 : Seq[SimpleBoxTransaction] = txGenerator.syncGeneratingProcess(30)
      val txPool2 : Seq[SimpleBoxTransaction] = txGenerator.syncGeneratingProcess(50)
      val txPool3 : Seq[SimpleBoxTransaction] = txGenerator.syncGeneratingProcess(10)

      val block1 = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         10 << 10,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txPool1
      )

      val block2 = new PowBlock(
         ModifierId @@ block1.id,
         System.currentTimeMillis(),
         10 << 11,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txPool2
      )

      val block3 = new PowBlock(
         ModifierId @@ block2.id,
         System.currentTimeMillis(),
         10 << 12,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txPool3
      )

      val blockSeq = Seq(block1, block2, block3)

      val powBlocksMessageSpec = new PoWBlocksMessageSpec()
      val serialized = powBlocksMessageSpec.toBytes(blockSeq)
      val deserialized : Seq[PowBlock] = powBlocksMessageSpec.parseBytes(serialized).get

      blockSeq.zip(deserialized).foreach(el => el._1 shouldBe el._2)
   }
}
