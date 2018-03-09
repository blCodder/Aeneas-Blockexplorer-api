package api.auth

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import api.Protocol

/**
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
trait NewAccountFlow {
  def newAccountFlowWithPwd (password:String): Flow[String, Protocol.Message, Any]
  def newAccountFlow (): Flow[NewAccountEvents.CallToSignUp, Seq[String], NotUsed]
}

object CreateAccountFlow {

  def apply(passPhraseMixingService: PassPhraseMixingService)(implicit system: ActorSystem):NewAccountFlow = {
    val newAccActor = system.actorOf(Props(new NewAccActor), "newAccount")

    new NewAccountFlow {
      override def newAccountFlowWithPwd(password: String): Flow[String, Protocol.Message, Any] = {
        val in =
          Flow[String].map(NewAccountEvents.ReceivedPassword)
            .to(Sink.actorRef[NewAccountEvents.ReceivedPassword](newAccActor, NewAccountEvents.ReceivedPassword(password)))
        val out: Source[Protocol.Message, NotUsed] =
          Source.actorRef[Protocol.CreateAccount](1, OverflowStrategy.fail)
            .mapMaterializedValue({ _ =>
              newAccActor ! NewAccountEvents.BackupPassPhrase
              NotUsed
            })

        Flow.fromSinkAndSource(in, out)
      }

      override def newAccountFlow(): Flow[NewAccountEvents.CallToSignUp, Seq[String], NotUsed] = {
        val in =
          Flow[NewAccountEvents.CallToSignUp]
              .to(Sink.actorRef[NewAccountEvents.CallToSignUp](newAccActor, NewAccountEvents.CallToSignUp))
        val out:Source[Seq[String], NotUsed] =
          Source.single({
            val phrase = passPhraseMixingService.generateRandomPassPhrase()
            newAccActor ! NewAccountEvents.NewPassPhraseGenerated(phrase)
            phrase
          })
        Flow.fromSinkAndSource(in, out)
      }
    }
  }
}
