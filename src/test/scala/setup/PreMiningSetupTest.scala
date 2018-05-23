package setup

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import mining.PreStartMiningSettingsSetup
import mining.PreStartMiningSettingsSetup.LaunchCPULoader
import org.scalatest.{FunSuite, Matchers}
import settings.AeneasSettings

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 19.05.18.
  */
class PreMiningSetupTest extends FunSuite with Matchers {
   test("Mining time setup test") {
      implicit val currentViewTimer = 70.second
      implicit val timeoutView = new Timeout(currentViewTimer)

      lazy val settings = AeneasSettings.read().miningSettings
      println(s"Old CPU load value : ${settings.miningCPULoad}")
      val system = ActorSystem("testSystem")
      val setupActor = system.actorOf(Props(new PreStartMiningSettingsSetup(settings)))

      val currentViewAwait = ask(setupActor, LaunchCPULoader).mapTo[Boolean]
      val b = Await.result(currentViewAwait, currentViewTimer)
      println(s"New CPU load value : ${settings.miningCPULoad}")
      system.terminate()
   }
}
