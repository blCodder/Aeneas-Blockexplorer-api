package api.auth

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.scalatest.FunSuite

/**
  * @author luger. Created on 08.03.18.
  * @version ${VERSION}
  */
class AuthApiTest extends FunSuite {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  test("testRoute register") {

  }

}
