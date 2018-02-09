package history

import block.PowBlock
import scorex.core.consensus.SyncInfo
import scorex.core.network.message.SyncInfoMessageSpec
import scorex.core.serialization.Serializer
import scorex.core.{ModifierId, ModifierTypeId, NodeViewModifier}

import scala.util.Try

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.01.18.
  *
  */
case class VerySimpleSyncInfo(lastBlocks: Seq[ModifierId]) extends SyncInfo {

   require(lastBlocks.size <= VerySimpleSyncInfo.lastBlocksCount)
   override def startingPoints: Seq[(ModifierTypeId, ModifierId)] = {
      Seq(lastBlocks.map(block => PowBlock.ModifierTypeId -> block)).flatten
   }

   override type M = VerySimpleSyncInfo

   override def serializer: Serializer[VerySimpleSyncInfo] = VerySimpleSyncInfoSerializer
}

object VerySimpleSyncInfo {
   val lastBlocksCount = 20
}

object VerySimpleSyncInfoSerializer extends Serializer[VerySimpleSyncInfo] {
   override def toBytes(obj: VerySimpleSyncInfo): Array[Byte] =
      Array(obj.lastBlocks.size.toByte) ++ obj.lastBlocks.foldLeft(Array[Byte]())((a, b) => a ++ b)


   override def parseBytes(bytes: Array[Byte]): Try[VerySimpleSyncInfo] = Try {
      val lastPowBlockIdsSize = bytes.slice(0, 1).head//TODO check bytes is not empty
      require(lastPowBlockIdsSize >= 0 && lastPowBlockIdsSize <= VerySimpleSyncInfo.lastBlocksCount)
      require(bytes.length == 1 + lastPowBlockIdsSize * NodeViewModifier.ModifierIdSize)

      val lastBlockIds =
         bytes.slice(1, 1 + NodeViewModifier.ModifierIdSize * lastPowBlockIdsSize)
              .grouped(NodeViewModifier.ModifierIdSize).toSeq.map(id => ModifierId @@ id)

      VerySimpleSyncInfo(lastBlockIds)
   }
}

object VerySimpleSyncInfoMessageSpec extends SyncInfoMessageSpec[VerySimpleSyncInfo](VerySimpleSyncInfoSerializer.parseBytes)
