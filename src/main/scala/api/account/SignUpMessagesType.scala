package api.account

import block.PowBlock

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
  case class Logout (seed:String) extends SignupMessages
  case class ErrorResponse (msg:String) extends SignupMessages
  case class Seed (seed:String) extends SignupMessages
  case class ConfirmationPassPhrase (phrase:List[String]) extends SignupMessages
  case class PassPhrase (phrase:List[String]) extends SignupMessages
  case class PwdConfirmed () extends SignupMessages
  case class LoggedIn (seed:String) extends SignupMessages
  case class GetSavedSeeds () extends SignupMessages
  case class SavedSeeds (seeds:List[SeedWithAddress]) extends SignupMessages
  case class GetSeedWithAddress (publicSeed:String) extends SignupMessages
  case class ReturnSeedWithAddress (seed:SeedWithAddress) extends SignupMessages
  case class ReturnPowBlock (id:String, parentId:String, timestamp:Long, nonce:Long, brothersHash:String, brothers:Seq[String]) extends SignupMessages

  case class SignUpMessage (msg:SignupMessages)
}