package wallet

import java.io.File
import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicLong

import block.{AeneasBlock, PowBlock}
import com.google.common.primitives.Ints
import commons._
import history.AeneasHistory
import history.storage.AeneasHistoryStorage
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.VersionTag
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion, PrivateKey25519Serializer}
import scorex.core.transaction.wallet.{Wallet, WalletBox, WalletBoxSerializer, WalletTransaction}
import scorex.core.utils.{ByteStr, ScorexLogging}
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Blake2b256
import settings.AeneasSettings
import state.SimpleMininalState

import scala.util.Try

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 09.02.18.
  */

case class AeneasWallet(var history: AeneasHistory, seed: ByteStr, store: LSMStore)
  extends Wallet[PublicKey25519Proposition, SimpleBoxTransaction, AeneasBlock, AeneasWallet]
    with ScorexLogging {

   override type S = PrivateKey25519
   override type PI = PublicKey25519Proposition

   private val SecretsKey: ByteArrayWrapper = if (seed.base58.nonEmpty) ByteArrayWrapper(seed.arr.take(store.keySize) ++ Array.fill(Math.max(0, store.keySize - seed.arr.length))(2:Byte)) else ByteArrayWrapper(Array.fill(store.keySize)(2: Byte))

   private val BoxIdsKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(store.keySize)(1: Byte))

   val availableBalance: AtomicLong = new AtomicLong(0)

   def boxIds: Seq[Array[Byte]] = {
      store.get(BoxIdsKey).map(_.data.grouped(store.keySize).toSeq).getOrElse(Seq[Array[Byte]]())
   }

   private lazy val walletBoxSerializer =
      new WalletBoxSerializer[PublicKey25519Proposition, PublicKey25519NoncedBox](PublicKey25519NoncedBoxSerializer)

   //TODO: implement it.
   override def historyTransactions: Seq[WalletTransaction[PublicKey25519Proposition, SimpleBoxTransaction]] = ???

   override def boxes(): Seq[WalletBox[PublicKey25519Proposition, PublicKey25519NoncedBox]] = {
      boxIds
        .flatMap(id => store.get(ByteArrayWrapper(id)))
        .map(_.data)
        .map(ba => walletBoxSerializer.parseBytes(ba))
        .map(_.get)
        .filter(_.box.value > 0)
   }

   /**
     * It counts if sum of inputs in this block.
     * @param acquiredBlock block.
     */
   private [wallet] def extractInputs(acquiredBlock : PowBlock): Long = {
      log.debug(s"Block ${acquiredBlock.encodedId} have ${acquiredBlock.transactionPool.size} transaction(s)")
      acquiredBlock.transactionPool
         .flatMap(tx => tx.to.filter(in => publicKeys.contains(in._1) && in._2 > 0))
         .foldLeft(0 : Long) { (acc, in) => acc + in._2 }
   }

   /**
     * It counts if sum of outputs in this block (with negative sign).
     * @param acquiredBlock block.
     */
   private [wallet] def extractOuts(acquiredBlock : PowBlock): Long = {
      acquiredBlock.transactionPool
         .flatMap(tx => tx.from.filter(in => publicKeys.contains(in._1) && in._2 < 0))
         .foldLeft(0 : Long) { (acc, in) => acc + in._2 } * -1.toLong
   }

   /**
     * It makes full traverse of chain to determine transactions and inputs/outputs which are related to current wallet.
     * It is very expensive operation!
     * Simple Example :
     *
     * Block 1: input (A, 20)
     * Block 2: input (B, 30)
     * Block 3: input (B, 30)
     * Block 4: out (C, 20)
     * Block 5: out (C, 20)
     * Result : available inputs : (B, 10), (B, 30) => 40.
     *
     * @return available inputs.
     */
   def fullCheckoutInputsOutputs() : Unit = {
      val genesis = history.genesis()
      val lastBlock = history.bestBlock()
      var acquiredBlock : PowBlock = lastBlock

      while (acquiredBlock.id.deep != genesis.deep) {
         val extractedInputs = extractInputs(acquiredBlock)
         log.debug(s"$extractedInputs AE was extracted as IN from ${acquiredBlock.encodedId}")
         availableBalance.compareAndSet(availableBalance.get(),
            availableBalance.addAndGet(extractedInputs))

         val extractedOuts = extractOuts(acquiredBlock)
         log.debug(s"$extractedOuts AE was extracted as OUT from ${acquiredBlock.encodedId}")
         availableBalance.compareAndSet(availableBalance.get(),
            availableBalance.addAndGet(extractedOuts))

         acquiredBlock = history.modifierById(acquiredBlock.parentId) match {
            case Some(block) =>
               block.asInstanceOf[PowBlock]
            case None =>
               throw new NoSuchElementException(s"Block ${Base58.encode(acquiredBlock.parentId)} is not finded")
         }
         System.gc()
      }
   }

   override def publicKeys: Set[PublicKey25519Proposition] = secrets.map(_.publicImage)

   override def secrets: Set[PrivateKey25519] = store.get(SecretsKey)
     .map(_.data.grouped(64).map(b => PrivateKey25519Serializer.parseBytes(b).get).toSet)
     .getOrElse(Set.empty[PrivateKey25519])

   override def secretByPublicImage(publicImage: PublicKey25519Proposition): Option[PrivateKey25519] =
      secrets.find(s => s.publicImage == publicImage)

   override def generateNewSecret(): AeneasWallet = {
      val prevSecrets = secrets
      val nonce: Array[Byte] = Ints.toByteArray(prevSecrets.size)
      val s = Blake2b256(seed.arr ++ nonce)
      val (priv, _) = PrivateKey25519Companion.generateKeys(s)
      val allSecrets: Set[PrivateKey25519] = Set(priv) ++ prevSecrets
      log.debug(s"WTF : ${SecretsKey.data}, ${ByteArrayWrapper (new Array[Byte](0)) == ByteArrayWrapper(priv.privKeyBytes)}")

      store.update(ByteArrayWrapper(priv.privKeyBytes),
         Seq(),
         Seq(SecretsKey -> ByteArrayWrapper(allSecrets.toArray.flatMap(p => PrivateKey25519Serializer.toBytes(p)))))
      AeneasWallet(history, seed, store)
   }

   override def scanOffchain(tx: SimpleBoxTransaction): AeneasWallet = this

   override def scanOffchain(txs: Seq[SimpleBoxTransaction]): AeneasWallet = this

   override def scanPersistent(modifier: AeneasBlock): AeneasWallet = {
      log.debug(s"Applying modifier to wallet: ${Base58.encode(modifier.id)}")
      val changes = SimpleMininalState.changes(modifier).get

      val newBoxes = changes.toAppend.filter(s => secretByPublicImage(s.box.proposition).isDefined).map(_.box).map { box =>
         val boxTransaction = modifier.transactions.find(t => t.newBoxes.exists(tb => tb.id sameElements box.id))
         val txId = boxTransaction.map(_.id).getOrElse(Array.fill(32)(0: Byte))
         val ts = boxTransaction.map(_.timestamp).getOrElse(modifier.timestamp)
         val wb = WalletBox[PublicKey25519Proposition, PublicKey25519NoncedBox](box, txId, ts)(PublicKey25519NoncedBoxSerializer)
         ByteArrayWrapper(box.id) -> ByteArrayWrapper(wb.bytes)
      }

      val boxIdsToRemove = changes.toRemove.view.map(_.boxId).map(ByteArrayWrapper.apply)
      val newBoxIds: ByteArrayWrapper = ByteArrayWrapper(newBoxes.toArray.flatMap(_._1.data) ++
        boxIds.filter(bi => !boxIdsToRemove.exists(_.data sameElements bi)).flatten)
      store.update(ByteArrayWrapper(modifier.id), boxIdsToRemove, Seq(BoxIdsKey -> newBoxIds) ++ newBoxes)
      log.debug(s"Successfully applied modifier to wallet: ${Base58.encode(modifier.id)}")

      AeneasWallet(history, seed, store)
   }

   override def rollback(to: VersionTag): Try[AeneasWallet] = Try {
      if (store.lastVersionID.exists(_.data sameElements to)) {
         this
      } else {
         log.debug(s"Rolling back wallet to: ${Base58.encode(to)}")
         store.rollback(ByteArrayWrapper(to))
         log.debug(s"Successfully rolled back wallet to: ${Base58.encode(to)}")
         AeneasWallet(history, seed, store)
      }
   }

   override type NVCT = this.type

}

