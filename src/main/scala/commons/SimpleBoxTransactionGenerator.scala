package commons

import java.util.concurrent.atomic.AtomicBoolean

import scorex.core.utils.ScorexLogging
import wallet.AeneasWallet

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Random, Try}

/**
  * Generator of SimpleBoxTransaction inside a wallet
  */
class SimpleBoxTransactionGenerator(wallet: AeneasWallet) extends ScorexLogging {

   val flag = new AtomicBoolean(false)
   val defaultDuration : FiniteDuration = 10.millisecond

   private val ex: ArrayBuffer[Array[Byte]] = ArrayBuffer()
   var txPool: ArrayBuffer[SimpleBoxTransaction] = ArrayBuffer()

   final def generate(wallet: AeneasWallet): Try[SimpleBoxTransaction] = {
      if (Random.nextInt(100) == 1)
         ex.clear()
      val pubkeys = wallet.publicKeys.toSeq
      if (pubkeys.lengthCompare(10) < 0)
         wallet.generateNewSecret()

      if (pubkeys.nonEmpty) {
         val recipients = scala.util.Random.shuffle(pubkeys).take(Random.nextInt(pubkeys.size))
            .map(r => (r, Value @@ Random.nextInt(100).toLong))

         val tx = SimpleBoxTransaction.create(wallet, recipients, Random.nextInt(100), ex)
         tx.map(t => t.boxIdsToOpen.foreach(id => ex += id))
         tx
      }
      else generate(wallet)
   }

   @tailrec
   final def generatingProcess(duration: FiniteDuration = defaultDuration, count : Int = 0) : ArrayBuffer[SimpleBoxTransaction] = {
      if (count == 100) txPool
      else {
         val txGenerationTrying = generate(wallet)
         if (txGenerationTrying.isSuccess)
            txPool.append(txGenerationTrying.get)

         Thread.sleep(duration.toMillis)
         generatingProcess(duration, count + 1)
      }
   }

   @tailrec
   final def syncGeneratingProcess(count : Int = 100) : ArrayBuffer[SimpleBoxTransaction] = {
      if (count == 0) txPool
      else {
         val txGenerationTrying = generate(wallet)
         if (txGenerationTrying.isSuccess)
            txPool.append(txGenerationTrying.get)
         syncGeneratingProcess(count - 1)
      }
   }
}