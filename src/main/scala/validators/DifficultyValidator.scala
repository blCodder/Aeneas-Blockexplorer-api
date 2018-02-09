package validators

import block.AeneasBlock
import history.storage.SimpleHistoryStorage
import scorex.core.block.BlockValidator
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import settings.SimpleMiningSettings

import scala.util.Try
;

/**
 * @author is Alex Syrotenko (@flystyle)
 * Created on 25.01.18.
 */
class DifficultyValidator(settings: SimpleMiningSettings, storage: SimpleHistoryStorage)
				extends BlockValidator[AeneasBlock] with ScorexLogging {

   override def validate(block: AeneasBlock): Try[Unit] = Try {
      require(DifficultyValidator.correctWork(block, settings, storage),
      s"Work done is incorrect for block ${Base58.encode(block.id)} " +
      s"and difficulty ${storage.getPoWDifficulty(Some(block.parentId))}")
   }

}

object DifficultyValidator extends ScorexLogging {
   def correctWork(b : AeneasBlock, settings: SimpleMiningSettings, storage : SimpleHistoryStorage) : Boolean = {
      val target = settings.MaxTarget / storage.getPoWDifficulty(Some(b.parentId))
      val dig = BigInt(1, b.id)
      log.info(s"Difficulty Validator diff computed: ${target-dig} and validation result is is ${dig < target}")
      dig < target
   }
}
