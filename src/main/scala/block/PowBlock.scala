package block

import _root_.settings.SimpleMiningSettings
import com.google.common.primitives.{Ints, Longs}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionSerializer}
import io.circe.Json
import io.circe.syntax._
import scorex.core._
import scorex.core.block.Block
import scorex.core.block.Block.{BlockId, Version}
import scorex.core.mainviews.NodeViewModifier
import scorex.core.serialization.{JsonSerializable, Serializer}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.crypto.signatures.{Curve25519, PublicKey}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 09.02.18.
  */

// This file was transfered from TwinscoinExample

class PowBlockHeader( val parentId: BlockId,
                      val timestamp: Block.Timestamp,
                      val nonce: Long,
                      val merkleRoot : MerkleHash,
                      val generatorProposition: PublicKey25519Proposition) {

   import PowBlockHeader._

   lazy val headerBytes =
      parentId ++
        Longs.toByteArray(timestamp) ++
        Longs.toByteArray(nonce) ++
        merkleRoot ++
        generatorProposition.pubKeyBytes

   // TODO: refactor this.
   def correctWork(difficulty: BigInt, s: SimpleMiningSettings): Boolean = correctWorkDone(id, difficulty, s)

   lazy val id = ModifierId @@ Blake2b256(headerBytes)

   override lazy val toString = s"PowBlockHeader(id: ${Base58.encode(id)})" +
     s"(parentId: ${Base58.encode(parentId)}, time: $timestamp, " + "nonce: $nonce)"
}

// 112 bytes as well.
object PowBlockHeader {
   val PowHeaderSize = NodeViewModifier.ModifierIdSize * 2 + 8 * 2 + Curve25519.KeyLength // 96 + 16 = 112

   // 4 + 32 = 36 bytes was throwed out
   def parse(bytes: Array[Byte]): Try[PowBlockHeader] = Try {
      require(bytes.length == PowHeaderSize)
      val parentId = ModifierId @@ bytes.slice(0, 32)
      val timestamp = Longs.fromByteArray(bytes.slice(32, 40))
      val nonce = Longs.fromByteArray(bytes.slice(40, 48))
      val merkleRoot = Digest32 @@ bytes.slice(48, 80)
      val prop = PublicKey25519Proposition(PublicKey @@ bytes.slice(80, 112))

      new PowBlockHeader(parentId, timestamp, nonce, merkleRoot, prop)
   }

   def correctWorkDone(id: Array[Byte], difficulty: BigInt, s: SimpleMiningSettings): Boolean = {
      val target = s.MaxTarget / difficulty
      BigInt(1, id) < target
   }
}

case class PowBlock(override val parentId: BlockId,
                    override val timestamp: Block.Timestamp,
                    override val nonce: Long,
                    override val merkleRoot: MerkleHash,
                    override val generatorProposition: PublicKey25519Proposition,
                    transactionPool: Seq[SimpleBoxTransaction])
  extends PowBlockHeader(parentId, timestamp, nonce, merkleRoot, generatorProposition)
    with AeneasBlock with JsonSerializable {

   override type M = PowBlock

   override lazy val serializer = PowBlockCompanion

   override lazy val version: Version = 0: Byte

   override lazy val modifierTypeId: ModifierTypeId = PowBlock.ModifierTypeId

   lazy val header = new PowBlockHeader(parentId, timestamp, nonce, merkleRoot, generatorProposition)

   override lazy val json: Json = Map(
      "id" -> Base58.encode(id).asJson,
      "parentId" -> Base58.encode(parentId).asJson,
      "timestamp" -> timestamp.asJson,
      "nonce" -> nonce.asJson,
      "merkleRoot" -> Base58.encode(merkleRoot).asJson,
      "transactions" -> transactionPool.map(tx =>
         tx.asJson,
      ).asJson
   ).asJson

   override lazy val toString: String = s"PoWBlock(${json.noSpaces})"

   // not implemented here.
   override def transactions: Seq[SimpleBoxTransaction] = transactionPool

   def size() : Int = {
      headerBytes.length + transactionPool.foldLeft(0) {(acc, tx) => acc + tx.size()}
   }
   def serializedSize() : Int = {
      PowBlockCompanion.toBytes(this).length
   }
}

object PowBlockCompanion extends Serializer[PowBlock] with ScorexLogging {

   @tailrec
   final def extractTransactions(transactionChunk: Array[Byte],
                                 txs: Seq[SimpleBoxTransaction],
                                 offset: Int): Seq[SimpleBoxTransaction] = {
      val nextTxSize = Ints.fromByteArray(transactionChunk.slice(offset, offset + 4))
      log.debug(s"Offset : ${offset + 4}, size of next chunk : $nextTxSize, overall size : ${transactionChunk.length}")
      if (offset + 4 + nextTxSize == transactionChunk.length) {
         SimpleBoxTransactionSerializer.parseBytes(
            transactionChunk.slice(offset + 4, transactionChunk.length)) match {
            case Success(tx) => txs :+ tx
            case Failure(ex) =>
               log.error(s"Last txs parsing error happened : $ex")
               throw ex // TODO : ask what to handle it correctly
         }
      } else {
         SimpleBoxTransactionSerializer.parseBytes(
            transactionChunk.slice(offset + 4, offset + 4 + nextTxSize)) match {
            case Success(tx) =>
               extractTransactions(transactionChunk, txs :+ tx, offset + 4 + nextTxSize)
            case Failure(ex) =>
               log.error(s"Transaction parsing error happened : $ex")
               txs
         }
      }
   }

   override def toBytes(modifier: PowBlock): Array[Byte] =
      modifier.headerBytes ++
      modifier.transactionPool.foldLeft(Array[Byte]())((acc, b) =>
      acc ++ Ints.toByteArray(SimpleBoxTransactionSerializer.toBytes(b).length) ++ SimpleBoxTransactionSerializer.toBytes(b))

   override def parseBytes(bytes: Array[Byte]): Try[PowBlock] = Try {
      // sizes of block parts
      val txOffset = PowBlockHeader.PowHeaderSize
      val headerBytes = bytes.slice(0, txOffset)
      val header = PowBlockHeader.parse(headerBytes).get
      val txs = {
         if (bytes.length - txOffset <= SimpleBoxTransaction.theMostLessTxSize) Seq()
         else PowBlockCompanion.extractTransactions(bytes.slice(txOffset, bytes.length), Seq.empty, 0)
      }

      PowBlock(
         header.parentId,
         header.timestamp,
         header.nonce,
         header.merkleRoot,
         header.generatorProposition,
         txs
      )
   }
}

object PowBlock extends ScorexLogging {
   val ModifierTypeId: ModifierTypeId = scorex.core.ModifierTypeId @@ 3.toByte
   val blockHeaderSize = 116 // base powBlockSize with empty tx pool
}
