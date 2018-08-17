import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import api.account.circe.Codecs.powBlockEncoder
import block.PowBlock
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import history.AeneasHistory
import scorex.core.ModifierId
import scorex.core.network.NetworkController
import scorex.crypto.encode.Base58
import settings.{AeneasSettings, SimpleLocalInterface}
import viewholder.AeneasNodeViewHolder
import viewholder.AeneasNodeViewHolder.AeneasSubscribe

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}



class BlockChainExplorer(loadSettings: LoadSettings) extends SimpleBlockChain(loadSettings: LoadSettings) with FailFastCirceSupport {

  private val simpleSettings: AeneasSettings = loadSettings.simpleSettings
  private val miner: ActorRef = actorSystem.actorOf(Props(new Actor {
    override def receive = {
      case _ =>
    }
  }))

  override val localInterface: ActorRef =
    actorSystem.actorOf(Props(new SimpleLocalInterface(nodeViewHolderRef, miner, simpleSettings.miningSettings)))

  val requestHandler: ActorRef = actorSystem.actorOf(Props(new RequestHandler(nodeViewHolderRef)))

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
          path("height") {
            onSuccess(requestHandler ? GetHeight) {
              case response: Long =>
                complete(response)
              case _ =>
                complete(StatusCodes.InternalServerError)
            }
          } ~
            path("block" / Segment) { (request) =>
              Base58.decode(request) match {
                case Success(id) =>
                  onSuccess(requestHandler ? GetBlock((ModifierId @@ id))) {
                    case response: PowBlock =>
                      complete(response)
                    case _ =>
                      complete(StatusCodes.InternalServerError)
                  }
                case _ =>
                  complete(StatusCodes.InternalServerError)
              }
            } ~
            path("blockIds" / LongNumber / IntNumber) { (start, amount) =>
              onSuccess(requestHandler ? GetBlockIds(start, amount)) {
                case response: Seq[String] =>
                  complete(response)
                case _ =>
                  complete(StatusCodes.InternalServerError)
              }
            } ~
            path("blocks" / Segment / IntNumber) { (start, amount) =>
              Base58.decode(start) match {
                case Success(idByteArray) => {
                  onSuccess(requestHandler ? GetBlocks(ModifierId @@ idByteArray, amount)) {
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

  def sendResponse[T](eventualResult: Future[T])(implicit marshaller: T ⇒ ToResponseMarshallable): Route = {
    onComplete(eventualResult) {
      case Success(result) ⇒
        complete(result)
      case Failure(e) ⇒
        log.error(s"Error: ${e.toString}")
        complete(ToResponseMarshallable(InternalServerError))
    }
  }

  object BlockChainExplorer {
    def main(args: Array[String]): Unit = {
      val loadSettings = LoadSettings()
      new BlockChainExplorer(loadSettings).run()
    }
  }

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
        request.amount).map(x => cachedHistory.modifierById(x).asInstanceOf[PowBlock])
    }
  }
}


