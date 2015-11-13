package code.io.udp

import java.net.InetSocketAddress

import akka.actor.{ActorLogging, ActorRef, Actor}
import akka.io.{Udp, IO}

import scala.concurrent.Promise

/**
  * @author eiennohito
  * @since 2015/10/27
  */
class UdpInput(hostname: String, port: Int, actual: Promise[InetSocketAddress]) extends Actor with ActorLogging {
  import context.system

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(hostname, port))

  var sock: ActorRef = null

  override def receive = {
    case Udp.Bound(addr) =>
      sock = sender()
      actual.success(addr)
      context.become(ready(sender()))
  }

  def ready(socket: ActorRef): Receive = {
    case Udp.Received(data, who) => //data
      log.info("msg from {},\n{}", who, data.decodeString("utf-8"))
    case Udp.Unbind => socket ! Udp.Unbind
    case Udp.Unbound =>
      sock = null
      context.stop(self)
  }

  @throws[Exception](classOf[Exception])
  override def postStop() = {
    if (sock != null) {
      sock ! Udp.Unbind
    }
  }
}
