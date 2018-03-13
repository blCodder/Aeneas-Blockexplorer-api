package api.account

import akka.actor.Actor
import block.PowBlock
import scorex.core.utils.ScorexLogging

/**
  * @author luger. Created on 13.03.18.
  * @version ${VERSION}
  */
class ClientInformActor(signUpApi: SignUpApi) extends Actor with ScorexLogging{
  override def receive: Receive = {
    case a@PowBlock (_, _, _, _, _, _, _) =>
      log.debug(s"block:$a")
      signUpApi.publishBlock(a)
  }

}
