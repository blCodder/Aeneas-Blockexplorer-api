package history.storage

import block.{AeneasBlock, PowBlock, PowBlockCompanion}
import com.google.common.primitives.Longs
import commons.SimpleBoxTransaction
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.ModifierId
import scorex.core.block.Block
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import scorex.crypto.hash.Sha256
import settings.SimpleMiningSettings

import scala.util.Failure

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 22.01.18.
  */
class SimpleHistoryStorage (storage : LSMStore, settings : SimpleMiningSettings) extends ScorexLogging {
   private val bestBlockIdKey = ByteArrayWrapper(Array.fill(storage.keySize)(-1: Byte))

   def bestPowId: ModifierId = storage.get(bestBlockIdKey)
                                      .map(d => ModifierId @@ d.data)
                                      .getOrElse(settings.GenesisParentId)

   def height : Long = heightOf(bestPowId).getOrElse(0L)

   def bestBlock: PowBlock = {
      require(height > 0, "History is empty")
      modifierById(bestPowId).get.asInstanceOf[PowBlock]
   }

   def update(b: AeneasBlock, diff: Option[(BigInt, Long)], isBest: Boolean) {
      log.info(s"History.update : Write new best=$isBest block ${b.encodedId}")
      val typeByte = b match {
         case _: PowBlock =>
            PowBlock.ModifierTypeId
      }

      val blockH: Iterable[(ByteArrayWrapper, ByteArrayWrapper)] =
      Seq(blockHeightKey(b.id) -> ByteArrayWrapper(Longs.toByteArray(parentHeight(b) + 1)))

      val blockDiff: Iterable[(ByteArrayWrapper, ByteArrayWrapper)] = diff.map { d =>
         Seq(blockDiffKey(b.id) -> ByteArrayWrapper(d._1.toByteArray),
              blockDiffKey(b.id) -> ByteArrayWrapper(Longs.toByteArray(d._2)))
      }.getOrElse(Seq())

      val bestBlockSeq: Iterable[(ByteArrayWrapper, ByteArrayWrapper)] = b match {
         case powBlock: PowBlock if isBest =>
            Seq(bestBlockIdKey -> ByteArrayWrapper(powBlock.id))
         case _ => Seq()
      }

      storage.update(
         ByteArrayWrapper(b.id),
         Seq(),
         blockDiff ++ blockH ++ bestBlockSeq ++ Seq(ByteArrayWrapper(b.id) -> ByteArrayWrapper(typeByte +: b.bytes)))

      val check = storage.lastVersionID.getOrElse(-1L)
      log.info(s" History.storage bestId : $bestBlockIdKey")
   }

   def getPoWDifficulty(idOpt: Option[ModifierId]): BigInt = {
      idOpt match {
         case Some(id) if id sameElements settings.GenesisParentId =>
            settings.initialDifficulty
         case Some(id) =>
            BigInt(storage.get(blockDiffKey(id)).get.data)
         case _ =>
            settings.initialDifficulty
      }
   }

   def parentHeight(b: AeneasBlock): Long = heightOf(parentId(b)).getOrElse(0L)

   def parentId(block: AeneasBlock): ModifierId = block match {
      case powBlock: PowBlock => powBlock.parentId
   }

   def modifierById(blockId: ModifierId): Option[AeneasBlock with
     Block[PublicKey25519Proposition, SimpleBoxTransaction]] = {
      storage.get(ByteArrayWrapper(blockId)).flatMap { bw =>
         val bytes = bw.data
         val mtypeId = bytes.head
         val parsed = mtypeId match {
            case t: Byte if t == PowBlock.ModifierTypeId =>
               PowBlockCompanion.parseBytes(bytes.tail)
         }
         parsed match {
            case Failure(e) => log.warn("Failed to parse bytes from bd", e)
            case _ =>
         }
         parsed.toOption
      }
   }

   def isGenesis(b: AeneasBlock): Boolean = b match {
      case block: PowBlock => block.parentId sameElements settings.GenesisParentId
      case _ => false
   }

   private def blockDiffKey(blockId: Array[Byte]): ByteArrayWrapper =
      ByteArrayWrapper(Sha256(s"difficulties".getBytes ++ blockId))

   private def blockHeightKey(blockId: Array[Byte]): ByteArrayWrapper =
      ByteArrayWrapper(Sha256("height".getBytes ++ blockId))

   //noinspection ScalaStyle
   def heightOf(blockId: Array[Byte]): Option[Long] =
      storage.get(blockHeightKey(blockId))
             .map(b => Longs.fromByteArray(b.data))
}
