package block

import commons.SimpleBoxTransaction
import scorex.core.PersistentNodeViewModifier
import scorex.core.block.Block
import scorex.core.transaction.box.proposition.PublicKey25519Proposition

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.01.18.
  */
trait AeneasBlock extends PersistentNodeViewModifier with Block[PublicKey25519Proposition, SimpleBoxTransaction]

