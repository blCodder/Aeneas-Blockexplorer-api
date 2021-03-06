package scorex.core.transaction

import scorex.core.mainviews.EphemerealNodeViewModifier
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.{ModifierId, ModifierTypeId, TxId}
import scorex.crypto.hash.Blake2b256


/**
  * A transaction is an atomic state modifier
  */

abstract class Transaction[P <: Proposition] extends EphemerealNodeViewModifier {
  override val modifierTypeId: ModifierTypeId = Transaction.ModifierTypeId

  val messageToSign: Array[Byte]

  override lazy val id: TxId = ModifierId @@ Blake2b256(messageToSign)
}


object Transaction {
  val ModifierTypeId: scorex.core.ModifierTypeId = scorex.core.ModifierTypeId @@ 2.toByte
}