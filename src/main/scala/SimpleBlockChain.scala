import akka.actor.{ActorRef, Props}
import block.AeneasBlock
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import history.{SimpleHistory, VerySimpleSyncInfo, VerySimpleSyncInfoMessageSpec}
import mining.Miner
import scorex.core.api.http.{ApiRoute, NodeViewApiRoute}
import scorex.core.app.Application
import scorex.core.network.NodeViewSynchronizer
import scorex.core.network.message.MessageSpec
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.utils.ScorexLogging
import settings.{SimpleLocalInterface, SimpleSettings}

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

   private val simpleSettings : SimpleSettings = loadSettings.simpleSettings

   // Note : NEVER NEVER forget to mark implicit as LAZY!
   override implicit lazy val settings: ScorexSettings = SimpleSettings.read().scorexSettings
   override protected lazy val additionalMessageSpecs: Seq[MessageSpec[_]] = Seq(VerySimpleSyncInfoMessageSpec)
   log.debug(s"full settings: $simpleSettings")
   log.info(s"SimpleBlokchain : Settings was initialized. Length is : ${simpleSettings.toString.length}")

   override val nodeViewHolderRef: ActorRef = actorSystem.actorOf(Props(new VerySimpleNodeViewHolder(settings, simpleSettings.miningSettings)))
   log.info(s"SimpleBlokchain : NodeViewHolder Actor was initialized : ${nodeViewHolderRef.path}")

   override val apiRoutes: Seq[ApiRoute] = Seq(NodeViewApiRoute[P, TX](settings.restApi, nodeViewHolderRef))

   private val miner = actorSystem.actorOf(Props(new Miner(nodeViewHolderRef,
      simpleSettings.miningSettings, SimpleHistory.readOrGenerate(settings, simpleSettings.miningSettings).storage)))

   override val localInterface: ActorRef =
   actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, miner, simpleSettings.miningSettings)))

   log.info(s"SimpleBlokchain : LocalInterface Actor started : ${localInterface.path}")

   override val nodeViewSynchronizer: ActorRef =
      actorSystem.actorOf(Props(
         new NodeViewSynchronizer[P, TX, SI, VerySimpleSyncInfoMessageSpec.type, PMOD, HIS, MPOOL]
         (networkControllerRef, nodeViewHolderRef, localInterface, VerySimpleSyncInfoMessageSpec, settings.network, timeProvider)))

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

case class LoadSettings(){
  val simpleSettings : SimpleSettings = SimpleSettings.read()
  // set logging path:
  sys.props += ("log.dir"->simpleSettings.scorexSettings.logDir.getAbsolutePath)

}