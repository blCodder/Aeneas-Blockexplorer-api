package api.account

import akka.actor.Actor
import api.util.Encryption
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Sha256

import scala.collection.mutable
import scala.util.{Random, Success}

/**
  * processing events from user form for Registration Flow
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
class LoginActor(store:LSMStore) extends Actor with ScorexLogging{

  def login (): Receive = {
    case NewAccountEvents.SignIn(seed, pwd) =>
      Base58.decode(seed) match {
        case Success(seedByteArray) =>
          val privateId = store.get(ByteArrayWrapper(seedByteArray))
          privateId match {
            case Some(id) =>
              val idInBase58 = Encryption.decrypt(pwd, new String (id.data, "UTF-8"))
              if (seed == Base58.encode(Sha256(idInBase58)))
                sender() ! NewAccountEvents.ReceivedPassword(pwd)
            case None =>
              sender() ! NewAccountEvents.ErrorEvent("Account not found")
          }
        case _ =>
          sender() ! NewAccountEvents.ErrorEvent("Seed is corrupted")
      }
  }

  def savedSeeds (): Receive = {
    case NewAccountEvents.GetSavedSeeds() =>
      val seeds = store.getAll().map{pair =>
        val (seed, _) = pair
        Base58.encode(seed.data)
      }.toList
      sender() ! NewAccountEvents.ReturnSavedSeeds (seeds)
  }

  def logout ():Receive = {
    case NewAccountEvents.Logout(seed) =>
      //TODO
      sender() ! NewAccountEvents.Logout(seed)
  }

  override def receive: Receive =
      login orElse
      savedSeeds orElse
      logout orElse {
      case x =>
        log.error(s"Unknown event type $x")
        sender() ! NewAccountEvents.ErrorEvent("Unknown event type")
    }
}
