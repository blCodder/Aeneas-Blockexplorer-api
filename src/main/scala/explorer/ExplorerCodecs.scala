package explorer

import com.sun.xml.internal.ws.api.pipe.Codecs
import io.circe.Encoder
import io.circe.syntax._
import scorex.crypto.encode.Base58

object ExplorerCodecs extends Codecs {
  implicit val accEncoder: Encoder[AccInfo] =
    Encoder.forProduct4("pubKeyBytes", "address", "transactions", "generatedBlockIds")(acc => {(
      Base58.encode(acc.pubKeyBytes).asJson,
      acc.address.asJson,
      acc.txs.map(tx => tx.asJson).asJson,
      acc.generatedBlockIds.map(id => Base58.encode(id).asJson).asJson
      )
    })
}
