package api.auth

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.scalatest.{Matchers, WordSpec}
import shapeless.ops.zipper.Get
import akka.http.scaladsl.server.Directives._
/**
  * @author luger. Created on 08.03.18.
  * @version ${VERSION}
  */
class AuthApiTest extends WordSpec with Matchers with ScalatestRouteTest {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  "testRoute signup" should {
    Get
  }

}
