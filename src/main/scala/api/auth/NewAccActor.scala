package api.auth

import akka.actor.Actor
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings

import scala.util.Random

/**
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
class NewAccActor extends Actor with ScorexLogging{
  var currentPassPhrase = List.empty[String]
  var shufflePassPhrase = List.empty[String]
  var signUpStarted = false
  var passPhraseSavedByUser = false

  override def receive: Receive = {
    case NewAccountEvents.ReceivedPassword(pwd) =>
      log.debug(s"password : $pwd")
    case NewAccountEvents.CallToSignUp =>
      log.debug("Call To Sign Up")
      if (!passPhraseSavedByUser) signUpStarted = true
    case NewAccountEvents.NewPassPhraseGenerated(passPhrase) =>
      log.debug(s"NewPassPhraseGenerated:$signUpStarted; $passPhrase;")
      if (signUpStarted) currentPassPhrase = passPhrase
    case NewAccountEvents.SignUpCancelled =>
      log.debug("Sign Up Cancelled by user ")
      signUpStarted = false
      passPhraseSavedByUser = false
      currentPassPhrase = List.empty
    case NewAccountEvents.SavedPassPhrase =>
      log.debug(s"Pass Phrase Saved By User;$signUpStarted")
      if (signUpStarted) {
        passPhraseSavedByUser = true
      }
    case NewAccountEvents.ConfirmationOutActorRef(ref) =>
      log.debug(s"ConfirmationOutActorRef;$signUpStarted; $currentPassPhrase")
      shufflePassPhrase = Random.shuffle(currentPassPhrase)
      log.debug(s"shufflePassPhrase: $shufflePassPhrase")
      ref ! NewAccountEvents.GeneratedConfirmationPassPhrase(shufflePassPhrase)
    case NewAccountEvents.ConfirmPassPhrase(confirmPhraseSeq) =>
      log.debug(s"Pass Phrase Confirmed by User : $confirmPhraseSeq")
      if (currentPassPhrase == confirmPhraseSeq){

      }
  }
}
