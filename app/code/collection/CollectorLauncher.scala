package code.collection

import javax.inject.{Provider, Inject, Singleton}

import akka.actor._
import com.google.inject.{Scopes, Provides, Binder, Module}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.mongodb.casbah.{MongoClientURI, MongoDB, MongoClient}
import com.novus.salat.Context
import com.novus.salat.annotations._
import com.novus.salat.dao.{DAO, SalatDAO}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.joda.time.DateTime
import play.api.{Environment, Configuration}

/**
  * @author eiennohito
  * @since 2015/11/13
  */
class CollectorLauncher (
  concurrency: Int,
  executor: ProcessLauncher,
  cas: CollectionArgsSpawner,
  tasks: CollectionTasksService,
  collectors: Collectors
) extends Actor with ActorLogging {
  import scala.concurrent.duration._

  implicit def ec = context.dispatcher

  case object Tick
  case class Mark(trg: Seq[CollectionTarget])

  context.system.scheduler.schedule(5.seconds, 15.seconds, self, Tick)


  var running: Seq[(CollectionTarget, Process)] = Nil

  override def receive = {
    case Tick =>
      val (alive, dead) = running.partition(_._2.isAlive)
      val cnt = concurrency - alive.length
      if (cnt > 0) {
        val ignore = running.map(_._1).toSet
        val available = tasks.request(cnt, ignore)
        log.info(s"$cnt slots available, running ${available.size} items")
        val launched = available.map { req =>
          val collectionArgs = cas.create()
          collectors.makeCollector(Collection.MakeCollector(collectionArgs.label, req))
          req -> executor.launch(req, collectionArgs)
        }
        self ! Mark(available)
        running = alive ++ launched
      } else {
        running = alive
      }
      val targets = dead.map(_._1)
      tasks.mark(targets)
  }

  @throws[Exception](classOf[Exception])
  override def postStop() = {
    running.map(_._2).foreach(_.destroy())
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

trait CollectorActorRef {
  def ref: ActorRef
}

class CollectorActorProvider @Inject() (
  conf: Configuration,
  asys: ActorSystem,
  pex: ProcessLauncher,
  cas: CollectionArgsSpawner,
  csvc: CollectionTasksService,
  colls: Collectors
) extends Provider[CollectorActorRef] {
  override def get() = {
    val concurrency = conf.getInt("aggregator.collection.max-concurrency").getOrElse(1)
    val props = Props(new CollectorLauncher(concurrency, pex, cas, csvc, colls))
    val aref = asys.actorOf(props, "collector")
    new CollectorActorRef {
      override def ref = aref
    }
  }
}

class CollectionServiceModule extends Module {
  override def configure(binder: Binder) = {
    binder.bind(classOf[CollectorActorRef]).toProvider(classOf[CollectorActorProvider]).asEagerSingleton()
    binder.bind(classOf[CollectionTasksService]).in(Scopes.SINGLETON)
  }

  @Provides
  @Singleton
  def mongo(conf: Configuration): MongoInstance = {
    RegisterJodaTimeConversionHelpers.apply()
    val uri = conf.getString("mongo.uri").getOrElse("mongodb://localhost")
    val db = conf.getString("mongo.db").getOrElse("aggregator")
    new MongoInstance {
      override val client = MongoClient(MongoClientURI(uri))
      override val database = client(db)
    }
  }

  @Provides
  @Singleton
  def salatContext(
    env: Environment
  ) = {
    val ctx = new Context {
      override val name: String = "collectorContext"
    }
    ctx.registerClassLoader(env.classLoader)
    ctx
  }

  @Provides
  @Singleton
  def skdao(
    implicit ctx: Context,
    mongo: MongoInstance
  ): SavedKeyDAO = {
    val db = mongo.database
    new SalatDAO[SavedKey, String](db("keys")) with SavedKeyDAO
  }
}

class CollectionTasksService @Inject() (skd: SavedKeyDAO, regs: CollectionRegistry) extends StrictLogging {
  def mark(target: TraversableOnce[CollectionTarget]) = {
    val upDate = DateTime.now().plusDays(1)
    val keys = target.map(_.key -> upDate).toMap
    synchronized {
      stored = stored ++ keys
    }
    val objs = keys.map(SavedKey.tupled)
    objs.foreach(skd.save)
  }

  var stored = skd.find(MongoDBObject()).map { a => a.id -> a.updDate }.toMap

  def request(cnt: Int, ignore: Set[CollectionTarget]): Seq[CollectionTarget] = synchronized {
    val updateTarget = DateTime.now()
    val ignoredKeys = ignore.map(_.key)
    regs.items.view.filter {
      ct => stored.get(ct.key)
        .map(_.isBefore(updateTarget))
        .getOrElse(true) && !ignoredKeys.contains(ct.key)
    }
  }

  def reset(key: String) = {
    synchronized {
      stored -= key
    }
    logger.debug(s"removed $key, ${stored.size} items remaining")
  }
}