package network.messagespec

import block.PowBlock
import org.scalatest.{FunSuite, Matchers}
import scorex.core.ModifierId
import scorex.core.transaction.state.PrivateKey25519Companion
import settings.AeneasSettings

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 28.03.18.
  */
class AeneasMessageSpecTest extends FunSuite with Matchers {
   test("Blockchain download request spec serialize/deserialize correctly") {
      val bcDownloadMsgSpec = new FullBlockChainRequestSpec()
      val msg : String = "blockchain"
      val deserialized = bcDownloadMsgSpec.parseBytes(bcDownloadMsgSpec.toBytes(msg)).getOrElse(None)
      deserialized shouldBe msg
   }

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
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val powBlockMessageSpec = new PoWBlockMessageSpec()
      val deserialized =  powBlockMessageSpec.parseBytes(powBlockMessageSpec.toBytes(onlyBlock)).getOrElse(None)
      deserialized shouldEqual onlyBlock
   }

   test("Seq of PoW blocks request spec serialize/deserialize correctly") {
      val settings = AeneasSettings.read()
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)

      val block1 = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         1 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block2 = new PowBlock(
         ModifierId @@ block1.id,
         System.currentTimeMillis(),
         2 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block3 = new PowBlock(
         ModifierId @@ block2.id,
         System.currentTimeMillis(),
         3 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val blockSeq = Seq(block1, block2, block3)

      val powBlocksMessageSpec = new PoWBlocksMessageSpec()
      val serialized = powBlocksMessageSpec.toBytes(blockSeq)
      val deserialized = powBlocksMessageSpec.parseBytes(serialized).getOrElse(None)

      serialized.length / blockSeq.length shouldBe PowBlock.powBlockSize
      deserialized shouldBe blockSeq
   }
}
