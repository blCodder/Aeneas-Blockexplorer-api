package api.auth

import akka.actor.Actor
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings

/**
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
class NewAccActor extends Actor with ScorexLogging{
  var currentPassPhrase = List.empty[String]
  var signUpStarted = false
  var passPhraseSavedByUser = false

  override def receive: Receive = {
    case NewAccountEvents.ReceivedPassword(pwd) =>
      log.debug(s"password : $pwd")
    case NewAccountEvents.CallToSignUp =>
      log.debug("Call To Sign Up")
      if (!passPhraseSavedByUser) signUpStarted = true
    case NewAccountEvents.NewPassPhraseGenerated(passPhrase) =>
      log.debug(s"NewPassPhraseGenerated:$passPhrase")
      if (signUpStarted) currentPassPhrase = passPhrase
    case NewAccountEvents.SignUpCancelled =>
      log.debug("Sign Up Cancelled by user ")
      signUpStarted = false
      passPhraseSavedByUser = false
    case NewAccountEvents.SavedPassPhrase =>
      log.debug("Pass Phrase Saves By User")
      if (signUpStarted) passPhraseSavedByUser = true
    case NewAccountEvents.ConfirmPassPhrase(confirmPhraseSeq) =>
      log.debug(s"Pass Phrase Confirmed by User : $confirmPhraseSeq")
      if (currentPassPhrase == confirmPhraseSeq){

      }
  }
}
