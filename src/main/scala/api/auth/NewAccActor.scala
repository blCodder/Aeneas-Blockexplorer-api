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
      log.info(s"password : $pwd")
    case NewAccountEvents.CallToSignUp =>
      if (!passPhraseSavedByUser) signUpStarted = true
    case NewAccountEvents.NewPassPhraseGenerated(passPhrase) =>
      if (signUpStarted) currentPassPhrase = passPhrase
    case NewAccountEvents.SignUpCancelled =>
      signUpStarted = false
      passPhraseSavedByUser = false
    case NewAccountEvents.SavedPassPhrase =>
      if (signUpStarted) passPhraseSavedByUser = true
    case NewAccountEvents.ConfirmPassPhrase(confirmPhraseSeq) =>
      if (currentPassPhrase.sameElements(confirmPhraseSeq)){

      }
  }
}
