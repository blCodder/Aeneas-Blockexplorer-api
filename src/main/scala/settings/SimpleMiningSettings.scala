package settings

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import scorex.core.ModifierId
import scorex.core.settings.ScorexSettings.readConfigFromPath
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

case class SimpleSettings(scorexSettings: ScorexSettings, miningSettings: SimpleMiningSettings)

object SimpleSettings extends ScorexLogging with SettingsReaders {
   def read(userConfigPath: Option[String]): SimpleSettings = {
      fromConfig(readConfigFromPath(userConfigPath, "scorex"))
   }

   implicit val networkSettingsValueReader: ValueReader[SimpleSettings] =
      (cfg: Config, path: String) => fromConfig(cfg.getConfig(path))

   private def fromConfig(config: Config): SimpleSettings = {
      val miningSettings = config.as[SimpleMiningSettings]("scorex.miner")
      log.info(s"Mining settings : ${miningSettings.toString}")
      val scorexSettings = config.as[ScorexSettings]("scorex")

      SimpleSettings(scorexSettings, miningSettings)
   }
}