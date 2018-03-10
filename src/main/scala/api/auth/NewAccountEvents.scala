package api.auth

import akka.actor.ActorRef

/**
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
object NewAccountEvents{
  sealed trait NewAccountEvent
  case class ReceivedPassword(pwd: String) extends NewAccountEvent
  case class CallToSignUp () extends NewAccountEvent
  case class SignUpCancelled () extends NewAccountEvent
  case class NewPassPhraseGenerated (passPhrase:List[String]) extends NewAccountEvent
  case class BackupPassPhrase() extends NewAccountEvent
  case class GeneratedConfirmationPassPhrase(passPhrase: List[String]) extends NewAccountEvent
  case class ConfirmationOutActorRef(ref:ActorRef) extends NewAccountEvent
  case class ConfirmPassPhrase(passPhrase: List[String]) extends NewAccountEvent
  case class SavedPassPhrase() extends NewAccountEvent
}

