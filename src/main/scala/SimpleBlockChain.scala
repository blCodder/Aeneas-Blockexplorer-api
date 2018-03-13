import java.io.File
import java.util.concurrent.Executors

import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import api.WsServerRunner
import api.account.{ClientInformActor, SignUpApi}
import block.AeneasBlock
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import history.sync.{VerySimpleSyncInfo, VerySimpleSyncInfoMessageSpec}
import history.SimpleHistory
import io.iohk.iodb.LSMStore
import mining.Miner
import scorex.core.api.http.{ApiRoute, NodeViewApiRoute}
import scorex.core.app.Application
import scorex.core.network.NodeViewSynchronizer
import scorex.core.network.message.MessageSpec
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import settings.{AeneasSettings, SimpleLocalInterface}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.{Failure, Success}

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 18.01.18.
  */

class SimpleBlockChain(loadSettings: LoadSettings) extends Application with ScorexLogging {
   override type P = PublicKey25519Proposition
   override type TX = SimpleBoxTransaction
   override type PMOD = AeneasBlock
   override type NVHT = VerySimpleNodeViewHolder
   type SI = VerySimpleSyncInfo
   type HIS = SimpleHistory
   type MPOOL = SimpleBoxTransactionMemPool

   private val simpleSettings : AeneasSettings = loadSettings.simpleSettings

   // Note : NEVER NEVER forget to mark implicit as LAZY!
   override implicit lazy val settings: ScorexSettings = AeneasSettings.read().scorexSettings
   override protected lazy val additionalMessageSpecs: Seq[MessageSpec[_]] = Seq(VerySimpleSyncInfoMessageSpec)
   log.info(s"SimpleBlokchain : Settings was initialized. Length is : ${simpleSettings.toString.length}")

   override val nodeViewHolderRef: ActorRef = actorSystem.actorOf(Props(new VerySimpleNodeViewHolder(settings, simpleSettings.miningSettings)))

   override val apiRoutes: Seq[ApiRoute] = Seq(NodeViewApiRoute[P, TX](settings.restApi, nodeViewHolderRef))

   val wFile = new File (simpleSettings.scorexSettings.dataDir.getAbsolutePath + File.separator + "account")
   if (!wFile.exists) wFile.mkdirs()

   val storage = new LSMStore(wFile, maxJournalEntryCount = 10000)
   val wsApi = new SignUpApi(simpleSettings, storage)

   private val pool = Executors.newCachedThreadPool()
   private implicit val exectutionContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(pool)

   private val clientInformer = actorSystem.actorOf(Props(new ClientInformActor(wsApi)))

   private val miner = actorSystem.actorOf(Props(new Miner(nodeViewHolderRef,
      clientInformer,
      simpleSettings.miningSettings, SimpleHistory.readOrGenerate(settings, simpleSettings.miningSettings).storage)))

   override val localInterface: ActorRef =
   actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, miner, simpleSettings.miningSettings)))

   override val nodeViewSynchronizer: ActorRef =
      actorSystem.actorOf(Props(
         new NodeViewSynchronizer[P, TX, SI, VerySimpleSyncInfoMessageSpec.type, PMOD, HIS, MPOOL]
         (networkControllerRef, nodeViewHolderRef, localInterface, VerySimpleSyncInfoMessageSpec, settings.network, timeProvider)))

   new WsServerRunner(wsApi, simpleSettings).run
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

case class LoadSettings() {
  val simpleSettings : AeneasSettings = AeneasSettings.read()
  // set logging path:
  sys.props += ("log.dir" -> simpleSettings.scorexSettings.logDir.getAbsolutePath)
}