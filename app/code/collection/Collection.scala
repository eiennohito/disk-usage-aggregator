package code.collection

import java.io.{BufferedReader, InputStreamReader}
import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.util.{ByteString, Timeout}
import code.io.udp.{InfoSink, UdpInput}
import com.google.inject.{Binder, Module, Provides, Singleton}
import com.mongodb.casbah.commons.MongoDBObject
import com.novus.salat.Context
import com.novus.salat.dao.{SalatDAO, DAO}

import scala.collection.mutable
import scala.concurrent.Await

/**
  * @author eiennohito
  * @since 2015/11/16
  */
case class CollectionEvent(mark: String, path: String, uid: Int, size: Long)

class CollectionRoot(input: ActorRef, ded: DirectoryEntryDao) extends Actor with ActorLogging {

  @throws[Exception](classOf[Exception])
  override def preStart() = {
    super.preStart()
    input ! UdpInput.Register
  }

  val collectors = new mutable.HashMap[String, ActorRef]()

  def processInput(bs: ByteString): Unit = {
    val is = bs.iterator.asInputStream
    val rdr = new BufferedReader(new InputStreamReader(is, "utf-8"), 2048)
    var line = rdr.readLine()
    while (line != null) {
      val parts = line.split('\u0000')
      if (parts.length == 4) {
        try {
          val obj = CollectionEvent(
            parts(0),
            parts(1),
            parts(2).toInt,
            parts(3).toLong
          )
          collectors.get(obj.mark) match {
            case Some(a) => a ! obj
            case None =>
              log.warning(s"no actor is registered for mark: ${obj.mark}, ev: $line")
          }
        } catch {
          case e: Exception =>
            log.error(s"could not process event: $line")
        }
      }
      line = rdr.readLine()
    }
  }

  override def receive = {
    case Collection.MakeCollector(mark, prefix) =>
      val props = Props(new Collector(mark, prefix, ded))
      val child = context.actorOf(props, mark)
      collectors.put(mark, child)
      sender() ! child
    case Collection.CollectionFinished(mark) =>
      collectors.remove(mark).foreach(context.stop)
    case bs: ByteString =>
      processInput(bs)
  }
}

class Collector(mark: String, ct: CollectionTarget, ded: DirectoryEntryDao) extends Actor with ActorLogging {

  val idx = ct.target.pos

  ded.dropKey(ct.key)

  override def receive = {
    case CollectionEvent(_, path, uid, size) =>
      if (idx != -1) {
        val items = path.split("/")
        if (items.length == idx + 1) {
          val item = items(idx)
          val entry = DirectoryEntry(
            ded.makeId(),
            None,
            ct.key,
            item,
            size
          )
          ded.save(entry)
        }
      }
    case Collection.CollectionFinished =>
      context.parent ! Collection.CollectionFinished
  }
}


object Collection {
  case class MakeCollector(mark: String, target: CollectionTarget)
  case class CollectionFinished(mark: String)
}

trait Collectors {
  def makeCollector(req: Collection.MakeCollector): ActorRef
}

import com.novus.salat.annotations._

case class DirectoryEntry(@Key("_id") id: Long, parent: Option[Long], key: String, name: String, size: Long)

case class ByKey(@Key("_id")key: String, total: Long)

trait DirectoryEntryDao extends DAO[DirectoryEntry, Long] {
  def forUser(name: String) = {
    val cmd = MongoDBObject(
      "name" -> name
    )
    val cursor = this.find(cmd)
    cursor.map(e => ByKey(e.key, e.size)).toList.sortBy(-_.total)
  }

  def forKey(key: String) = {
    val cmd = MongoDBObject(
      "key" -> key
    )
    val cursor = this.find(cmd)
    cursor.map(e => ByKey(e.name, e.size)).toList.sortBy(-_.total)
  }

  def dropKey(key: String) = {
    val cmd = MongoDBObject(
      "key" -> key
    )
    this.remove(cmd)
  }

  val start = new AtomicLong(this.ids(MongoDBObject()).max + 1)
  def makeId(): Long = start.getAndIncrement()

  implicit def context: Context

  def byKey(): Seq[ByKey] = {
    val pipeline = Seq(
      MongoDBObject("$group" -> MongoDBObject(
        "_id" -> "$key",
        "total" -> MongoDBObject("$sum" -> "$size")
      )), MongoDBObject("$sort" -> MongoDBObject(
        "total" -> -1
      ))
    )

    val out = this.collection.aggregate(pipeline)
    val grater = com.novus.salat.grater[ByKey]
    out.results.map { o =>  grater.asObject(new MongoDBObject(o)) }.toBuffer
  }

  def byName(): Seq[ByKey] = {
    val pipeline = Seq(
      MongoDBObject("$group" -> MongoDBObject(
        "_id" -> "$name",
        "total" -> MongoDBObject("$sum" -> "$size")
      )), MongoDBObject("$sort" -> MongoDBObject(
        "total" -> -1
      ))
    )
    val out = this.collection.aggregate(pipeline)
    val grater = com.novus.salat.grater[ByKey]
    out.results.map { o =>  grater.asObject(new MongoDBObject(o)) }.toBuffer
  }
}

class CollectionModule extends Module {
  override def configure(binder: Binder) = {}

  @Provides
  @Singleton
  def dirEntryDao(
    implicit ctx: Context,
    mongoInstance: MongoInstance
  ): DirectoryEntryDao = {
    new SalatDAO[DirectoryEntry, Long](mongoInstance.database("direntry")) with DirectoryEntryDao {
      def context = ctx
    }

  }

  @Provides
  @Singleton
  def collectors(
    asys: ActorSystem,
    info: InfoSink,
    ded: DirectoryEntryDao
  ): Collectors = {
    new Collectors {

      private val croot = asys.actorOf(
        Props(new CollectionRoot(info.actor, ded)),
        name = "collectors"
      )

      override def makeCollector(req: Collection.MakeCollector): ActorRef = {
        import akka.pattern.ask

        import scala.concurrent.duration._

        implicit val ec = asys.dispatcher
        implicit val to: Timeout = 2.seconds

        val child = (croot ? req).mapTo[ActorRef]
        Await.result(child, 2.seconds)
      }
    }
  }

}
