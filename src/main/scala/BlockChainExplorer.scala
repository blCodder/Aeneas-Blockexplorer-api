import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import block.{AeneasBlock, PowBlock}
import com.typesafe.config.ConfigFactory
import commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import history.AeneasHistory
import history.sync.{AeneasSynchronizer, VerySimpleSyncInfo, VerySimpleSyncInfoMessageSpec}
import scala.util.{Failure, Success}
import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
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
import api.account.circe.Codecs._

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

  private val miner: ActorRef = actorSystem.actorOf(Props(new Actor {
    override def receive: Receive = ???
  }))

  override val localInterface: ActorRef =
    actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, miner, simpleSettings.miningSettings)))

  val downloaderActor: ActorRef =
    actorSystem.actorOf(Props(
      new BlockchainDownloader(networkControllerRef, nodeViewHolderRef, settings.network, downloadSpecs.tail.tail)))

  override val nodeViewSynchronizer: ActorRef =
    actorSystem.actorOf(Props(
      new AeneasSynchronizer[P, TX, SI, VerySimpleSyncInfoMessageSpec.type, PMOD, HIS, MPOOL](networkControllerRef,
        nodeViewHolderRef, localInterface, VerySimpleSyncInfoMessageSpec, settings.network, timeProvider, downloaderActor)))

  val host = simpleSettings.explorerSettings.bindAddress.getAddress.getHostAddress
  val port = simpleSettings.explorerSettings.bindAddress.getPort

  val requestHandler: ActorRef = actorSystem.actorOf(Props(new RequestHandler(nodeViewHolderRef)))

  override def run(): Unit = {
    require(settings.network.agentName.length <= ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at ${settings.restApi.bindAddress.toString}")

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(combinedRoute, settings.restApi.bindAddress.getAddress.getHostAddress, settings.restApi.bindAddress.getPort)

    implicit val executionContext = actorSystem.dispatcher

    def explorerRoutes: Route =
      get {
        path("height") {
          onComplete(requestHandler ? GetHeight) {
            case Success(response) =>
              complete(response)
            case Failure(exception) =>
              log.error(s"Error: ${exception.toString}")
              complete(StatusCodes.InternalServerError)
          }
        } ~
          path("block" / Segment) { (request) =>
            Base58.decode(request) match {
              case Success(id) =>
                onComplete(requestHandler ? (ModifierId @@ id)) {
                  case Success(response) =>
                    complete(response)
                  case Failure(exception) =>
                    log.error(s"Error: ${exception.toString}")
                    complete(StatusCodes.InternalServerError)
                }
              case _ =>
                complete(StatusCodes.InternalServerError)
            }
          } ~
          path("blockIds" / LongNumber / IntNumber) { (start, amount) =>
            onComplete(requestHandler ? GetBlockIds(start, amount)) {
              case Success(response) =>
                complete(response)
              case Failure(exception) =>
                log.error(s"Error: ${exception.toString}")
                complete(StatusCodes.InternalServerError)
            }
          } ~
          path("blocks" / Segment / IntNumber) { (start, amount) =>
            Base58.decode(start) match {
              case Success(idByteArray) => {
                onComplete(requestHandler ? GetBlocks(ModifierId @@ idByteArray, amount)) {
                  case Success(response) =>
                    complete(response)
                  case Failure(exception) =>
                    log.error(s"Error: ${exception.toString}")
                    complete(StatusCodes.InternalServerError)
                }
              }
              case _ => {
                complete(StatusCodes.InternalServerError)
              }
            }

          }
      }

    val bindingFuture = Http().bindAndHandle(explorerRoutes, host, port)

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

case object GetHeight

case class GetBlockIds(start: Long, amount: Int)

case class GetBlocks(start: ModifierId, amount: Int)

case class GetBlock(id: ModifierId)


class RequestHandler(aeneasNodeViewHolderRef: ActorRef) extends Actor {
  var cachedHistory: AeneasHistory = null

  override def preStart(): Unit = {
    aeneasNodeViewHolderRef ! AeneasSubscribe(Seq(AeneasNodeViewHolder.NodeViewEvent.UpdateHistory))
  }

  override def receive = {
    case response: AeneasHistory => {
      cachedHistory = response
    }

    case GetHeight => {
      sender() ! cachedHistory.syncInfo.blockchainHeight
    }

    case request: GetBlock => {
      sender() ! cachedHistory.modifierById(request.id).asInstanceOf[PowBlock]
    }

    case request: GetBlockIds => {
      if (request.start < cachedHistory.height) {
        val difference = (cachedHistory.height - request.start).toInt + 1
        val block = cachedHistory.modifierById(cachedHistory.lastBlockIds(cachedHistory.bestBlock(),
          difference).last).asInstanceOf[PowBlock]
        sender() ! cachedHistory.lastBlockIds(block, request.amount).map(x => Base58.encode(x))
      } else {
        sender() ! cachedHistory.lastBlockIds(cachedHistory.bestBlock(), request.amount).map(x => Base58.encode(x))
      }
    }
    case request: GetBlocks => {
      sender() ! cachedHistory.lastBlockIds(cachedHistory.modifierById(request.start).asInstanceOf[PowBlock],
        request.amount).map(x => cachedHistory.modifierById(x))
    }
  }
}

