package settings

import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import scorex.core.ModifierId
import scorex.core.settings._
import scorex.core.utils.ScorexLogging

import scala.concurrent.duration._


/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 22.01.18.
  * @param offlineGen enables offline block generation
  * @param blockGenDelay defines minimal generation delay for each block.
  * @param targetBlockDelay defines linear addition to block generation difficulty
  *                      after the recalculation.
  * @param initialDifficulty initial difficulty of block generation.
  */
case class SimpleMiningSettings(offlineGen : Boolean,
                                blockGenDelay: FiniteDuration,
                                targetBlockDelay : FiniteDuration,
                                initialDifficulty : BigInt) {
   lazy val MaxTarget = BigInt(1, Array.fill(36)(Byte.MinValue))
   lazy val GenesisParentId = ModifierId @@ Array.fill(32)(1: Byte)
}


case class AeneasSettings(scorexSettings: ScorexSettings, miningSettings: SimpleMiningSettings, wsApiSettings:WsApiSettings, staticFilesSettings: StaticFilesSettings, seedSettings: SeedSettings)

object AeneasSettings extends SettingsReaders {
   def read(): AeneasSettings = {
      val config = ConfigFactory.load()
      if (!config.hasPath("scorex")) {
         throw new Error("Malformed configuration file was provided! Aborting!")//TODO remove throw Exception with Try
      }
      fromConfig(config)
   }

   implicit val networkSettingsValueReader: ValueReader[AeneasSettings] =
      (cfg: Config, path: String) => fromConfig(cfg.getConfig(path))

   private def fromConfig(config: Config): AeneasSettings = {
      val miningSettings = config.as[SimpleMiningSettings]("scorex.miner")
      val scorexSettings = config.as[ScorexSettings]("scorex")
      val wsApiSettings  = config.as[WsApiSettings]("scorex.api")
      val staticFilesSettings  = config.as[StaticFilesSettings]("scorex.static")
      val seedSettings  = config.as[SeedSettings]("scorex.seedGen")
      AeneasSettings(scorexSettings, miningSettings, wsApiSettings, staticFilesSettings, seedSettings)
   }
}