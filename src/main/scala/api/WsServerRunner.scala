package api

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import api.auth.AuthApi
import settings.AeneasSettings

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.{Failure, Success}

/**
  * @author luger. Created on 09.03.18.
  * @version ${VERSION}
  */
class WsServerRunner(aeneasSettings: AeneasSettings)(implicit system:ActorSystem){
  private val pool = Executors.newCachedThreadPool()
  private implicit val exectutionContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(pool)

  private implicit val materializer = ActorMaterializer()

  val wsApi = new AuthApi(aeneasSettings)

  def run = {
    val addr = aeneasSettings.wsApiSettings.bindAddress
    val bind = Http().bindAndHandle(wsApi.route, addr.getHostName, addr.getPort)
    bind.onComplete {
      case Success(binding) =>
        val localAddress = binding.localAddress
        println(s"Server is listening on ${localAddress.getHostName}:${localAddress.getPort}")
      case Failure(e) =>
        println(s"Binding failed with ${e.getMessage}")
        sys.exit(-1)
    }
  }

}
