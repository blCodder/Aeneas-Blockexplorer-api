package history

import java.io.File

import block.{AeneasBlock, PowBlock}
import history.storage.SimpleHistoryStorage
import io.iohk.iodb.LSMStore
import scorex.core.block.BlockValidator
import scorex.core.consensus.History.{HistoryComparisonResult, ModifierIds, ProgressInfo}
import scorex.core.consensus.{History, ModifierSemanticValidity}
import scorex.core.settings.ScorexSettings
import scorex.core.utils.ScorexLogging
import scorex.core.{ModifierId, ModifierTypeId}
import scorex.crypto.encode.Base58
import settings.SimpleMiningSettings
import validators.DifficultyValidator

import scala.annotation.tailrec
import scala.util.{Failure, Try}

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 22.01.18.
  */
class SimpleHistory (val storage: SimpleHistoryStorage,
                     validators : Seq[BlockValidator[AeneasBlock]],
                     settings: SimpleMiningSettings)
  extends History[AeneasBlock, VerySimpleSyncInfo, SimpleHistory] with ScorexLogging {

   override type NVCT = SimpleHistory
   val height = storage.height

   /**
     * @return append modifier to history
     */
   override def append(block: AeneasBlock): Try[(SimpleHistory, History.ProgressInfo[AeneasBlock])] = Try {
      log.info(s"Trying to append block ${Base58.encode(block.id)} to history")
      validators.map(_.validate(block)).foreach {
         case Failure(e) =>
            log.warn(s"Failed to validate block ${Base58.encode(block.id)}")
            throw e
         case _ =>
      }
      val progressInfo: ProgressInfo[AeneasBlock] =
         if (storage.isGenesis(block)) {
            storage.update(block, None, isBest = true)
            log.info(s"History.append postappend length : ${storage.height}")
            ProgressInfo(None, Seq(), Some(block), Seq())
         } else {
            storage.heightOf(block.parentId) match {
               case Some(parentHeight) =>
                  val best = storage.height == storage.parentHeight(b = block)
                  val mod : ProgressInfo[AeneasBlock] = {
                     if (best && storage.isGenesis(block)) {
                        log.info(s"New block incoming : ${Base58.encode(block.id)}")
                        ProgressInfo(None, Seq(), Some(block), Seq())
                     } else ProgressInfo(None, Seq(), None, Seq())
                  }
                  storage.update(block, None, best)
                  mod
               case None =>
                  log.info(s"No parent block ${Base58.encode(block.parentId)}")
                  ProgressInfo(None, Seq(), None, Seq())
            }
         }
      (new SimpleHistory(storage, validators, settings), progressInfo)
   }

   /**
     * Is there's no history, even genesis block
     */
   override def isEmpty: Boolean = height <= 0

   /**
     * Return modifier of type PM with id == modifierId
     *
     * @param modifierId - modifier id to get from history
     * @return
     */
   override def modifierById(modifierId: ModifierId): Option[AeneasBlock] = storage.modifierById(modifierId)

   /**
     * Return semantic validity status of modifier with id == modifierId
     *
     * @param modifierId - modifier id to check
     * @return
     */
   override def isSemanticallyValid(modifierId: ModifierId): ModifierSemanticValidity.Value = {
      modifierById(modifierId).map { _ =>
         ModifierSemanticValidity.Valid
      }.getOrElse(ModifierSemanticValidity.Absent)
   }

   /**
     * Report that modifier is valid from other nodeViewHolder components point of view
     *
     */
   //TODO: to know more about semantic validity
   override def reportSemanticValidity(modifier: AeneasBlock, valid: Boolean, lastApplied: ModifierId) :
   (SimpleHistory, History.ProgressInfo[AeneasBlock]) = {
      this -> History.ProgressInfo(None, Seq(), None, Seq())
   }

   /**
     * @return last block if a history is linear, otherwise it returns last blocks from a blocktree etc
     */
   override def openSurfaceIds(): Seq[ModifierId] = {
      if (isEmpty) Seq(settings.GenesisParentId)
      else Seq(storage.bestPowId)
   }

   def continuationsIds(from: Seq[(ModifierTypeId, ModifierId)], size: Int): Option[ModifierIds] = {
      def inList(m: AeneasBlock): Boolean = idInList(m.id) || storage.isGenesis(m)

      def idInList(id: ModifierId): Boolean = from.exists(f => f._2.deep == id.deep)

      //Look without limit for case difference between nodes is bigger then size
      chainBack(storage.bestBlock, inList) match {
         case Some(chain) if chain.exists(id => idInList(id._2)) => Some(chain.take(size))
         case Some(chain) =>
         log.warn("Found chain without ids form remote")
            None
         case _ => None
      }
   }

   def lastBlockIds(startBlock: AeneasBlock, count: Int): Seq[ModifierId] = {
      chainBack(startBlock, storage.isGenesis, count - 1).get.map(_._2)
   }

   /**
     * Ids of modifiers, that node with info should download and apply to synchronize
     */

   override def continuationIds(info: VerySimpleSyncInfo, size: Int): Option[ModifierIds] = {
      continuationsIds(info.startingPoints, size)
   }

   def parentBlock(m: AeneasBlock): Option[AeneasBlock] = modifierById(m.parentId)

   /**
     * Go back though chain and get block ids until condition until
     * None if parent block is not in chain
     */
   @tailrec
   private def chainBack(m: AeneasBlock,
                         until: AeneasBlock => Boolean,
                         limit: Int = Int.MaxValue,
                         acc: Seq[(ModifierTypeId, ModifierId)] = Seq()): Option[Seq[(ModifierTypeId, ModifierId)]] = {
      val sum: Seq[(ModifierTypeId, ModifierId)] = (PowBlock.ModifierTypeId -> m.id) +: acc

      if (limit <= 0 || until(m)) {
         Some(sum)
      } else {
         parentBlock(m) match {
            case Some(parent) => chainBack(parent, until, limit - 1, sum)
            case _ =>
               log.warn(s"Parent block for ${Base58.encode(m.id)} not found ")
               None
         }
      }
   }
   /**
     * Information about our node synchronization status. Other node should be able to compare it's view with ours by
     * this syncInfo message and calculate modifiers missed by our node.
     *
     * @return
     */
   override def syncInfo: VerySimpleSyncInfo =
      VerySimpleSyncInfo(lastBlocks(VerySimpleSyncInfo.lastBlocksCount, storage.bestBlock).map(_.id))

   /**
     * Whether another's node syncinfo shows that another node is ahead or behind ours
     *
     * @param other other's node sync info
     * @return Equal if nodes have the same history, Younger if another node is behind, Older if a new node is ahead
     */
   override def compare(other: VerySimpleSyncInfo): HistoryComparisonResult.Value = {
      if (other.lastBlocks.isEmpty)
         HistoryComparisonResult.Nonsense

      val compareSize = syncInfo.lastBlocks.zip(other.lastBlocks).count(el => el._1.deep != el._2.deep)
      if (compareSize == 0)
         HistoryComparisonResult.Equal
      else HistoryComparisonResult.Younger
   }

   private def lastBlocks(count: Int, startBlock: PowBlock): Seq[PowBlock] = if (isEmpty) {
      Seq()
   } else {
      @tailrec
      def loop(b: PowBlock, acc: Seq[PowBlock] = Seq()): Seq[PowBlock] = if (acc.length >= count) {
         acc
      } else {
         modifierById(b.parentId) match {
            case Some(parent: PowBlock) => loop(parent, b +: acc)
            case _ => b +: acc
         }
      }

      loop(startBlock)
   }
}

object SimpleHistory extends ScorexLogging {
   def readOrGenerate(settings: ScorexSettings, minerSettings: SimpleMiningSettings): SimpleHistory = {
      readOrGenerate(settings.dataDir, settings.logDir, minerSettings)
   }

   def readOrGenerate(dataDir: File, logDir: File, settings: SimpleMiningSettings): SimpleHistory = {
      log.info(s"SimpleHistory : generation begins at ${dataDir.getAbsolutePath}")
      val iFile = new File(s"${dataDir.getAbsolutePath}/blocks")
      iFile.mkdirs()
      val blockStorage = new LSMStore(iFile, maxJournalEntryCount = 10000)

//      val logger = new FileLogger(logDir.getAbsolutePath + "/tails.data")

      Runtime.getRuntime.addShutdownHook(new Thread() {
         override def run(): Unit = {
            log.info("Closing block storage...")
            blockStorage.close()
         }
      })

      val storage = new SimpleHistoryStorage(blockStorage, settings)
      val validators = Seq(new DifficultyValidator(settings, storage))

      new SimpleHistory(storage, validators, settings)
   }
}
