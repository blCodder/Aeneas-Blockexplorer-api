package api

import java.io.{File, FileInputStream}
import java.security.{KeyStore, SecureRandom}
import java.util.concurrent.Executors
import javax.net.ssl.{KeyManagerFactory, SSLContext}

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.stream.ActorMaterializer
import api.account.SignUpApi
import io.iohk.iodb.LSMStore
import settings.AeneasSettings

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.io.Source
import scala.util.{Failure, Success}

/**
  * @author luger. Created on 09.03.18.
  * @version ${VERSION}
  */
class WsServerRunner(wsApi: SignUpApi, aeneasSettings: AeneasSettings)(implicit system:ActorSystem, executionContext: ExecutionContext){
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  def run = {
    val addr = aeneasSettings.wsApiSettings.bindAddress
    val bind = Http().bindAndHandle(wsApi.route, addr.getHostName, addr.getPort)//, connectionContext = ServerContext(aeneasSettings).context) TODO add correct wss support
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

class ServerContext(val aeneasSettings:AeneasSettings){

  val context : ConnectionContext = {
    val password = aeneasSettings.wsApiSettings.keyPwd.toCharArray
    val context = SSLContext.getInstance("TLS")
    val ks = KeyStore.getInstance("PKCS12")
    val keyFileStream = new FileInputStream(aeneasSettings.wsApiSettings.keyPath)

    ks.load(keyFileStream, password)
    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)
    context.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)
    // start up the web server
    ConnectionContext.https(context)
  }
}

object ServerContext {
  def apply(aeneasSettings: AeneasSettings): ServerContext = new ServerContext(aeneasSettings)
}