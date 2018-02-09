package settings

import akka.actor.ActorRef
import block.{AeneasBlock, PowBlock}
import commons.SimpleBoxTransaction
import mining.Miner.{MineBlock, StartMining, StopMining}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.{LocalInterface, ModifierId}

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 31.01.18.
  */
class SimpleLocalInterface (override val viewHolderRef: ActorRef,
                            powMinerRef: ActorRef,
                            minerSettings: SimpleMiningSettings)
  extends LocalInterface[PublicKey25519Proposition, SimpleBoxTransaction, AeneasBlock] {

   private var block = false

   override protected def onSuccessfulTransaction(tx: SimpleBoxTransaction): Unit = ???

   override protected def onFailedTransaction(tx: SimpleBoxTransaction): Unit = ???

   override protected def onStartingPersistentModifierApplication(pmod: AeneasBlock): Unit = ???

   override protected def onSyntacticallySuccessfulModification(mod: AeneasBlock): Unit = ???

   override protected def onSyntacticallyFailedModification(mod: AeneasBlock): Unit = ???

   override protected def onSemanticallySuccessfulModification(mod: AeneasBlock): Unit = {
      if (!block) {
         mod match {
            case wb: PowBlock =>
               powMinerRef ! MineBlock
            case _ =>
         }
      }
   }

   override protected def onSemanticallyFailedModification(mod: AeneasBlock): Unit = ???

   override protected def onNewSurface(newSurface: Seq[ModifierId]): Unit = ???

   override protected def onRollbackFailed(): Unit = {
      log.error("Too deep rollback occurred!")
   }

   override protected def onNoBetterNeighbour(): Unit = {
      powMinerRef ! StartMining
      block = false
   }

   override protected def onBetterNeighbourAppeared(): Unit = {
      powMinerRef ! StopMining
      block = true
   }
}
