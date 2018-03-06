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

package network

import akka.actor.{Actor, ActorRef}
import network.BlockchainDownloader.Mock
import scorex.core.utils.ScorexLogging
import viewholder.AeneasNodeViewHolder
import viewholder.AeneasNodeViewHolder.AeneasSubscribe

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 06.03.18.
  */
class BlockchainDownloader(networkController: ActorRef,
                           viewHolderRef : ActorRef) extends Actor with ScorexLogging {

   override def preStart(): Unit = {
      val aeneasEvents = Seq(
         AeneasNodeViewHolder.NodeViewEvent.DownloadBlock,
         AeneasNodeViewHolder.NodeViewEvent.ReceiveBlock
      )
      viewHolderRef ! AeneasSubscribe(aeneasEvents)

      //TODO: Mocked object for test launching. Redo in future.
      self ! Mock
   }

   def onDownloadBlock : Receive = {
      case Mock =>
   }

   override def receive: Receive = {
      onDownloadBlock
   }
}

object BlockchainDownloader {
   case object Mock
}
