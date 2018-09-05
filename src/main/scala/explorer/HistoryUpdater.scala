package explorer

import akka.actor.{Actor, ActorRef}
import block.{AeneasBlock, PowBlock}
import commons.SimpleBoxTransaction
import history.AeneasHistory
import viewholder.AeneasNodeViewHolder
import viewholder.AeneasNodeViewHolder.AeneasSubscribe

class HistoryUpdater(aeneasNodeViewHolderRef: ActorRef, requestHandlerRef: ActorRef) extends Actor {


  var txs: Seq[SimpleBoxTransaction] = Seq()
  var prevHeight: Long = 0

  override def preStart(): Unit = {
    aeneasNodeViewHolderRef ! AeneasSubscribe(Seq(AeneasNodeViewHolder.NodeViewEvent.UpdateHistory))
  }

  def powBlockMatcher(option: Option[AeneasBlock]): PowBlock = {
    option match {
      case Some(value) =>
        value.asInstanceOf[PowBlock]
    }
  }

  override def receive = {
    case message: AeneasHistory => {

      txs ++ message.lastBlockIds(message.bestBlock(), (message.height - prevHeight).toInt).
        map(x => powBlockMatcher(message.modifierById(x)).transactions)
      prevHeight = message.height

      requestHandlerRef ! message
      requestHandlerRef ! txs
    }
  }
}
