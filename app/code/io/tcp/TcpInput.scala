package code.io.tcp

import java.net.InetSocketAddress
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.stream.actor.{ActorSubscriber, ActorSubscriberMessage, WatermarkRequestStrategy}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Framing, Sink, Source, Tcp}
import akka.util.ByteString
import code.io.Input

import scala.concurrent.{ExecutionContext, Promise}

/**
  * @author eiennohito
  * @since 2016/04/12
  */
class TcpInput(hostname: String, port: Int, addr: Promise[InetSocketAddress]) extends Actor {
  private val materializer = ActorMaterializer(namePrefix = Some("tcpio"))(this.context)
  implicit val ec: ExecutionContext = context.system.dispatcher

  def handler(sink: ActorRef) = {
    val input = Sink.actorSubscriber(Props(new TcpHandler(sink)))
    val output = Source.actorRef(10, OverflowStrategy.fail)
    val flow = Flow.fromSinkAndSourceMat(input, output)((a1, a2) => a1 ! TcpHandler.Output(a2))

    val processing = Flow[ByteString]
      .via(Framing.lengthField(fieldLength = 4, fieldOffset = 0, maximumFrameLength = 50000, byteOrder = ByteOrder.BIG_ENDIAN))
      .via(flow).buffer(1000, OverflowStrategy.backpressure)

    processing
  }

  private var binding: Tcp.ServerBinding = null

  import akka.pattern.pipe

  override def receive = {
    case Input.Register =>
      val sink = sender()
      val binding = Tcp(context.system).bindAndHandle(handler(sink), hostname, port)(materializer)
      binding.pipeTo(self)
      addr.completeWith(binding.map(_.localAddress))
    case sb: Tcp.ServerBinding =>
      this.binding = sb
    case Input.AddressQuery =>
      if (binding == null) {
        throw new Exception("can't get an address")
      } else {
        sender() ! binding.localAddress
      }
  }
}

class TcpHandler(sink: ActorRef) extends ActorSubscriber {
  var output: ActorRef = null

  val counter = new AtomicInteger(0)
  val bbuf = ByteBuffer.allocate(4)

  override def receive = {
    case ActorSubscriberMessage.OnNext(bs: ByteString) =>
      sink ! bs
      if (output != null) {
        val cnt = counter.getAndIncrement()
        bbuf.clear()
        bbuf.putInt(cnt)
        bbuf.flip()
        //output ! ByteString.apply(bbuf)
      }
    case ActorSubscriberMessage.OnComplete =>
      if (output != null) {
        context.stop(output)
      }
      context.stop(self)
    case TcpHandler.Output(ar) =>
      output = ar
  }

  override protected def requestStrategy = WatermarkRequestStrategy(20, 10)
}

object TcpHandler {
  case class Output(out: ActorRef)
}
