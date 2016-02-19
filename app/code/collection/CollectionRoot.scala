package code.collection

import java.util.Base64

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.ByteString
import code.io.udp.UdpInput

/**
  * @author eiennohito
  * @since 2016/02/19
  */
class CollectionRoot(input: ActorRef, updater: ActorRef) extends Actor with ActorLogging {
  @throws[Exception](classOf[Exception])
  override def preStart() = {
    super.preStart()
    input ! UdpInput.Register
  }

  val collectors = new scala.collection.mutable.HashMap[String, ActorRef]()

  def processInput(bs: ByteString): Unit = {
    try {
      val message = MessageParser.parse(bs)
      collectors.get(message.mark) match {
        case Some(c) => c ! message
        case None =>
          log.warning(s"no collector registered for mark: {}", message.mark)
      }
    } catch {
      case e: Exception => log.error(e, "when processing message: " + Base64.getEncoder.encode(bs.asByteBuffer))
    }
  }

  override def receive = {
    case Collection.MakeCollector(mark, prefix) =>
      val props = Props(new Collector(mark, prefix, updater))
      val child = context.actorOf(props, mark)
      collectors.put(mark, child)
      sender() ! child
    case Collection.CollectionFinished(mark) =>
      collectors.remove(mark).foreach(context.stop)
    case bs: ByteString =>
      processInput(bs)
  }
}
