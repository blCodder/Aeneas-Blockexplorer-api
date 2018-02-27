package history

import java.io.File

import block.{PowBlock, PowBlockCompanion}
import history.storage.SimpleHistoryStorage
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import org.apache.commons.io.FileUtils
import org.scalatest.{FunSuite, Matchers}
import scorex.core.ModifierId
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.encode.Base58
import settings.SimpleSettings

import scala.util.Success

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 20.02.18.
  */
class HistoryTest extends FunSuite with Matchers {
   test("History : block sequential append") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      FileUtils.deleteDirectory(testFile)
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
                       .append(block2).get._1
                       .append(block3).get._1
                       .append(block4).get._1
                       .append(block5).get._1

      history.height shouldBe 5
      history.storage.parentId(block5) shouldBe block4.id
      history.storage.parentId(block4) shouldBe block3.id
      history.storage.parentId(block3) shouldBe block2.id
      history.storage.parentId(block2) shouldBe block1.id
   }

   test("History : block nonsequential append height test") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      FileUtils.deleteDirectory(testFile)
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
         ModifierId @@ block1.id,
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
                       .append(block2).get._1
                       .append(block3).get._1
                       .append(block4).get._1
                       .append(block5).get._1

      history.height shouldBe 4
   }

   test("History : receiving of genesis block") {
      val settings = SimpleSettings.read()
      // we need to create custom history storage because validators fails our blocks appending.
      val testFile = new File(s"${System.getenv("AENEAS_TESTPATH")}/blocks")
      FileUtils.deleteDirectory(testFile)
      testFile.mkdirs()
      val store = new LSMStore(testFile, maxJournalEntryCount = 100)
      val storage = new SimpleHistoryStorage(store, settings.miningSettings)
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

      history = history.append(block1).get._1

      val genesis = store.get(ByteArrayWrapper(settings.miningSettings.GenesisParentId))
      val genesisId = history.genesis()
      store.getAll().foreach(a => {
         val (key, value) = a
         PowBlockCompanion.parseBytes(value.data) match {
            case Success(x) => println(s"${Base58.encode(key.data)}; value = $x")
            case _ => println(s"FALL, HERO : ${Base58.encode(key.data)}; value = $value")
         }
      })
      genesisId.deep shouldBe block1.id.deep
   }
}
