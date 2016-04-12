package code.collection

import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.util.Timeout
import code.io.udp.InfoSink
import code.tracing.{ProgressInfo, ReplyHistogram, Tracer, TrackStatsAccess}
import com.google.inject.{Binder, Module, Provides, Singleton}
import com.mongodb.casbah.WriteConcern
import com.mongodb.casbah.commons.MongoDBObject
import com.novus.salat.Context
import com.novus.salat.dao.{DAO, SalatDAO}
import org.joda.time.DateTime

import scala.concurrent.{Await, ExecutionContext}



object Collection {
  case class MakeCollector(mark: String, target: CollectionTarget)
  case class CollectionFinished(mark: String)
}

trait Collectors {
  def makeCollector(req: Collection.MakeCollector): ActorRef
}

import com.novus.salat.annotations._

case class DirectoryEntry(
  @Key("_id") id: Long,
  parent: Option[Long],
  place: String,
  user: String,
  size: Long,
  files: Long,
  date: DateTime
)

case class ByKey(@Key("_id")key: String, total: Long)

trait DirectoryEntryDao extends DAO[DirectoryEntry, Long] {
  def dropOld(place: String, date: DateTime): Unit = {
    val q = MongoDBObject(
      "place" -> place,
      "date" -> MongoDBObject(
        "$lt" -> date
      )
    )

    this.remove(q)
  }


  def forPair(place: String, user: String) = {
    val q = MongoDBObject(
      "place" -> place,
      "user" -> user
    )
    this.findOne(q)
  }

  def updateStats(place: String, user: String, size: Long, files: Long): DirectoryEntry = {
    val obj = forPair(place, user) match {
      case Some(x) => x
      case None =>
        val id = makeId()
        DirectoryEntry(
          id,
          None,
          place,
          user,
          0L,
          0L,
          DateTime.now()
        )
    }

    val updated = obj.copy(
      size = size,
      files = files,
      date = DateTime.now()
    )

    this.update(MongoDBObject("_id" -> obj.id), updated, upsert = true, multi = false, WriteConcern.Acknowledged)

    obj
  }

  def forUser(name: String) = {
    val cmd = MongoDBObject(
      "user" -> name
    )
    val cursor = this.find(cmd)
    cursor.map(e => ByKey(e.place, e.size)).toList.sortBy(-_.total)
  }

  def forKey(key: String) = {
    val cmd = MongoDBObject(
      "place" -> key
    )
    val cursor = this.find(cmd)
    cursor.map(e => ByKey(e.user, e.size)).toList.sortBy(-_.total)
  }

  def maxId() = {
    val qs = MongoDBObject("_id" -> -1)

    val data = this.find(MongoDBObject.empty).$orderby(qs).limit(1)
    data.toList.headOption.map(_.id).getOrElse(0L)
  }

  val start = new AtomicLong(maxId() + 1L)
  def makeId(): Long = start.getAndIncrement()

  implicit def context: Context

  def byKey(): Seq[ByKey] = {
    val pipeline = Seq(
      MongoDBObject("$group" -> MongoDBObject(
        "_id" -> "$place",
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
        "_id" -> "$user",
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

case class PlaceTotal(
  @Key("_id") id: Long,
  place: String,
  total: Long,
  used: Long
)

trait PlaceTotalDao extends DAO[PlaceTotal, Long] {
  def all() = {
    this.find(MongoDBObject.empty).toList
  }

  def byPlace(place: String) = {
    val query = MongoDBObject("place" -> place)
    this.findOne(query)
  }

  def updateTotal(place: String, total: Long, used: Long): PlaceTotal = {
    val obj = byPlace(place) match {
      case Some(x) => x
      case None =>
        val id = this.count()
        PlaceTotal(
          id, place, 0L, 0L
        )
    }
    val updated = obj.copy(total = total, used = used)
    this.update(MongoDBObject("_id" -> obj.id), updated, upsert = true, multi = false, WriteConcern.Acknowledged)
    obj
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
  def placesDao(
    implicit ctx: Context,
    mongoInstance: MongoInstance
  ): PlaceTotalDao = {
    new SalatDAO[PlaceTotal, Long](mongoInstance.database("places")) with PlaceTotalDao {
      def context = ctx
    }
  }

  trait TracerAccess {
    def tracer: ActorRef
  }

  @Provides
  @Singleton
  def collectors(
    asys: ActorSystem,
    info: InfoSink,
    ded: DirectoryEntryDao,
    places: PlaceTotalDao
  ): Collectors = {
    new Collectors with TracerAccess {

      val tracer = asys.actorOf(
        Props(new Tracer),
        name = "tracking"
      )

      private val updater = asys.actorOf(
        Props(new Updater(ded, places)),
        name = "updater"
      )

      private val croot = asys.actorOf(
        Props(new CollectionRoot(info.actor, updater)),
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

  @Provides
  def statsAccess(
    coll: Collectors,
    asys: ActorSystem
  ): TrackStatsAccess = new TrackStatsAccess {
    val tacc = coll.asInstanceOf[TracerAccess]
    implicit def ec: ExecutionContext = asys.dispatcher

    import akka.pattern.ask
    import scala.concurrent.duration._

    implicit val timeout: Timeout = 10.seconds

    override def progress = (tacc.tracer ? Tracer.Progress).mapTo[ProgressInfo]
    override def histogram(key: String) = (tacc.tracer ? Tracer.Histogram(key)).mapTo[ReplyHistogram]
  }

}
