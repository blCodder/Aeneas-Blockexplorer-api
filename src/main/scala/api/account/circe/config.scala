package api.account.circe

import io.circe._
import io.circe.generic.extras._
import io.circe.generic.semiauto._
/**
  * @author luger. Created on 13.03.18.
  * @version ${VERSION}
  */
object json {

  object codecs {

    import auto._, api.account.SignUpMessagesType._


    implicit val movieEncoder: Encoder[SignUpMessage] = deriveEncoder[SignUpMessage]
    implicit val movieDecoder: Decoder[SignUpMessage] = deriveDecoder[SignUpMessage]

  }

  private object auto extends AutoDerivation {

    import shapeless._

    implicit def encoderValueClass[T <: AnyVal, V](implicit
                                                   g: Lazy[Generic.Aux[T, V :: HNil]],
                                                   e: Encoder[V]
                                                  ): Encoder[T] = Encoder.instance { value ⇒
      e(g.value.to(value).head)
    }

    implicit def decoderValueClass[T <: AnyVal, V](implicit
                                                   g: Lazy[Generic.Aux[T, V :: HNil]],
                                                   d: Decoder[V]
                                                  ): Decoder[T] = Decoder.instance { cursor ⇒
      d(cursor).map { value ⇒
        g.value.from(value :: HNil)
      }
    }

    implicit val configuration: Configuration = Configuration.default.withDiscriminator("type")
  }

}