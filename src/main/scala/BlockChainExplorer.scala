import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import block.AeneasBlock
import com.typesafe.config.ConfigFactory
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import history.AeneasHistory
import history.sync.{AeneasSynchronizer, VerySimpleSyncInfo, VerySimpleSyncInfoMessageSpec}
import io.circe.Json
import io.circe.syntax._
import network.BlockchainDownloader
import scorex.core.ModifierId
import scorex.core.api.http.{ApiRoute, NodeViewApiRoute}
import scorex.core.network.NetworkController
import scorex.core.network.message.MessageSpec
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.encode.Base58
import settings.{AeneasSettings, SimpleLocalInterface}
import viewholder.AeneasNodeViewHolder
import viewholder.AeneasNodeViewHolder.AeneasSubscribe

class BlockChainExplorer(loadSettings: LoadSettings) extends AeneasApp {
  override type P = PublicKey25519Proposition
  override type TX = SimpleBoxTransaction
  override type PMOD = AeneasBlock
  override type NVHT = AeneasNodeViewHolder
  type SI = VerySimpleSyncInfo
  type HIS = AeneasHistory
  type MPOOL = SimpleBoxTransactionMemPool

  private val simpleSettings: AeneasSettings = loadSettings.simpleSettings

  override implicit lazy val settings: ScorexSettings = AeneasSettings.read().scorexSettings
  override protected lazy val additionalMessageSpecs: Seq[MessageSpec[_]] = Seq(VerySimpleSyncInfoMessageSpec)

  override protected implicit lazy val actorSystem: ActorSystem = ActorSystem("AeneasActors", loadSettings.aeneasActorConfig)

  override val nodeViewHolderRef: ActorRef = actorSystem.actorOf(Props(new AeneasNodeViewHolder(settings, simpleSettings.miningSettings)))

  override val apiRoutes: Seq[ApiRoute] = Seq(NodeViewApiRoute[P, TX](settings.restApi, nodeViewHolderRef))

  override val localInterface: ActorRef =
    actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, null, null)))

  val downloaderActor: ActorRef =
    actorSystem.actorOf(Props(
      new BlockchainDownloader(networkControllerRef, nodeViewHolderRef, settings.network, downloadSpecs.tail.tail)))

  override val nodeViewSynchronizer: ActorRef =
    actorSystem.actorOf(Props(
      new AeneasSynchronizer[P, TX, SI, VerySimpleSyncInfoMessageSpec.type, PMOD, HIS, MPOOL](networkControllerRef,
        nodeViewHolderRef, localInterface, VerySimpleSyncInfoMessageSpec, settings.network, timeProvider, downloaderActor)))

  val host = "localhost"
  val port = 8080

  val requestHandler: ActorRef = actorSystem.actorOf(Props(new RequestHandler(nodeViewHolderRef)))

  override def run(): Unit = {
    require(settings.network.agentName.length <= ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at ${settings.restApi.bindAddress.toString}")

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(combinedRoute, settings.restApi.bindAddress.getAddress.getHostAddress, settings.restApi.bindAddress.getPort)

    implicit val executionContext = actorSystem.dispatcher

    def syncInfoRoute: Route = path("sync") {
      get {
        entity(as[Json]) {
          onSuccess(requestHandler ? GetSyncInfo) {
            case response: Json =>
              complete(response)
            case _ => complete(StatusCodes.InternalServerError)
          }
        }
      }
    }

    def blockByIdRoute: Route = pathPrefix("block") {
      get {
        entity(as[Json]) {
          val request = ModifierId @@ Base58.decode(Remaining.toString())
          onSuccess(requestHandler ? request) {
            case response: Json =>
              complete(response)
            case _ => complete(StatusCodes.InternalServerError)
          }
        }
      }
    }

    val route = syncInfoRoute ~ blockByIdRoute

    val bindingFuture = Http().bindAndHandle(route, host, port)

    //on unexpected shutdown
    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        log.error("Unexpected shutdown")
        synchronized {
          log.info("Stopping network services")
          if (settings.network.upnpEnabled) upnp.deletePort(settings.network.bindAddress.getPort)
          networkControllerRef ! NetworkController.ReceivableMessages.ShutdownNetwork

          log.info("Stopping actors (incl. block generator)")
          actorSystem.terminate().onComplete { _ =>

            log.info("Exiting from the app...")
            System.exit(0)
          }
        }
      }
    })
  }


}

object BlockChainExplorer {
  def main(args: Array[String]): Unit = {
    val loadSettings = LoadSettings()
    new BlockChainExplorer(loadSettings).run()
  }
}

case class LoadSettings() {
  val simpleSettings: AeneasSettings = AeneasSettings.read()
  private val root = ConfigFactory.load()
  val aeneasActorConfig = root.getConfig("Aeneas")
  println(aeneasActorConfig.toString)
  // set logging path:
  sys.props += ("log.dir" -> simpleSettings.scorexSettings.logDir.getAbsolutePath)
}

//object HistoryUpdater {
//  final val name = "history-updater"
//}
case object GetSyncInfo


class RequestHandler(aeneasNodeViewHolderRef: ActorRef) extends Actor {
  var cachedHistory: AeneasHistory = null

  override def preStart(): Unit = {
    aeneasNodeViewHolderRef ! AeneasSubscribe(Seq(AeneasNodeViewHolder.NodeViewEvent.UpdateHistory))
  }

  override def receive = {
    case response: AeneasHistory => {
      cachedHistory = response
    }
    case GetSyncInfo => {
      sender() ! cachedHistory.syncInfo.serializer.toBytes(cachedHistory.syncInfo).asJson
    }
    case response: ModifierId =>
      sender() ! cachedHistory.modifierById(response).get.asJson
  }
}
