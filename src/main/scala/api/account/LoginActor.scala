package api.account

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.actor.Actor
import api.util.Encryption
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.settings.ScorexSettings
import scorex.core.utils.{ByteStr, ScorexLogging}
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Sha256
import wallet.AeneasWallet

import scala.util.{Failure, Success}

/**
  * processing events from user form for Registration Flow
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
class LoginActor(settings: ScorexSettings, store:LSMStore) extends Actor with ScorexLogging{
  val logged = new AtomicBoolean(false)

  def login (): Receive = {
    case NewAccountEvents.SignIn(seed, pwd) =>
      Base58.decode(seed) match {
        case Success(seedByteArray) =>
          val privateId = store.get(ByteArrayWrapper(seedByteArray))
          privateId match {
            case Some(id) =>
              Encryption.decrypt(pwd, new String (id.data, "UTF-8")) match {
                case Success(idInBase58) =>
                  if (seed == Base58.encode(Sha256(idInBase58))) {
                    val wallet = AeneasWallet.readOrGenerate(settings, ByteStr(Sha256(idInBase58)))
                    val publicKeys = wallet.publicKeys.toSeq.sortBy(_.address)
                    logged.getAndSet(true)
                    val seedWithAddress = SeedWithAddress(seed, publicKeys.headOption.map(_.address).getOrElse(""))
                    sender() ! NewAccountEvents.ReceivedPassword(seedWithAddress, pwd)
                  }else
                    sender() ! NewAccountEvents.ErrorEvent("Account not found")
                case Failure(_) =>
                  sender() ! NewAccountEvents.ErrorEvent("Account not found")
              }
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
        val wallet = AeneasWallet.readOrGenerate(settings, ByteStr(seed.data))
        val publicKeys = wallet.publicKeys.toSeq.sortBy(_.address)
        SeedWithAddress(Base58.encode(seed.data), publicKeys.headOption.map(_.address).getOrElse(""))
      }.toList
      sender() ! NewAccountEvents.ReturnSavedSeeds (seeds)
  }

  def seedWithAddress (): Receive = {
    case NewAccountEvents.GetSeedWithAddress(seed) =>
      Base58.decode(seed) match {
        case Success(seedBytes) =>
          val wallet = AeneasWallet.readOrGenerate(settings, ByteStr(seedBytes))
          log.debug(s"seedWithAddress: wallet:$wallet, publicKeys:${wallet.publicKeys}")
          val publicKeys = wallet.publicKeys.toSeq.sortBy(_.address)
          val seedWithAddr = SeedWithAddress(seed, publicKeys.headOption.map(_.address).getOrElse(""))
          sender() ! NewAccountEvents.ReturnSeedWithAddress (seedWithAddr)
        case _ =>
          sender() ! NewAccountEvents.ErrorEvent("Account not found")
      }
  }

  def logout ():Receive = {
    case NewAccountEvents.Logout(seed) =>
      //TODO
      logged.getAndSet(false)
      sender() ! NewAccountEvents.Logout(seed)
  }

  override def receive: Receive =
      login orElse
      savedSeeds orElse
      seedWithAddress orElse
      logout orElse {
      case x =>
        log.error(s"Unknown event type $x")
        sender() ! NewAccountEvents.ErrorEvent("Unknown event type")
    }
}
