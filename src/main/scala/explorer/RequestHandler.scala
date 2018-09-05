package explorer

import akka.actor.Actor
import block.{AeneasBlock, PowBlock}
import commons.SimpleBoxTransaction
import history.AeneasHistory
import scorex.core.ModifierId
import scorex.crypto.encode.Base58

class RequestHandler() extends Actor {
  var cachedHistory: AeneasHistory = null
  var txs: Seq[SimpleBoxTransaction] = Seq()

  def powBlockMatcher(option: Option[AeneasBlock]): PowBlock = {
    option match {
      case Some(value) =>
        value.asInstanceOf[PowBlock]
    }
  }

  override def receive = {

    case message: AeneasHistory => cachedHistory = message
    case message: Seq[SimpleBoxTransaction] => txs = message

    case GetHeight => {
      sender() ! cachedHistory.syncInfo.blockchainHeight
    }
    case request: GetBlocksByID => {
      sender() ! cachedHistory.lastBlockIds(powBlockMatcher(cachedHistory.modifierById(request.start)),
        request.amount).map(x => powBlockMatcher(cachedHistory.modifierById(x)))
    }
    case request: GetBlocksByHeight => {
      val difference = (cachedHistory.height - request.start).toInt + 1
      val head = powBlockMatcher(cachedHistory.modifierById(cachedHistory.lastBlockIds(cachedHistory.bestBlock(),
        difference).last))
      sender() ! cachedHistory.lastBlockIds(head,
        request.amount).map(x => powBlockMatcher(cachedHistory.modifierById(x)))
    }
    case request: GetBlock => {
      sender() ! powBlockMatcher(cachedHistory.modifierById(request.id))
    }
    case request: GetBlockIds => {
      if (request.start < cachedHistory.height) {
        val difference = (cachedHistory.height - request.start).toInt + 1
        val block = powBlockMatcher(cachedHistory.modifierById(cachedHistory.lastBlockIds(cachedHistory.bestBlock(),
          difference).last))
        sender() ! cachedHistory.lastBlockIds(block, request.amount).map(x => Base58.encode(x))
      } else {
        sender() ! cachedHistory.lastBlockIds(cachedHistory.bestBlock(), request.amount).map(x => Base58.encode(x))
      }
    }
    case request: GetAcc => ???
    case request: LastTxs => {
      if (txs.size > 100) txs = txs.take(100)
      sender() ! txs
    }
  }
}

case object GetHeight

case class GetBlockIds(start: Long, amount: Int)

case class GetBlocksByID(start: ModifierId, amount: Int)

case class GetBlocksByHeight(start: Long, amount: Int)

case class GetBlock(id: ModifierId)

case class LastTxs(amount: Int)

case class GetAcc(publicKey: String)