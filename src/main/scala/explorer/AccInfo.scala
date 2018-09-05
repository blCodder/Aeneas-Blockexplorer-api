package explorer

import block.PowBlockCompanion
import com.google.common.primitives.{Bytes, Ints, Longs}
import commons.{SimpleBoxTransaction, SimpleBoxTransactionSerializer}
import io.circe.Json
import io.circe.syntax._
import scorex.core.ModifierId
import scorex.core.serialization.{JsonSerializable, Serializer}
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import scorex.crypto.signatures.PublicKey

import scala.util.Try

case class AccInfo(pubKeyBytes: PublicKey,
                   address: String,
                   txs: Seq[SimpleBoxTransaction],
                   generatedBlockIds: Seq[ModifierId]
                  ) extends JsonSerializable {
  override type M = AccInfo
  override lazy val json: Json = Map(
    "pubKeyBytes" -> Base58.encode(pubKeyBytes).asJson,
    "address" -> address.asJson,
    "transactions" -> txs.map(tx => tx.asJson).asJson,
    "generatedBlockIds" -> generatedBlockIds.map(x =>
      Base58.encode(x).asJson
    ).asJson
  ).asJson

  override def serializer: Serializer[AccInfo] = AccInfoSerializer
}

object AccInfoSerializer extends Serializer[AccInfo] with ScorexLogging {

  final def extractIds(idChunk: Array[Byte],
                       ids: Seq[ModifierId],
                       offset: Int): Seq[ModifierId] = {
    val nextChunkSize = Ints.fromByteArray(idChunk.slice(offset, offset + 32))
    log.debug(s"Offset : ${offset + 32}, size of next chunk : $nextChunkSize, overall size : ${idChunk.length}")
    if (offset + 32 + nextChunkSize == idChunk.length)
      ids :+ ModifierId @@ idChunk.slice(offset + 32, idChunk.length)
    else
      extractIds(idChunk, ids :+ ModifierId @@ idChunk.slice(offset + 32, offset + 32 + nextChunkSize), offset + 32 + nextChunkSize)
  }

  override def toBytes(obj: AccInfo): Array[Byte] = {
    val txsBytes = obj.txs.foldLeft(Array[Byte]())((acc, b) =>
      acc ++ Ints.toByteArray(SimpleBoxTransactionSerializer.toBytes(b).length)
        ++ SimpleBoxTransactionSerializer.toBytes(b))
    val txsLength: Long = txsBytes.length

    Bytes.concat(
      obj.pubKeyBytes,
      Ints.toByteArray(obj.address.getBytes.length),
      obj.address.getBytes,
      Longs.toByteArray(txsLength),
      txsBytes,
      obj.generatedBlockIds.foldLeft(Array[Byte]())((acc, b) => acc ++ b
      )
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[AccInfo] = Try {
    val pubKeyBytes = bytes.slice(0, 34)
    val addressLength = Ints.fromByteArray(bytes.slice(34, 38))
    val address = bytes.slice(38, 38 + addressLength).map(_.toChar).mkString
    var start = 38 + addressLength
    val txsLength = Longs.fromByteArray(bytes.slice(start, start + 8))
    start = start + 8
    val txs = {
      if (bytes.length - start <= 0) Seq()
      else PowBlockCompanion.extractTransactions(bytes.slice(start, (start + txsLength).toInt), Seq.empty, 0)
    }
    start = (start + txsLength).toInt
    val genBlockIds = {
      if (bytes.length - start < 32) Seq()
      else extractIds(bytes.slice(start, bytes.length), Seq.empty, 0)
    }
    AccInfo(
      PublicKey @@ pubKeyBytes,
      address,
      txs,
      genBlockIds
    )
  }

}