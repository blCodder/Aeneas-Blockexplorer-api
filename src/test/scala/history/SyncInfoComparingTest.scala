package history

import java.io.File

import block.{AeneasBlock, PowBlock}
import history.storage.SimpleHistoryStorage
import io.iohk.iodb.LSMStore
import org.scalatest.{FunSuite, Matchers}
import scorex.core.ModifierId
import scorex.core.consensus.History.HistoryComparisonResult
import scorex.core.transaction.state.PrivateKey25519Companion
import settings.SimpleSettings

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 14.02.18.
  */
class SyncInfoComparingTest extends FunSuite with Matchers {
   test("SyncInfo : equal multiple elements compare test") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      testFile.mkdirs()
      val storage = new SimpleHistoryStorage(new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
      var history = new SimpleHistory(storage, Seq(), settings.miningSettings)
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)

      val block1 = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         1 << 7,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block2 = new PowBlock(
         ModifierId @@ block1.id,
         System.currentTimeMillis(),
         2 << 7,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block3 = new PowBlock(
         ModifierId @@ block2.id,
         System.currentTimeMillis(),
         3 << 7,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block4 = new PowBlock(
         ModifierId @@ block3.id,
         System.currentTimeMillis(),
         4 << 7,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      history = history.append(block1).get._1
      println(history.height)
      history = history.append(block2).get._1
      println(history.height)
      history = history.append(block3).get._1
      println(history.height)
      history = history.append(block4).get._1
      println(history.height)

      val simpleSyncInfo : VerySimpleSyncInfo =
         VerySimpleSyncInfo(4L, Seq(block1, block2, block3, block4).map(_.id), block1.id)

      val res = history.compare(simpleSyncInfo)
      res shouldBe HistoryComparisonResult.Equal
      simpleSyncInfo.blockchainHeight shouldBe history.height
   }

   test("SyncInfo : multiple elements compare test with older chain") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      testFile.mkdirs()
      val storage = new SimpleHistoryStorage(new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
      var history = new SimpleHistory(storage, Seq(), settings.miningSettings)
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

      val block4 = new PowBlock(
         ModifierId @@ block3.id,
         System.currentTimeMillis(),
         4 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block5 = new PowBlock(
         ModifierId @@ block4.id,
         System.currentTimeMillis(),
         5 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      history = history.append(block1).get._1
      history = history.append(block2).get._1
      history = history.append(block3).get._1
      history = history.append(block4).get._1
      history = history.append(block5).get._1
      println(history.height)

      val simpleSyncInfo : VerySimpleSyncInfo =
         VerySimpleSyncInfo(4L, Seq(block1, block2, block3, block4).map(_.id), block1.id)

      val res = history.compare(simpleSyncInfo)
      res shouldBe HistoryComparisonResult.Younger
   }

   test("SyncInfo : multiple elements compare test with younger chain") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      testFile.mkdirs()
      val storage = new SimpleHistoryStorage(new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
      var history = new SimpleHistory(storage, Seq(), settings.miningSettings)
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

      val block4 = new PowBlock(
         ModifierId @@ block3.id,
         System.currentTimeMillis(),
         4 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      val block5 = new PowBlock(
         ModifierId @@ block4.id,
         System.currentTimeMillis(),
         5 << 3,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      history = history.append(block1).get._1
      history = history.append(block2).get._1
      history = history.append(block3).get._1

      val simpleSyncInfo : VerySimpleSyncInfo =
         VerySimpleSyncInfo(4L, Seq(block1, block2, block3, block4).map(_.id), block1.id)

      val res = history.compare(simpleSyncInfo)
      res shouldBe HistoryComparisonResult.Older
   }

   test("SyncInfo : multiple elements compare with different chain") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      testFile.mkdirs()
      val storage = new SimpleHistoryStorage(new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
      var history = new SimpleHistory(storage, Seq(), settings.miningSettings)
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val otherAccount = PrivateKey25519Companion.generateKeys("AeZakMee".getBytes)

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

      val otherBlock1 = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         5 << 3,
         0,
         Array.fill(32) (0 : Byte),
         otherAccount._2,
         Seq()
      )
      val otherBlock2 = new PowBlock(
         ModifierId @@ otherBlock1.id,
         System.currentTimeMillis(),
         5 << 4,
         0,
         Array.fill(32) (0 : Byte),
         otherAccount._2,
         Seq()
      )
      val otherBlock3 = new PowBlock(
         ModifierId @@ otherBlock2.id,
         System.currentTimeMillis(),
         5 << 5,
         0,
         Array.fill(32) (0 : Byte),
         otherAccount._2,
         Seq()
      )

      history = history.append(block1).get._1
      history = history.append(block2).get._1
      history = history.append(block3).get._1

      val simpleSyncInfo : VerySimpleSyncInfo =
         VerySimpleSyncInfo(4L, Seq(otherBlock1, otherBlock2, otherBlock3).map(_.id), otherBlock1.id)

      val res = history.compare(simpleSyncInfo)
      res shouldBe HistoryComparisonResult.Younger
   }
}
