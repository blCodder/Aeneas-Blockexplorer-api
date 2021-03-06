package account

import block.PowBlock
import commons.{SimpleBoxTransaction, Value}
import history.storage.AeneasHistoryStorage
import history.{AeneasHistory, TempDbHelper}
import io.iohk.iodb.LSMStore
import org.scalatest.{FunSuite, Matchers}
import scorex.core.ModifierId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.hash.Digest32
import settings.AeneasSettings
import wallet.AeneasWallet

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 10.05.18.
  */
class WalletValuesRecalculationTest extends FunSuite with Matchers {
   test("Initial wallet input calculation with inputs") {
      val settings = AeneasSettings.read()
      val testFile = TempDbHelper.mkdir

      val storage = new AeneasHistoryStorage (new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
      var history = new AeneasHistory(storage, Seq(), settings.miningSettings)

      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)

      val wallet = AeneasWallet.readOrGenerate(history, settings.scorexSettings)
      wallet.generateNewSecret()

      require(wallet.publicKeys.nonEmpty)

      val tx10 = Seq(wallet.publicKeys.head -> Value @@ 10.toLong)
      val tx20 = Seq(wallet.publicKeys.head -> Value @@ 20.toLong)
      val tx30 = Seq(wallet.publicKeys.head -> Value @@ 30.toLong)

      val txs1 = Seq(
         SimpleBoxTransaction.create(wallet, tx10, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx20, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx30, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx20, 1: Long, Seq()).get
      )

      val txs2 = Seq(
         SimpleBoxTransaction.create(wallet, tx20, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx20, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx20, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx30, 1: Long, Seq()).get
      )
      val txs3 = Seq(
         SimpleBoxTransaction.create(wallet, tx10, 1: Long, Seq()).get,
         SimpleBoxTransaction.create(wallet, tx20, 1: Long, Seq()).get
      )

      val txs4 = Seq(
         SimpleBoxTransaction.create(wallet, tx30, 1: Long, Seq()).get
      )

      println("============")
      println(wallet.publicKeys.size)
      println(wallet.publicKeys.head)
      println(txs1.head.to.head._1 + " --> " + txs1.head.to.head._2)
      println(txs2.head.to.head._1 + " --> " + txs2.head.to.head._2)
      println(txs3.head.to.head._1 + " --> " + txs3.head.to.head._2)
      println(txs4.head.to.head._1 + " --> " + txs4.head.to.head._2)
      println("============")

      val genesisBlock = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         1,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         Seq()
      )
      val block1 = new PowBlock(
         ModifierId @@ genesisBlock.id,
         System.currentTimeMillis(),
         100 << 30,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txs1
      )
      val block2 = new PowBlock(
         ModifierId @@ block1.id,
         System.currentTimeMillis(),
         120 << 40,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txs2
      )
      val block3 = new PowBlock(
         ModifierId @@ block2.id,
         System.currentTimeMillis(),
         80 << 30,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txs3
      )
      val block4 = new PowBlock(
         ModifierId @@ block3.id,
         System.currentTimeMillis(),
         100 << 40,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         txs4
      )

      wallet.history = wallet.history.append(genesisBlock).get._1
                                     .append(block1).get._1
                                     .append(block2).get._1
                                     .append(block3).get._1
                                     .append(block4).get._1

      wallet.fullCheckoutInputsOutputs()
      wallet.availableBalance.longValue() shouldBe 230.toLong
      TempDbHelper.del(testFile)
   }

   test("Wallet input calculation with service transactions") {
      val settings = AeneasSettings.read()
      val testFile = TempDbHelper.mkdir

      val storage = new AeneasHistoryStorage (new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
      var history = new AeneasHistory(storage, Seq(), settings.miningSettings)

      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)

      val wallet = AeneasWallet.readOrGenerate(history, settings.scorexSettings)
      wallet.generateNewSecret()

      val fundAddress = PublicKey25519Proposition.validPubKey("Æx3fbGHUiSMSC8pHDRCJB1qnNfeykA2XtrvrHrxmaWfdxJdhPPuV").get
      val mastersRewardAddress = PublicKey25519Proposition.validPubKey("Æx2yKq4jutF3Q8CkHgr7gtPJ3rDKpkwNKENVcF6PsbRnRDzebh8r").get
      val ownerSupportAddress = PublicKey25519Proposition.validPubKey("Æx3RCDLUPNp3QnGQqeh88NPTuPBarYiiVivWTVdZULLMrf6Hs73c").get

      val serviceTx = IndexedSeq(
         // reward for the block    : 80 * 10^7 granoes.
         SimpleBoxTransaction.create(wallet, Seq(wallet.publicKeys.head -> Value @@ 800000000.toLong), 0).get,
         // public benefit fund fee : 20 * 10^7 granoes.
         SimpleBoxTransaction.create(wallet, Seq(fundAddress            -> Value @@ 100000000.toLong), 0).get,
         // master nodes support fee: 8 * 10^7 granoes.
         SimpleBoxTransaction.create(wallet, Seq(mastersRewardAddress   -> Value @@ 80000000.toLong), 0).get,
         // owners support fee      : 2 * 10^7 granoes.
         SimpleBoxTransaction.create(wallet, Seq(ownerSupportAddress    -> Value @@ 20000000.toLong), 0).get
      )

      println("============")
      println(wallet.publicKeys.size)
      println(wallet.publicKeys.head)
      println("============")

      val genesisBlock = new PowBlock(
         settings.miningSettings.GenesisParentId,
         System.currentTimeMillis(),
         1,
         Digest32 @@ Array.fill(32) (1 : Byte),
         genesisAccount._2,
         Seq()
      )
      val block1 = new PowBlock(
         ModifierId @@ genesisBlock.id,
         System.currentTimeMillis(),
         100 << 30,
         Digest32 @@ Array.fill(32) (1 : Byte),
         wallet.publicKeys.head,
         serviceTx
      )
      val block2 = new PowBlock(
         ModifierId @@ block1.id,
         System.currentTimeMillis(),
         120 << 20,
         Digest32 @@ Array.fill(32) (1 : Byte),
         wallet.publicKeys.head,
         serviceTx
      )

      wallet.history = wallet.history.append(genesisBlock).get._1
                                     .append(block1).get._1
                                     .append(block2).get._1


      wallet.fullCheckoutInputsOutputs()
      wallet.availableBalance.longValue() shouldBe 1600000000L
      TempDbHelper.del(testFile)
   }
}
