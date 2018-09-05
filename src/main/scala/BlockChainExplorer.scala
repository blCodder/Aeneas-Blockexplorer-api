import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import api.account.circe.Codecs.powBlockEncoder
import block.PowBlock
import commons.SimpleBoxTransaction
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import explorer.ExplorerCodecs.accEncoder
import explorer._
import scorex.core.ModifierId
import scorex.core.network.NetworkController
import scorex.crypto.encode.Base58
import settings.{AeneasSettings, SimpleLocalInterface}

import scala.concurrent.duration.FiniteDuration
import scala.util.Success


class BlockChainExplorer(loadSettings: LoadSettings) extends SimpleBlockChain(loadSettings: LoadSettings)
  with FailFastCirceSupport {

  private val simpleSettings: AeneasSettings = loadSettings.simpleSettings
  private val miner: ActorRef = actorSystem.actorOf(Props(new Actor {
    override def receive = {
      case _ =>
    }
  }))

  override val localInterface: ActorRef =
    actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, miner, simpleSettings.miningSettings)))

  val requestHandler: ActorRef = actorSystem.actorOf(Props(new RequestHandler()))
  val historyUpdater: ActorRef = actorSystem.actorOf(Props(new HistoryUpdater(nodeViewHolderRef, requestHandler)))

  override def run(): Unit = {
    require(settings.network.agentName.length <= ApplicationNameLimit)

    log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
    log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
    log.debug(s"RPC is allowed at ${settings.restApi.bindAddress.toString}")

    implicit val materializer = ActorMaterializer()
    Http().bindAndHandle(combinedRoute, settings.restApi.bindAddress.getAddress.getHostAddress, settings.restApi.bindAddress.getPort)

    implicit val executionContext = actorSystem.dispatcher

    def explorerRoutes: Route = {

      implicit val timeout: Timeout = FiniteDuration(20, "seconds")
      get {
        // returns current height of blockchain
        path("height") {
          onSuccess(requestHandler ? GetHeight) {
            case response: Long =>
              complete(response)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
          path("blocksById" / Segment / IntNumber) { (startId, amount) =>
            Base58.decode(startId) match {
              case Success(idByteArray) => {
                onSuccess(requestHandler ? GetBlocksByID(ModifierId @@ idByteArray, amount)) {
                  case response: Seq[PowBlock] =>
                    complete(response)
                  case _ =>
                    complete(StatusCodes.InternalServerError)
                }
              }
              case _ => {
                complete(StatusCodes.InternalServerError)
              }
            }
          } ~
          //        returns sequence of blocks
          path("blocksByHeight" / LongNumber / IntNumber) { (height, amount) =>
            onSuccess(requestHandler ? GetBlocksByHeight(height, amount)) {
              case response: Seq[PowBlock] => complete(response)
              case _ => complete(StatusCodes.InternalServerError)
            }
          }
        //         returns block by given id
        path("block" / Segment) { request =>
          Base58.decode(request) match {
            case Success(id) =>
              onSuccess(requestHandler ? GetBlock(ModifierId @@ id)) {
                case response: PowBlock =>
                  complete(response)
                case _ =>
                  complete(StatusCodes.InternalServerError)
              }
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        } ~
          //         returns sequence of blockIds
          path("blockIds" / LongNumber / IntNumber) { (startHeight, amount) =>
            onSuccess(requestHandler ? GetBlockIds(startHeight, amount)) {
              case response: Seq[String] =>
                complete(response)
              case _ =>
                complete(StatusCodes.InternalServerError)
            }
          } ~
        // returns info about given account, such id, transactions and blocks which it mined
          path("account" / Segment) { publicKeyBytes =>
            onSuccess(requestHandler ? GetAcc(publicKeyBytes)) {
              case response: AccInfo =>
                complete(response)
              case _ =>
                complete(StatusCodes.InternalServerError)
            }
          } ~
        path("latestTransactions" / IntNumber) { amount =>
          onSuccess(requestHandler ? LastTxs(amount)) {
            case response: Seq[SimpleBoxTransaction] =>
              complete(response)
            case _ =>
              complete(StatusCodes.InternalServerError)
          }
        }
      }
    }

    val bindingFuture = Http().bindAndHandle(explorerRoutes,
      simpleSettings.explorerSettings.bindAddress.getAddress.getHostAddress,
      simpleSettings.explorerSettings.bindAddress.getPort)

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

  object BlockChainExplorer {
    def main(args: Array[String]): Unit = {
      val loadSettings = LoadSettings()
      new BlockChainExplorer(loadSettings).run()
    }
  }

}