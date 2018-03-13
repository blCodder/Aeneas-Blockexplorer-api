package api.account

/**
  * @author luger. Created on 13.03.18.
  * @version ${VERSION}
  */
object SignUpMessagesType {
  sealed trait SignupMessages
  case class Signup() extends SignupMessages
  case class CancelSignUp() extends SignupMessages
  case class PassPhraseSaved() extends SignupMessages
  case class ConfirmPassPhrase(passphrase:List[String]) extends SignupMessages
  case class SetLocalPass(password:String, confirmPassword:String) extends SignupMessages
  case class ImportAccont (passPhrase: List[String]) extends SignupMessages
  case class Login (seed:String, pwd:String) extends SignupMessages

  case class SignUpMessage (msg:SignupMessages)
}