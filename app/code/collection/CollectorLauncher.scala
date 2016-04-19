package code.collection

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Provider, Singleton}

import akka.actor._
import com.google.inject.{Binder, Module, Provides, Scopes}
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.mongodb.casbah.{MongoClient, MongoClientURI, MongoDB}
import com.novus.salat.Context
import com.novus.salat.annotations._
import com.novus.salat.dao.{DAO, SalatDAO}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.joda.time.{DateTime, Duration}
import play.api.{Configuration, Environment}

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
  * @author eiennohito
  * @since 2015/11/13
  */

case class CollectionStatus(
  target: CollectionTarget,
  process: Process,
  host: String,
  args: CollectionInstArgs,
  actor: ActorRef,
  startTime: DateTime = DateTime.now()
)

class CollectorLauncher (
  concurrency: Int,
  inteval: FiniteDuration,
  executor: ProcessLauncher,
  cas: CollectionArgsSpawner,
  tasks: CollectionTasksService,
  collectors: Collectors
) extends Actor with ActorLogging {
  import scala.concurrent.duration._

  implicit def ec = context.dispatcher

  case object Tick
  case class Append(trg: Seq[CollectionStatus])

  context.system.scheduler.schedule(5.seconds, inteval, self, Tick)


  var running: Seq[CollectionStatus] = Nil

  import akka.pattern.pipe

  override def receive = {
    case Tick =>
      val (alive, dead) = running.partition(_.process.isAlive)
      val cnt = concurrency - alive.length
      val hosts = new mutable.HashSet[String]()
      hosts ++= alive.map(_.host)
      if (cnt > 0) {
        val ignore = running.map(_.target).toSet
        val available = tasks.request(cnt, ignore)
        if (available.nonEmpty) {
          log.debug("{} slots available, trying to launch {} collectors for {}", cnt, available.size, available.map(_.key).mkString("[", ", ", "]"))
        }

        val toLaunch = available.flatMap { req =>
          val host = req.selectHostname()
          if (hosts.contains(host)) {
            tasks.makeWait(req, 5.minutes)
            Nil
          } else {
            hosts += host
            val args = cas.create()
            val f = args.map { collectionArgs =>
              val aref = collectors.makeCollector(Collection.MakeCollector(collectionArgs.mark, req))
              val proc = executor.launch(req, collectionArgs, host)
              CollectionStatus(req, proc, host, collectionArgs, aref)
            }
            f :: Nil
          }
        }

        val data = Future.sequence(toLaunch)
        data.map(x => Append(x)).pipeTo(self)

        running = alive
      } else {
        running = alive
      }

      tasks.markFinish(dead)
      dead.foreach(x => x.actor ! Collection.CollectionFinished(x.args.mark))
      if (dead.nonEmpty) {
        log.debug("{} collectors finished")
      }
    case Append(launched) =>
      running ++= launched
      log.info("launched {} collectors", launched.size)
  }

  @throws[Exception](classOf[Exception])
  override def postStop() = {
    running.map(_.process).foreach { p =>
      val stream = p.getOutputStream
      stream.write(1)
      stream.flush()
      p.destroy()
    }
  }
}

case class SavedKey(
  @Key("_id") id: String,
  lastUpdate: DateTime,
  updDate: DateTime,
  duration: Long
)


trait SavedKeyDAO extends DAO[SavedKey, String] {
  def all() = find(MongoDBObject.empty).toList
}

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
    import scala.concurrent.duration._
    val concurrency = conf.getInt("aggregator.collection.max-concurrency").getOrElse(1)
    val tickInterval = conf.getMilliseconds("aggregator.colleciton.tick-interval")
      .map(FiniteDuration(_, TimeUnit.MILLISECONDS)).getOrElse(15.seconds)
    val props = Props(new CollectorLauncher(concurrency, tickInterval, pex, cas, csvc, colls))
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

class CollectionTasksService @Inject() (
  skd: SavedKeyDAO,
  regs: CollectionRegistry,
  conf: Configuration
) extends StrictLogging {

  def makeWait(req: CollectionTarget, minutes: FiniteDuration) = synchronized {
    val date = DateTime.now().plusMillis(minutes.toMillis.toInt)
    stored = stored.updated(req.key, date)
  }

  val scanTTL = conf.getMilliseconds("aggregator.collection.scan-ttl").getOrElse(1000 * 60 * 60 * 24L)

  def markFinish(target: Seq[CollectionStatus]) = {
    val updateInterval = org.joda.time.Duration.millis(scanTTL)
    val now = DateTime.now()
    val nextUpdate = now.plus(updateInterval)
    val keys = target.map(x => (x.target.key, nextUpdate, now.getMillis - x.startTime.getMillis))
    val update = keys.map { case (a, b, c) => a -> b}.toMap
    synchronized {
      stored = stored ++ update
    }
    val objs = keys.map { case (key, next, processTime) => SavedKey(key, now, next, processTime) }
    objs.foreach(skd.save)
  }

  var stored = skd.find(MongoDBObject()).map { a => a.id -> a.updDate }.toMap

  def request(cnt: Int, ignore: Set[CollectionTarget]): Seq[CollectionTarget] = synchronized {
    val updateTarget = DateTime.now()
    val ignoredKeys = ignore.map(_.key)
    val data = regs.items.view.filter {
      ct => stored.get(ct.key).forall(_.isBefore(updateTarget)) && !ignoredKeys.contains(ct.key)
    }.take(cnt)
    data.toList
  }

  def reset(key: String) = {
    synchronized {
      stored -= key
    }
    logger.debug(s"removed $key, ${stored.size} items remaining")
  }
}
