package api.account

import akka.actor.Actor
import api.account.CreateAccountFlow.UserKey
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.utils.ScorexLogging
import scorex.crypto.hash.Sha256

import scala.collection.mutable
import scala.util.Random

/**
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
class NewAccActor(store:LSMStore) extends Actor with ScorexLogging{
  private var currentPassPhrase = List.empty[String]
  private var shufflePassPhrase = List.empty[String]
  private var signUpStarted = false
  private var passPhraseSavedByUser = false
  private val userKeySet = mutable.HashSet[UserKey]()

  private def signup:Receive = {
    case NewAccountEvents.CallToSignUp(passPhrase) =>
      log.debug("Call To Sign Up")
      if (!passPhraseSavedByUser) {
        signUpStarted = true
        currentPassPhrase = passPhrase
        sender() ! NewAccountEvents.CallToSignUp(passPhrase)
      }else sender() ! NewAccountEvents.ErrorEvent("already initialized signing up")
  }

  private def signupCancellation:Receive = {
    case NewAccountEvents.SignUpCancelled() =>
      log.debug("Sign Up Cancelled by user ")
      signUpStarted = false
      passPhraseSavedByUser = false
      currentPassPhrase = List.empty
      shufflePassPhrase = List.empty
      sender() ! NewAccountEvents.SignUpCancelled
  }

  private def savedPassPhrase:Receive = {
    case NewAccountEvents.SavedPassPhrase() =>
      log.debug(s"Pass Phrase Saved By User;$signUpStarted")
      if (signUpStarted) {
        passPhraseSavedByUser = true
        shufflePassPhrase = Random.shuffle(currentPassPhrase)
        log.debug(s"shufflePassPhrase: $shufflePassPhrase")
        sender() ! NewAccountEvents.GeneratedConfirmationPassPhrase(shufflePassPhrase)
      }else sender() ! NewAccountEvents.ErrorEvent("")
  }

  private def confirmPassPhrase:Receive = {
    case NewAccountEvents.ConfirmPassPhrase(confirmPhraseSeq) =>
      log.debug(s"Pass Phrase Confirmed by User : $confirmPhraseSeq, ${currentPassPhrase == confirmPhraseSeq}")
      if (currentPassPhrase == confirmPhraseSeq){
        //store.update() TODO: save account to database
        val privateId = Sha256(currentPassPhrase.mkString(",").getBytes("UTF-8"))
        val publicSeed = Sha256(privateId)
        userKeySet += UserKey (new String (publicSeed, "UTF-8"), new String (privateId, "UTF-8"))
        signUpStarted = false
        passPhraseSavedByUser = false
        sender() ! NewAccountEvents.GeneratedSeed(userKeySet.headOption)
      }else sender() ! NewAccountEvents.ErrorEvent("password equals not confirmation password")
  }

  private def receivedPassword:Receive = {
    case NewAccountEvents.ReceivedPassword(pwd) =>
      log.debug(s"password : $pwd")
      currentPassPhrase = List.empty
      shufflePassPhrase = List.empty
      userKeySet.headOption match {
        case Some (x) =>
          val UserKey (publicSeed, privateId) = x

          store.update(
            ByteArrayWrapper(publicSeed.getBytes("UTF-8")), Seq(),
            Seq (ByteArrayWrapper(publicSeed.getBytes("UTF-8")) -> ByteArrayWrapper(Encryption .encrypt(pwd, privateId).getBytes("UTF-8"))))
        case None =>
      }
      userKeySet.clear()
      sender() ! NewAccountEvents.ReceivedPassword(pwd)
  }

  def importAccount ():Receive = {
    case NewAccountEvents.ImportAccont(passPhrase) =>
      val privateId = Sha256(passPhrase.mkString(",").getBytes("UTF-8"))
      val publicSeed = Sha256(privateId)
      userKeySet += UserKey (new String (publicSeed, "UTF-8"), new String (privateId, "UTF-8"))
      signUpStarted = false
      passPhraseSavedByUser = false
      sender() ! NewAccountEvents.GeneratedSeed(userKeySet.headOption)
  }

  def login (): Receive = {
    case NewAccountEvents.SignIn(seed, pwd) =>
      val privateId = store.get(ByteArrayWrapper(seed.getBytes("UTF-8")))
      privateId match {
        case Some(id) =>
          if (
            Sha256 (
              Encryption.decrypt(pwd, new String (id.data, "UTF-8")).getBytes("UTF-8")
            ).sameElements (seed.getBytes("UTF-8")))
            sender() ! NewAccountEvents.ReceivedPassword(pwd)
        case None =>
          sender() ! NewAccountEvents.ErrorEvent("Account not found")
      }
  }

  override def receive: Receive =
    signup orElse
      signupCancellation orElse
      savedPassPhrase orElse
      confirmPassPhrase orElse
      receivedPassword orElse {
      case x =>
        log.error(s"Unknown event type $x")
        sender() ! NewAccountEvents.ErrorEvent("Unknown event type")
    }
}
