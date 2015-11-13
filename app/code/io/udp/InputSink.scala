package code.io.udp

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{ActorRef, ActorSystem, Props}
import com.google.inject._
import play.api.Configuration

import scala.concurrent.{Await, Promise}

/**
  * @author eiennohito
  * @since 2015/10/27
  */

trait InfoSink {
  def hostname: String
  def port: Int
  def addr: InetSocketAddress
  def actor: ActorRef
}


case class InfoSinkImpl(hostname: String, port: Int, addr: InetSocketAddress, actor: ActorRef) extends InfoSink


class InfoSinkModule extends Module {
  override def configure(binder: Binder) = {
    binder.bind(classOf[InfoSink]).to(classOf[InfoSinkImpl]).asEagerSingleton()
  }

  @Provides
  @Singleton
  def infoSink(
    asys: ActorSystem,
    cfg: Configuration
  ): InfoSinkImpl = {
    val port = cfg.getInt("my.port").getOrElse(0)
    val hostname = cfg.getString("my.hostname").getOrElse(InetAddress.getLocalHost.getHostAddress)
    val addrP = Promise[InetSocketAddress]()
    val aref = asys.actorOf(Props(new UdpInput(hostname, port, addrP)))
    import scala.concurrent.duration._
    val addr = Await.result(addrP.future, 10.seconds)
    new InfoSinkImpl(hostname, port, addr, aref)
  }
}
