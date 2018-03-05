package block

import com.google.common.primitives.{Ints, Longs}
import commons.SimpleBoxTransaction
import io.circe.Json
import io.circe.syntax._
import scorex.core.{ModifierId, ModifierTypeId, NodeViewModifier}
import scorex.core.block.Block
import scorex.core.block.Block.{BlockId, Version}
import scorex.core.serialization.Serializer
import scorex.core.transaction.box.proposition.{PublicKey25519Proposition, PublicKey25519PropositionSerializer}
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.{Curve25519, PublicKey}
import settings.SimpleMiningSettings

import scala.util.Try

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 09.02.18.
  */

// This file was transfered from TwinscoinExample

class PowBlockHeader(
                      val parentId: BlockId,
                      val timestamp: Block.Timestamp,
                      val nonce: Long,
                      val brothersCount: Int,
                      val brothersHash: Array[Byte],
                      val generatorProposition: PublicKey25519Proposition) {

   import PowBlockHeader._

   lazy val headerBytes =
      parentId ++
        Longs.toByteArray(timestamp) ++
        Longs.toByteArray(nonce) ++
        Ints.toByteArray(brothersCount) ++
        brothersHash ++
        generatorProposition.pubKeyBytes

   def correctWork(difficulty: BigInt, s: SimpleMiningSettings): Boolean = correctWorkDone(id, difficulty, s)

   lazy val id = ModifierId @@ Blake2b256(headerBytes)

   override lazy val toString = s"PowBlockHeader(id: ${Base58.encode(id)})" +
     s"(parentId: ${Base58.encode(parentId)}, time: $timestamp, " + "nonce: $nonce)"
}

// 116 bytes as well.
object PowBlockHeader {
   val PowHeaderSize = NodeViewModifier.ModifierIdSize + 8 * 2 + 4 + Blake2b256.DigestSize + Curve25519.KeyLength

   def parse(bytes: Array[Byte]): Try[PowBlockHeader] = Try {
      require(bytes.length == PowHeaderSize)
      val parentId = ModifierId @@ bytes.slice(0, 32)
      val timestamp = Longs.fromByteArray(bytes.slice(32, 40))
      val nonce = Longs.fromByteArray(bytes.slice(40, 48))
      val brothersCount = Ints.fromByteArray(bytes.slice(48, 52))
      val brothersHash = bytes.slice(52, 84)
      val prop = PublicKey25519Proposition(PublicKey @@ bytes.slice(84, 116))

      new PowBlockHeader(parentId, timestamp, nonce, brothersCount, brothersHash, prop)
   }

   def correctWorkDone(id: Array[Byte], difficulty: BigInt, s: SimpleMiningSettings): Boolean = {
      val target = s.MaxTarget / difficulty
      BigInt(1, id) < target
   }
}

case class PowBlock(override val parentId: BlockId,
                    override val timestamp: Block.Timestamp,
                    override val nonce: Long,
                    override val brothersCount: Int,
                    override val brothersHash: Array[Byte],
                    override val generatorProposition: PublicKey25519Proposition,
                    brothers: Seq[PowBlockHeader])
  extends PowBlockHeader(parentId, timestamp, nonce, brothersCount, brothersHash, generatorProposition)
    with AeneasBlock {

   override type M = PowBlock

   override lazy val serializer = PowBlockCompanion

   override lazy val version: Version = 0: Byte

   override lazy val modifierTypeId: ModifierTypeId = PowBlock.ModifierTypeId

   lazy val header = new PowBlockHeader(parentId, timestamp, nonce, brothersCount, brothersHash, generatorProposition)

   lazy val brotherBytes = serializer.brotherBytes(brothers)

   override lazy val json: Json = Map(
      "id" -> Base58.encode(id).asJson,
      "parentId" -> Base58.encode(parentId).asJson,
      "timestamp" -> timestamp.asJson,
      "nonce" -> nonce.asJson,
      "brothersHash" -> Base58.encode(brothersHash).asJson,
      "brothers" -> brothers.map(b => Base58.encode(b.id).asJson).asJson
   ).asJson

   override lazy val toString: String = s"PoWBlock(${json.noSpaces})"

   override def transactions: Seq[SimpleBoxTransaction] = Seq()
}

object PowBlockCompanion extends Serializer[PowBlock] {

   def brotherBytes(brothers: Seq[PowBlockHeader]): Array[Byte] = brothers.foldLeft(Array[Byte]()) { case (ba, b) =>
      ba ++ b.headerBytes
   }

   override def toBytes(modifier: PowBlock): Array[Byte] =
      modifier.headerBytes ++ modifier.brotherBytes ++ modifier.generatorProposition.bytes

   override def parseBytes(bytes: Array[Byte]): Try[PowBlock] = {
      val headerBytes = bytes.slice(0, PowBlockHeader.PowHeaderSize)
      PowBlockHeader.parse(headerBytes).flatMap { header =>
         Try {
            val (bs, posit) = (0 until header.brothersCount).foldLeft((Seq[PowBlockHeader](), PowBlockHeader.PowHeaderSize)) {
               case ((brothers, position), _) =>
                  val bBytes = bytes.slice(position, position + PowBlockHeader.PowHeaderSize)

                  (brothers :+ PowBlockHeader.parse(bBytes).get,
                    position + PowBlockHeader.PowHeaderSize)
            }
            val prop = PublicKey25519PropositionSerializer.parseBytes(bytes.slice(posit, posit + Curve25519.KeyLength)).get
            PowBlock(
               header.parentId,
               header.timestamp,
               header.nonce,
               header.brothersCount,
               header.brothersHash,
               prop,
               bs
            )
         }
      }
   }
}

object PowBlock {
   val ModifierTypeId: ModifierTypeId = scorex.core.ModifierTypeId @@ 3.toByte
}
