package api.account

import settings.AeneasSettings

import scala.annotation.tailrec
import scala.io.Source
import scala.util.Random

/**
  * @author luger. Created on 09.03.18.
  * @version ${VERSION}
  */
class PassPhraseMixingService(aeneasSettings: AeneasSettings) {

  /**
    * generate pseudorandom passphrase for user signup
    * @return seq of passphrase
    */
  def generateRandomPassPhrase ():List[String] = {
    val size = aeneasSettings.seedSettings.passPhraseSize
    val words = Source
      .fromResource(aeneasSettings.seedSettings.file)
      .getLines().toSeq.head.split("\\,")
    randomIntArray(List.empty, size, words.size).map(words(_).toLowerCase)
  }

  @tailrec
  private def randomIntArray (accum:List[Int], size:Int, maxRandom:Int):List[Int] = size match {
    case 0 => accum
    case _ =>
      randomIntArray(randomInt(accum, maxRandom)._1, size - 1, maxRandom)
  }

  @tailrec
  private def randomInt (accum:List[Int], maxRandom:Int):(List[Int], Int) = {
    val r = Random.nextInt(maxRandom)
    if (accum.contains(r)) randomInt(accum, maxRandom)
    else {
      (accum ++ Seq (r), r)
    }
  }
}
