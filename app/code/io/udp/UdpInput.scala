package code.io.udp

import java.net.InetSocketAddress

import akka.actor.{Terminated, ActorLogging, ActorRef, Actor}
import akka.io.{Udp, IO}
import akka.util.ByteString

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Promise

/**
  * @author eiennohito
  * @since 2015/10/27
  */
class UdpInput(hostname: String, port: Int) extends Actor with ActorLogging {
  import context.system

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(hostname, port))

  var sock: ActorRef = null
  var sink: ActorRef = null

  var queries: List[ActorRef] = Nil
  var boundAddress: InetSocketAddress = null

  override def receive = {
    case Udp.Bound(addr) =>
      sock = sender()
      boundAddress = addr
      log.info(s"server is started on UDP $hostname:$port")
      context.become(ready(sender()))
      for (q <- queries) {
        q ! boundAddress
      }
      queries = Nil
    case UdpInput.Register =>
      sink = sender()
      context.watch(sink)
    case UdpInput.AddressQuery =>
      if (boundAddress != null) {
        sender() ! boundAddress
      } else {
        queries = sender() :: queries
      }
    case Terminated(ar) if ar == sink =>
      sink = null
  }

  val savedInput = new ArrayBuffer[ByteString]()

  def ready(socket: ActorRef): Receive = {
    case UdpInput.Register =>
      sink = sender()
      savedInput.foreach(sink ! _)
      savedInput.clear()
    case UdpInput.AddressQuery =>
      sender() ! boundAddress
    case Udp.Received(data, who) => //data
      if (sink != null) {
        sink ! data
      } else {
        log.warning(s"stashing input: ${savedInput.size} <+ ${data.length}")
        savedInput += data
      }
    case Udp.Unbind => socket ! Udp.Unbind
    case Udp.Unbound =>
      sock = null
      context.stop(self)
    case Terminated(ar) if ar == sink =>
      sink = null
  }

  @throws[Exception](classOf[Exception])
  override def postStop() = {
    if (sock != null) {
      sock ! Udp.Unbind
    }
  }
}

object UdpInput {
  case object Register
  case object AddressQuery
}
