package collection

import javax.inject.Singleton

import akka.actor.Actor
import com.google.inject.{Provides, Binder, Module}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClientURI, MongoDB, MongoClient}
import com.novus.salat.annotations.raw.Key
import com.novus.salat.dao.{DAO, SalatDAO}
import org.joda.time.DateTime
import play.api.Configuration

/**
  * @author eiennohito
  * @since 2015/11/13
  */
class CollectorActor(concurrency: Int, executor: ProcessExecutor, cas: CollectionArgsSpawner, svc: CollectionService) extends Actor {
  import scala.concurrent.duration._

  implicit def ec = context.dispatcher

  case object Tick

  context.system.scheduler.schedule(1.second, 5.seconds, self, Tick)


  var running: Seq[Process] = Nil

  override def receive = {
    case Tick =>

  }
}

trait CollectionData {

}

case class SavedKey(@Key("_id") id: String, updDate: DateTime)


trait SavedKeyDAO extends DAO[SavedKey, String]

trait MongoInstance {
  def client: MongoClient
  def database: MongoDB
}


import com.novus.salat._
import com.novus.salat.global._

class CollectionServiceModule extends Module {
  override def configure(binder: Binder) = {}

  @Provides
  @Singleton
  def mongo(conf: Configuration): MongoInstance = {
    val uri = conf.getString("mongo.uri").getOrElse("mongodb://localhost")
    val db = conf.getString("mongo.db").getOrElse("aggregator")
    new MongoInstance {
      override val client = MongoClient(MongoClientURI(uri))
      override val database = client(db)
    }
  }

  @Provides
  @Singleton
  def skdao(
    mongo: MongoInstance
  ): SavedKeyDAO = {
    val db = mongo.database
    new SalatDAO[SavedKey, String](db("keys")) with SavedKeyDAO
  }
}

class CollectionService(skd: SavedKeyDAO, regs: CollectionRegistry) {
  var stored = skd.find(MongoDBObject()).map { a => a.id -> a.updDate }.toMap

  def request(cnt: Int): Seq[CollectionTarget] = synchronized {
    val updateTarget = DateTime.now().minusDays(1)
    regs.items.view.filter(ct => stored.get(ct.key).map(_.isBefore(updateTarget)).getOrElse(true))
  }
}
