package org.eiennohito

import java.nio.ByteBuffer

import akka.util.ByteString
import code.collection.{Error, MessageParser}
import org.scalatest.{FreeSpec, Inside, LoneElement, Matchers}

/**
  * @author eiennohito
  * @since 2016/02/18
  */
class MessageFormatterTest extends FreeSpec with Matchers with LoneElement with Inside {

  class DumbSender(sz: Int) extends MessageSender {
    val buffer = ByteBuffer.allocate(sz)
    override def sendBuffer(buf: ByteBuffer) = {
      buffer.clear()
      buffer.put(buf)
      buffer.flip()
    }
  }

  "MessageFormatter" - {
    "formatPositive does work" - {

      def checkFormat(i: Long) = {
        val sndr = new DumbSender(500)
        sndr.buffer.clear()
        val printed = MessageFormatter.formatPositive(sndr.buffer, i)
        printed should be >= 1
        val srep = new String(sndr.buffer.array(), 0, printed)
        srep shouldBe i.toString
      }

      "0" in { checkFormat(0) }
      "10" in { checkFormat(10) }
      "152" in { checkFormat(152) }
      "512516213" in { checkFormat(512516213L) }
    }

    "appendError" - {
      "small line" in {
        val sndr = new DumbSender(500)
        val fmtr = new MessageFormatter(sndr, 100, 500, "=")
        fmtr.appendError(0, "a message")
        fmtr.flush()
        val parsed = MessageParser.parse(ByteString(sndr.buffer))
        parsed.mark shouldBe "="
        inside(parsed.events.loneElement) {
          case x: Error => x.message shouldBe "a message"
        }
      }
    }
  }
}
