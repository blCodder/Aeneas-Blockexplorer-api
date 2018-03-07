/*
 * Copyright 2018, Aeneas Platform.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package network.messagespec

import block.{PowBlock, PowBlockCompanion}
import scorex.core.network.message.Message.MessageCode
import scorex.core.network.message.MessageSpec

import scala.annotation.tailrec
import scala.util.Try

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 06.03.18.*
  */

/**
  * * Message specification for `PowBlock`
  */
class PoWBlockMessageSpec extends MessageSpec[PowBlock]{
   override val messageCode: MessageCode = 102.byteValue()
   override val messageName: String = "PowBlock"

   override def toBytes(obj: PowBlock): Array[Byte] = PowBlockCompanion.toBytes(obj)

   override def parseBytes(bytes: Array[Byte]): Try[PowBlock] = PowBlockCompanion.parseBytes(bytes)
}

/**
  * * Message specification for sequence of `PowBlock`
  */
class PoWBlocksMessageSpec extends MessageSpec[Seq[PowBlock]] {
   override val messageCode: MessageCode = 101.byteValue()
   override val messageName: String = "PowBlocks"

   override def toBytes(obj: Seq[PowBlock]): Array[MessageCode] = {
      obj.foldLeft(Array(obj.size.toByte)) {
         (acc, el) => acc ++ PowBlockCompanion.toBytes(el)
      }
   }

   override def parseBytes(bytes: Array[MessageCode]): Try[Seq[PowBlock]] = Try {
      // byte.head consists count of objects in bytearray, other is serialized objects.
      val byteSize = bytes.tail.length / bytes.head
      val blocks = Seq[PowBlock]()
      var offset = 1
      while (offset < bytes.length - 1) {
         blocks :+ PowBlockCompanion.parseBytes(bytes.slice(offset, offset + byteSize)).getOrElse()
         offset = offset + byteSize
      }
      blocks
   }
}


class FullBlockChainRequestSpec extends MessageSpec[String] {
   override val messageCode: MessageCode = 99.byteValue()
   override val messageName: String = "Blockchain"

   override def toBytes(obj: String): Array[MessageCode] = obj.getBytes

   override def parseBytes(bytes: Array[MessageCode]): Try[String] = Try { String.valueOf(bytes) }
}

class EndDownloadSpec extends MessageSpec[String] {
   override val messageCode: MessageCode = 199.byteValue()
   override val messageName: String = "EndDownload"

   override def toBytes(obj: String): Array[MessageCode] = obj.getBytes

   override def parseBytes(bytes: Array[MessageCode]): Try[String] = Try { String.valueOf(bytes) }
}

