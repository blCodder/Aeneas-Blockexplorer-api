package api.account

import akka.NotUsed
import akka.actor.{ActorSystem, Props}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import api.Protocol
import api.account.CreateAccountFlow.UserKey
import api.account.NewAccountEvents.GeneratedConfirmationPassPhrase
import io.iohk.iodb.LSMStore

/**
  * @author luger. Created on 07.03.18.
  * @version ${VERSION}
  */
trait NewAccountFlow {

  def newAccountFlowWithPwd (password:String): Flow[String, Protocol.Message, Any]
  def newAccountFlow (): Flow[NotUsed, Seq[String], NotUsed]
  def savedPassPhraseFlow ():Flow[NewAccountEvents.SavedPassPhrase, Seq[String], NotUsed]
  def confirmPassPhraseFlow (confirmationPassPhrase: List[String]):Flow[NewAccountEvents.ConfirmPassPhrase, Option[UserKey], NotUsed]
}

object CreateAccountFlow {
  type Seed = String
  type PrivateKey = String
  case class UserKey (seed:Seed, key:PrivateKey)

  def apply(passPhraseMixingService: PassPhraseMixingService, store:LSMStore)(implicit system: ActorSystem):NewAccountFlow = {
    val newAccActor = system.actorOf(Props(new NewAccActor(store)), "newAccount")

    new NewAccountFlow {
      override def newAccountFlowWithPwd(password: String): Flow[String, Protocol.Message, Any] = {
        val in =
          Flow[String].map(NewAccountEvents.ReceivedPassword)
            .to(Sink.actorRef[NewAccountEvents.ReceivedPassword](newAccActor, NewAccountEvents.ReceivedPassword(password)))
        val out: Source[Protocol.Message, NotUsed] =
          Source.actorRef[Protocol.CreateAccount](1, OverflowStrategy.fail)
            .mapMaterializedValue({ _ =>
              NotUsed
            })

        Flow.fromSinkAndSource(in, out)
      }

      override def newAccountFlow(): Flow[NotUsed, Seq[String], NotUsed] = {
        val phrase = passPhraseMixingService.generateRandomPassPhrase()
        val in =
          Flow[NotUsed]
              .to(Sink.actorRef[NotUsed](newAccActor, NewAccountEvents.CallToSignUp(phrase)))
        val out:Source[Seq[String], NotUsed] =
          Source.single(phrase)
        Flow.fromSinkAndSource(in, out)
      }

      override def savedPassPhraseFlow(): Flow[NewAccountEvents.SavedPassPhrase, Seq[String], NotUsed] = {
        val in =
          Flow[NewAccountEvents.SavedPassPhrase].to(
            Sink.actorRef[NewAccountEvents.SavedPassPhrase](newAccActor, NewAccountEvents.SavedPassPhrase))
        val out:Source[Seq[String], NotUsed] = Source.actorRef[NewAccountEvents.GeneratedConfirmationPassPhrase](256, OverflowStrategy.fail)
          .mapMaterializedValue{outActor =>
            newAccActor ! NewAccountEvents.ConfirmationOutActorRef(outActor)
            NotUsed
          }.map(confirmationPhrase => confirmationPhrase.passPhrase)

        Flow.fromSinkAndSource(in, out)
      }

      override def confirmPassPhraseFlow(confirmationPassPhrase: List[String]):Flow[NewAccountEvents.ConfirmPassPhrase, Option[UserKey], NotUsed] = {
        val in =
          Flow[NewAccountEvents.ConfirmPassPhrase].to(
            Sink.actorRef[NewAccountEvents.ConfirmPassPhrase](newAccActor, NewAccountEvents.ConfirmPassPhrase(confirmationPassPhrase)))

        val out:Source[Option[UserKey], NotUsed] = Source.actorRef[NewAccountEvents.GeneratedSeed](256, OverflowStrategy.fail)
          .mapMaterializedValue{outActor =>
            newAccActor ! NewAccountEvents.SeedOutActorRef(outActor)
            NotUsed
          }.map(seed => seed.userKey)

        Flow.fromSinkAndSource(in, out)
      }
    }
  }
}
