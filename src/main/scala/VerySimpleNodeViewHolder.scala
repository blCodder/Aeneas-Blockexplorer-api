import java.io.File

import block.{AeneasBlock, PowBlock, PowBlockCompanion}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool, SimpleBoxTransactionSerializer}
import history.{SimpleHistory, VerySimpleSyncInfo}
import scorex.core.serialization.Serializer
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.Transaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.{ModifierTypeId, NodeViewHolder, NodeViewModifier}
import settings.SimpleMiningSettings
import state.SimpleMininalState
import wallet.AeneasWallet

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.01.18.
  */
//noinspection ScalaStyle
class VerySimpleNodeViewHolder (settings : ScorexSettings, minerSettings: SimpleMiningSettings)
  extends NodeViewHolder[PublicKey25519Proposition, SimpleBoxTransaction, AeneasBlock] {
   override type SI = VerySimpleSyncInfo
   override type HIS = SimpleHistory
   override type MS = SimpleMininalState
   override type VL = AeneasWallet
   override type MP = SimpleBoxTransactionMemPool

   /**
     * Restore a local view during a node startup. If no any stored view found
     * (e.g. if it is a first launch of a node) None is to be returned
     */
   override def restoreState(): Option[(HIS, MS, VL, MP)] = {
      log.debug(s"AeneasWallet.exists:${AeneasWallet.exists(settings)}")
      if (AeneasWallet.exists(settings) && AeneasWallet.nonEmpty(settings)) {
         Some((
           SimpleHistory.readOrGenerate(settings, minerSettings),
           SimpleMininalState.readOrGenerate(settings),
           AeneasWallet.readOrGenerate(settings, 1),
           SimpleBoxTransactionMemPool.emptyPool))
      } else None
   }

   /**
     * Hard-coded initial view all the honest nodes in a network are making progress from.
     */
   override protected def genesisState: (HIS, MS, VL, MP) = {
      log.debug("start genesisState")
      val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
      val genesisBlock = new PowBlock(minerSettings.GenesisParentId,
         System.currentTimeMillis(),
         1,
         0,
         Array.fill(32) (0 : Byte),
         genesisAccount._2,
         Seq()
      )

      var history = SimpleHistory.readOrGenerate(settings, minerSettings)
      history = history.append(genesisBlock).get._1

      log.debug(s"NodeViewHolder : Genesis Block : ${genesisBlock.json.toString()}")
      log.info(s"NodeViewHolder : History height is ${history.storage.height}, ${history.height}")

      val mininalState = SimpleMininalState.genesisState(settings, Seq(genesisBlock))
      val wallet = AeneasWallet.genesisWallet(settings, Seq(genesisBlock))

      (history, mininalState, wallet, SimpleBoxTransactionMemPool.emptyPool)
   }

   /**
     * Serializers for modifiers, to be provided by a concrete instantiation
     */
   override val modifierSerializers: Map[ModifierTypeId, Serializer[_ <: NodeViewModifier]] =
      Map(PowBlock.ModifierTypeId -> PowBlockCompanion,
      Transaction.ModifierTypeId -> SimpleBoxTransactionSerializer)
   /**
     *
     */
   override val networkChunkSize: Int = settings.network.networkChunkSize

}
