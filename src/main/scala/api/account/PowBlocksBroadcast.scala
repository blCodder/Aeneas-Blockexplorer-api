package api.account

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{handleWebSocketMessages, path}
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, RunnableGraph, Sink, Source, SourceQueueWithComplete}
import block.PowBlock
import mining.Miner
import scorex.core.api.http.ActorHelper
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings

import scala.concurrent.{ExecutionContext, Future}

/**
  * @author luger. Created on 13.03.18.
  * @version ${VERSION}
  */
class PowBlocksBroadcast (miner:ActorRef, aeneasSettings: AeneasSettings)(
  implicit system: ActorSystem, executionContext: ExecutionContext) extends ScorexLogging with ActorHelper{
  implicit private val materializer: ActorMaterializer = ActorMaterializer()

  val bufferSize = 256

  //if the buffer fills up then this strategy drops the oldest elements
  //upon the arrival of a new element.

  private val overflowStrategy: OverflowStrategy = akka.stream.OverflowStrategy.dropHead

  private val source: Source[String, SourceQueueWithComplete[String]] = Source.queue(
    bufferSize, overflowStrategy
  )

  private val queue: RunnableGraph[(SourceQueueWithComplete[String], Source[String, NotUsed])] =
    source.toMat( BroadcastHub.sink(bufferSize) )(Keep.both)

  val producer: (SourceQueueWithComplete[String], Source[String, NotUsed]) = queue.run()

  producer._2.runWith(Sink.ignore)


  private def broadcast(): Flow[Message, Message, NotUsed] =
    Flow[Message]
      .mapConcat(_ => List.empty[String]) // Ignore any data sent from the client
      .merge(producer._2)
      .map(l => TextMessage(l.toString))

  def publishBlock (pb:PowBlock): Future[QueueOfferResult] = {
    log.debug(s"pb : $pb")
    producer._1.offer(pb.json.noSpaces)
  }

  system.actorOf(Props(new InformatorActor(miner, this)))

  def route: Route = path("broadcast") {
    handleWebSocketMessages(broadcast())
  }
}

class InformatorActor(val miner:ActorRef, powBlocksBroadcast:PowBlocksBroadcast) extends Actor {
  override def preStart(): Unit = {
    super.preStart()
    miner ! Miner.UiInformatorSubscribe
  }

  override def receive: Receive = {
    case pb:PowBlock =>
      powBlocksBroadcast.publishBlock(pb)
  }
}