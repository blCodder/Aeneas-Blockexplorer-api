package network

import java.io.File

import com.dimafeng.testcontainers.{DockerComposeContainer, ForAllTestContainer}
import org.scalatest.FunSuite

/**
  * @author luger. Created on 09.04.18.
  * @version ${VERSION}
  */
class BlockchainDownloaderTest extends FunSuite  with ForAllTestContainer{

  override val container = DockerComposeContainer(new File("src/test/resources/docker-compose.yml"), exposedService = Map("redis_1" -> 6379))

  test("testApplyBlocksToBlockchain") {

  }

}
