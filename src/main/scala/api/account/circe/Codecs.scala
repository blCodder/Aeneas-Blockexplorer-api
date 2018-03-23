package api.account.circe

import api.account.SignUpMessagesType
import block.PowBlock
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.extras._
import scorex.crypto.encode.Base58
/**
  * @author luger. Created on 13.03.18.
  * @version ${VERSION}
  */
object Codecs{
    import io.circe.generic.extras.semiauto._
    import api.account.SignUpMessagesType._
    import auto._

    implicit val powBlockEncoder: Encoder[PowBlock] = Encoder.forProduct6("id", "parentId", "timestamp", "nonce", "brothersHash", "brothers")(pb =>{
        (
          Base58.encode(pb.id).asJson,
          Base58.encode(pb.parentId).asJson,
          pb.timestamp.asJson,
          pb.nonce.asJson,
          Base58.encode(pb.brothersHash).asJson,
          pb.brothers.map(b => Base58.encode(b.id).asJson).asJson
        )
    })

    implicit val signUpEncoder: Encoder[SignUpMessage] = deriveEncoder[SignUpMessage]
    implicit val signUpDecoder: Decoder[SignUpMessage] = deriveDecoder[SignUpMessage]

    implicit val signUpMEncoder: Encoder[SignupMessages] = deriveEncoder[SignupMessages]
    implicit class ToReturnPowBlock(pb:PowBlock){
        def toReturnPowBlock =
            SignUpMessagesType.ReturnPowBlock(
                Base58.encode(pb.id),
                Base58.encode(pb.parentId),
                pb.timestamp,
                pb.nonce,
                Base58.encode(pb.brothersHash),
                pb.brothers.map(b => Base58.encode(b.id))
            )
    }

}

object auto extends AutoDerivation {

    import shapeless._

    implicit def encoderValueClass[T <: AnyVal, V](implicit
                                                   g: Lazy[Generic.Aux[T, V :: HNil]],
                                                   e: Encoder[V]
                                                  ): Encoder[T] = Encoder.instance { value =>
        e(g.value.to(value).head)
    }

    implicit def decoderValueClass[T <: AnyVal, V](implicit
                                                   g: Lazy[Generic.Aux[T, V :: HNil]],
                                                   d: Decoder[V]
                                                  ): Decoder[T] = Decoder.instance { cursor =>
        d(cursor).map { value =>
            g.value.from(value :: HNil)
        }
    }

    implicit val configuration: Configuration = Configuration.default.withDiscriminator("action")
}
