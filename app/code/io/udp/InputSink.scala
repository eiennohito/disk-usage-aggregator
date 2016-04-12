package code.io.udp

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{ActorRef, ActorSystem, Props}
import code.io.tcp.TcpInput
import com.google.inject._
import play.api.Configuration

import scala.concurrent.{Future, Promise}

/**
  * @author eiennohito
  * @since 2015/10/27
  */

trait InfoSink {
  def hostname: String
  def port: Int
  def addr: Future[InetSocketAddress]
  def actor: ActorRef
}

case class InfoSinkImpl(hostname: String, port: Int, addr: Future[InetSocketAddress], actor: ActorRef) extends InfoSink


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
    val addr = Promise[InetSocketAddress]
    val aref = asys.actorOf(Props(new TcpInput(hostname, port, addr)))
    new InfoSinkImpl(hostname, port, addr.future, aref)
  }
}
