package explorer

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import block.PowBlock
import history.storage.AeneasHistoryStorage
import history.{AeneasHistory, TempDbHelper}
import io.iohk.iodb.LSMStore
import org.scalatest.{FunSuite, Matchers}
import scorex.core.ModifierId
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.encode.Base58
import scorex.crypto.hash.Digest32
import settings.AeneasSettings
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import api.account.circe.Codecs.powBlockEncoder
import commons.SimpleBoxTransactionGenerator
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.syntax._
import scorex.core.idsToString
import scorex.core.utils.ScorexLogging
import wallet.AeneasWallet

class RoutesTest extends FunSuite with Matchers with FailFastCirceSupport with ScalatestRouteTest with ScorexLogging {
  test("Explorer : GET block request") {
    val settings = AeneasSettings.read()
    // we need to create custom history storage because validators fails our blocks appending.
    val testFile = TempDbHelper.mkdir
    val storage = new AeneasHistoryStorage(new LSMStore(testFile, maxJournalEntryCount = 100), settings.miningSettings)
    var history = new AeneasHistory(storage, Seq(), settings.miningSettings)
    val genesisAccount = PrivateKey25519Companion.generateKeys("genesisBlock".getBytes)
    implicit val system = ActorSystem("testActorSystem")
    implicit val materializer = ActorMaterializer()
    //    implicit val executionContext = actorSystem.dispatcher

    val block1 = new PowBlock(
      settings.miningSettings.GenesisParentId,
      System.currentTimeMillis(),
      1 << 3,
      Digest32 @@ Array.fill(32)(1: Byte),
      genesisAccount._2,
      Seq()
    )

    val block2 = new PowBlock(
      ModifierId @@ block1.id,
      System.currentTimeMillis(),
      2 << 3,
      Digest32 @@ Array.fill(32)(1: Byte),
      genesisAccount._2,
      Seq()
    )
    log.debug(s"created block Id: ${Base58.encode(block2.id)}")

    val block3 = new PowBlock(
      ModifierId @@ block2.id,
      System.currentTimeMillis(),
      3 << 3,
      Digest32 @@ Array.fill(32)(1: Byte),
      genesisAccount._2,
      Seq()
    )

    val block4 = new PowBlock(
      ModifierId @@ block3.id,
      System.currentTimeMillis(),
      4 << 3,
      Digest32 @@ Array.fill(32)(1: Byte),
      genesisAccount._2,
      Seq()
    )

    val block5 = new PowBlock(
      ModifierId @@ block4.id,
      System.currentTimeMillis(),
      5 << 3,
      Digest32 @@ Array.fill(32)(1: Byte),
      genesisAccount._2,
      Seq()
    )

    val generator = new SimpleBoxTransactionGenerator(AeneasWallet.readOrGenerate(history, settings.scorexSettings))
    val pool = generator.syncGeneratingProcess(10).toSeq


    history = history.append(block1).get._1
      .append(block2).get._1
      .append(block3).get._1
      .append(block4).get._1
      .append(block5).get._1

    val accInfo = AccInfo(block1.generatorProposition.toString, pool,
      history.lastBlockIds(history.bestBlock(), 5))

    val requestHandler: ActorRef = system.actorOf(Props(new RequestHandler()))
    requestHandler ! history

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
          path("blocksByHeight" / LongNumber / IntNumber) { (height, amount) =>
            onSuccess(requestHandler ? GetBlocksByHeight(height, amount)) {
              case response: Seq[PowBlock] => complete(response)
              case _ => complete(StatusCodes.InternalServerError)
            }
          } ~
          path("block" / Segment) { (request) =>
            log.debug(s"requesed BlockId: $request")
            Base58.decode(request) match {
              case Success(id) =>
                log.debug(s"decoded blockId: ${id}")
                log.debug(s"required blockId: ${block2.id}")
                onSuccess(requestHandler ? GetBlock((ModifierId @@ id))) {
                  case response: PowBlock =>
                    complete(response)
                  case _ =>
                    complete(StatusCodes.InternalServerError)
                }
            }
          }
      }
    }

    Get("/height") ~> explorerRoutes ~> check {
      responseAs[Long] shouldEqual 5
    }
    Get(s"/block/${Base58.encode(block2.id)}") ~> explorerRoutes ~> check {
      responseAs[Json] shouldBe block2.json
    }
    Get(s"/blocksByHeight/5/4") ~> explorerRoutes ~> check {
      responseAs[Json] shouldBe Seq[PowBlock](block2, block3, block4, block5).asJson
    }
    Get(s"/blocksById/${Base58.encode(block5.id)}/4") ~> explorerRoutes ~> check {
      responseAs[Json] shouldBe Seq[PowBlock](block2, block3, block4, block5).asJson
    }

    accInfo.json shouldBe accInfo.json

    TempDbHelper.del(testFile)
  }
}


