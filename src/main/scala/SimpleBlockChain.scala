import java.io.File
import java.util.concurrent.Executors

import akka.actor.{ActorRef, Props, ActorSystem}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import api.WsServerRunner
import api.account.SignUpApi
import block.AeneasBlock
import com.typesafe.config.ConfigFactory
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import history.AeneasHistory
import history.sync.{AeneasSynchronizer, VerySimpleSyncInfo, VerySimpleSyncInfoMessageSpec}
import mining.Miner
import network.BlockchainDownloader
import scorex.core.api.http.{ApiRoute, NodeViewApiRoute}
import scorex.core.network.message.MessageSpec
import scorex.core.serialization.SerializerRegistry
import scorex.core.serialization.SerializerRegistry.SerializerRecord
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import settings.{SimpleLocalInterface, SimpleSettings}
import viewholder.AeneasNodeViewHolder

import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 18.01.18.
  */

class SimpleBlockChain(loadSettings: LoadSettings) extends AeneasApp with ScorexLogging {
   override type P = PublicKey25519Proposition
   override type TX = SimpleBoxTransaction
   override type PMOD = AeneasBlock
   override type NVHT = AeneasNodeViewHolder
   type SI = VerySimpleSyncInfo
   type HIS = AeneasHistory
   type MPOOL = SimpleBoxTransactionMemPool

   private val simpleSettings : AeneasSettings = loadSettings.simpleSettings

   // Note : NEVER NEVER forget to mark implicit as LAZY!
   override implicit lazy val settings: ScorexSettings = AeneasSettings.read().scorexSettings
   override protected lazy val additionalMessageSpecs: Seq[MessageSpec[_]] = Seq(VerySimpleSyncInfoMessageSpec)
   log.info(s"SimpleBloсkchain : Settings was initialized. Length is : ${simpleSettings.toString.length}")

   implicit val serializerReg: SerializerRegistry = SerializerRegistry(Seq(SerializerRecord(SimpleBoxTransaction.simpleBoxEncoder)))

   override protected implicit lazy val actorSystem: ActorSystem = ActorSystem("AeneasActors", loadSettings.aeneasActor)

   override val nodeViewHolderRef: ActorRef = actorSystem.actorOf(Props(new AeneasNodeViewHolder(settings, simpleSettings.miningSettings)))

   override val apiRoutes: Seq[ApiRoute] = Seq(NodeViewApiRoute[P, TX](settings.restApi, nodeViewHolderRef))

   private val miner = actorSystem.actorOf(Props(new Miner(nodeViewHolderRef,
      simpleSettings.miningSettings, AeneasHistory.readOrGenerate(settings, simpleSettings.miningSettings).storage)))

   override val localInterface: ActorRef =
   actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, miner, simpleSettings.miningSettings)))

   val downloaderActor : ActorRef =
      actorSystem.actorOf(Props(
         new BlockchainDownloader(networkControllerRef, nodeViewHolderRef, settings.network)))

   override val nodeViewSynchronizer: ActorRef =
      actorSystem.actorOf(Props(
         new AeneasSynchronizer[P, TX, SI, VerySimpleSyncInfoMessageSpec.type, PMOD, HIS, MPOOL] (networkControllerRef,
            nodeViewHolderRef, localInterface, VerySimpleSyncInfoMessageSpec, settings.network, timeProvider, downloaderActor)))

   new WsServerRunner(miner, simpleSettings).run
   /**
     * API description in openapi format in YAML or JSON
     */
   override val swaggerConfig: String = ""
}

object SimpleBlockChain {
   def main(args: Array[String]): Unit = {
      val loadSettings = LoadSettings()
      new SimpleBlockChain(loadSettings).run()
   }
}

case class LoadSettings() extends ScorexLogging {
   val simpleSettings : SimpleSettings = SimpleSettings.read()
   private val root = ConfigFactory.load()
   val aeneasActor = root.getConfig("Aeneas")
   log.debug(aeneasActor.toString)
  // set logging path:
  sys.props += ("log.dir" -> simpleSettings.scorexSettings.logDir.getAbsolutePath)
}