package code.collection

import java.util.Base64

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.ByteString
import code.io.Input
import code.io.udp.UdpInput
import code.tracing.TrackingApi

/**
  * @author eiennohito
  * @since 2016/02/19
  */
class CollectionRoot(input: ActorRef, updater: ActorRef) extends Actor with ActorLogging with TrackingApi {
  @throws[Exception](classOf[Exception])
  override def preStart() = {
    super.preStart()
    input ! Input.Register
  }

  val collectors = new scala.collection.mutable.HashMap[String, ActorRef]()

  def processInput(bs: ByteString): Unit = {
    try {
      if (bs.length < 10) {
        log.warning(s"message is too small: ${bs.utf8String}")
      }
      val message = MessageParser.parse(bs.drop(4))
      collectors.get(message.mark) match {
        case Some(c) =>
          trace(message)
          c ! message
        case None =>
          log.warning(s"no collector registered for mark: {}", message.mark)
      }
    } catch {
      case e: Exception => log.error(e, "when processing message: " + Base64.getEncoder.encode(bs.asByteBuffer))
    }
  }

  override def receive = {
    case msg @ Collection.MakeCollector(mark, prefix) =>
      val props = Props(new Collector(mark, prefix, updater))
      val actorName = s"$mark-${prefix.key}"
      val child = context.actorOf(props, actorName)
      collectors.put(mark, child)
      sender() ! child
      trace(msg)
    case msg @ Collection.CollectionFinished(mark) =>
      collectors.remove(mark).foreach(context.stop)
      trace(msg)
    case bs: ByteString =>
      processInput(bs)
  }
}