object AeneasWallet extends ScorexLogging {

   def walletFile(settings: ScorexSettings): File = {
      if (!settings.wallet.walletDir.exists())
         settings.wallet.walletDir.mkdirs()
      new File(s"${settings.wallet.walletDir.getAbsolutePath}") //TODO WTF?????
   }

   def exists(settings: ScorexSettings): Boolean = walletFile(settings).exists()

   def nonEmpty(settings: ScorexSettings): Boolean = walletFile(settings).listFiles().exists(_.isFile)

   def readOrGenerate(history : AeneasHistory, settings: ScorexSettings, seed: ByteStr): AeneasWallet = {
      val wFile = settings.wallet.walletDir
      if (wFile.exists) settings.wallet.walletDir.mkdirs
      else {
         settings.wallet.walletDir.getParentFile.mkdirs
         settings.wallet.walletDir.mkdirs
         settings.wallet.walletDir.createNewFile
      }
      val boxesStorage = new LSMStore(wFile, maxJournalEntryCount = 10000)
      sys.addShutdownHook{
        boxesStorage.close()
      }

      AeneasWallet(history, seed, boxesStorage)
   }

   def readOrGenerate(history : AeneasHistory, settings: ScorexSettings): AeneasWallet = {
      readOrGenerate(history, settings, settings.wallet.seed)
   }

   def emptyHistoryReadOrGenerate(settings: AeneasSettings): AeneasWallet = {
      val wFile = settings.scorexSettings.wallet.walletDir
      readOrGenerate(new AeneasHistory(new AeneasHistoryStorage(new LSMStore(wFile, maxJournalEntryCount = 10000),
                                                                 settings.miningSettings),
                                       Seq(),
                                       settings.miningSettings),
         settings.scorexSettings, settings.scorexSettings.wallet.seed)
   }

   def readOrGenerate(history : AeneasHistory, settings: ScorexSettings, seed: ByteStr, accounts: Int): AeneasWallet =
      (1 to accounts).foldLeft(readOrGenerate(history, settings, seed)) { case (w, _) =>
         w.generateNewSecret()
      }

   def readOrGenerate(history : AeneasHistory, settings: ScorexSettings, accounts: Int): AeneasWallet =
      (1 to accounts).foldLeft(readOrGenerate(history, settings)) { case (w, _) =>
         w.generateNewSecret()
      }

   //wallet with applied initialBlocks
   def genesisWallet(history : AeneasHistory, settings: ScorexSettings, initialBlocks: Seq[AeneasBlock]): AeneasWallet = {
      initialBlocks.foldLeft(readOrGenerate(history, settings).generateNewSecret()) { (a, b) =>
         a.scanPersistent(b)
      }
   }
}

case class WalletValue(value: Value)
case class AeneasInput(proposition: PublicKey25519Proposition, value: Value) extends Ordered[AeneasInput] {
   override def compare(that: AeneasInput): Int = this.value compare that.value
}
