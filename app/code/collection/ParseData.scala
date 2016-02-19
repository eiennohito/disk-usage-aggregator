package code.collection

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.Charset

import akka.util.ByteString
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.eiennohito.MessageFlags

import scala.collection.mutable.ArrayBuffer

/**
  * @author eiennohito
  * @since 2016/02/18
  */
case class CollectionMessage(
  mark: String,
  events: Seq[CollectionEntry]
)

sealed trait CollectionEntry

final case class DeviceStat(total: Long, used: Long) extends CollectionEntry
final case class DirectoryDown(name: String, id: Long, parent: Long, uid: Int) extends CollectionEntry
final case class DirectoryUp(id: Long, ownSize: Long, ownFiles: Long, recSize: Long, recFiles: Long) extends CollectionEntry
final case class Error(id: Long, message: String) extends CollectionEntry


object MessageParser extends StrictLogging {
  val utf8 = Charset.forName("utf-8")

  implicit private class XString(val x: String) extends AnyVal {
    def xInt: Int = java.lang.Integer.parseInt(x, 36)
    def xLong: Long = java.lang.Long.parseLong(x, 36)
  }

  def parseOverall(objs: Array[String]): DeviceStat = {
    DeviceStat(
      objs(1).xLong,
      objs(2).xLong
    )
  }

  def parseDirDown(objs: Array[String]): DirectoryDown = {
    DirectoryDown(
      objs(1),
      objs(2).xLong,
      objs(3).xLong,
      objs(4).xInt
    )
  }

  def parseDirUp(objs: Array[String]): DirectoryUp = {
    DirectoryUp(
      objs(1).xLong,
      objs(2).xLong,
      objs(3).xLong,
      objs(4).xLong,
      objs(5).xLong
    )
  }

  def parseError(objs: Array[String]): Error = {
    Error(
      objs(1).xLong,
      objs(2)
    )
  }

  def parse(data: ByteString): CollectionMessage = {
    val bis = data.iterator.asInputStream
    val rdr = new BufferedReader(new InputStreamReader(bis, utf8))

    val fistLine = rdr.readLine()

    var line = rdr.readLine()
    val entries = new ArrayBuffer[CollectionEntry]()

    while (line != null) {
      val objs = line.split('\u0000')
      objs(0).charAt(0) match {
        case MessageFlags.ENT_OVERALL => entries += parseOverall(objs)
        case MessageFlags.ENT_DIRECTORY_DOWN => entries += parseDirDown(objs)
        case MessageFlags.ENT_DIRECTORY_UP => entries += parseDirUp(objs)
        case MessageFlags.ENT_ERROR => entries += parseError(objs)
        case _ => logger.warn(s"invalid message flag ${objs(0).charAt(0).toInt}: $line")
      }
      line = rdr.readLine()
    }

    CollectionMessage(fistLine.substring(2), entries)
  }
}
