package code.tracing

import akka.actor.{Actor, ActorLogging}
import code.collection._
import org.HdrHistogram.Histogram
import org.joda.time.DateTime

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

case class CollectionProgress(start: DateTime, progress: Double, estimatedEnd: DateTime)

/**
  * @author eiennohito
  * @since 2016/04/12
  */
class Tracer extends Actor with ActorLogging {

  log.info("starting up tracing with path {}", self.path)

  private class TraceInstance {
    val histogram = new Histogram(3)

    var lastMessage = -1L
    def logMessageTime(nanotime: Long) = {
      if (lastMessage != -1) {
        val eplaced = nanotime - lastMessage
        if (eplaced > 0) {
          histogram.recordValue(eplaced / 1000) //microseconds
        }
      }
      lastMessage = nanotime
    }


    def reset() = {
      collectionStart = DateTime.now()
      histogram.reset()
      collected = 0
    }

    var total = 0L
    var used = 0L
    var collectionStart = DateTime.now()
    var collected = 0L

    def stats(total: Long, used: Long): Unit = {
      this.total = total
      this.used = used
    }

    def updir(dinfo: DirectoryInformation, upinfo: DirectoryUp): Unit = {
      collected += upinfo.ownSize
    }

    def finish(): Unit = {
      collected = used
    }
  }

  private val inFlight = new mutable.HashMap[String, CollectionTarget]()
  private val instances = new mutable.HashMap[String, TraceInstance]()

  private def instance(mark: String): Option[TraceInstance] = {
    inFlight.get(mark).map(ct => instances.getOrElseUpdate(ct.key, new TraceInstance))
  }

  private def handleMsg(nanotime: Long, msg: AnyRef): Unit = {
    msg match {
      case x: Collection.MakeCollector =>
        inFlight.update(x.mark, x.target)
        instance(x.mark).foreach(_.reset())
      case x: Updater.UpdateTotals =>
        instances.get(x.place).foreach(_.stats(x.total, x.used))
      case x: CollectionMessage =>
        instance(x.mark).foreach { _.logMessageTime(nanotime) }
      case x: Tracer.UpWalk =>
        instances.get(x.target.key).foreach { _.updir(x.dinfo, x.upinfo) }
      case x: Collection.CollectionFinished =>
        instance(x.mark).foreach(_.finish())
        inFlight.remove(x.mark)
      case x => log.warning("unhandled message {}", x)
    }
  }

  def formatStats() = {
    val objs = instances.map { case (k, v) =>
      val progress = v.collected.toDouble / v.used
      val start = v.collectionStart
      val eplaced = System.currentTimeMillis() - start.getMillis
      val speed = v.collected / eplaced.toDouble
      val willTake = speed * v.used
      val endTime = start.plus(org.joda.time.Duration.millis(willTake.toLong))
      k -> CollectionProgress(start, progress, endTime)
    }

    ProgressInfo(objs.toMap)
  }

  override def receive = {
    case Tracer.Trace(time, msg) => handleMsg(time, msg)
    case Tracer.Progress => sender() ! formatStats()
    case Tracer.Histogram(key) =>
      sender() ! ReplyHistogram(instances.get(key).map { i => i.histogram.copy() })
  }
}

case class ReplyHistogram(hist: Option[Histogram])
case class ProgressInfo(data: Map[String, CollectionProgress])

object Tracer {
  case class Trace(time: Long, msg: AnyRef)
  case class UpWalk(target: CollectionTarget, dinfo: DirectoryInformation, upinfo: DirectoryUp)
  case object Progress
  case class Histogram(key: String)
}

trait TrackingApi {
  this: Actor =>
  private lazy val trackerSelection = this.context.actorSelection(this.context.parent.path.root / "user" / "tracking")
  private lazy val trackerRefFuture = trackerSelection.resolveOne(5.seconds)

  protected def trace(msg: AnyRef) = {
    val time = System.nanoTime()
    trackerRefFuture.foreach {
      _ ! Tracer.Trace(time, msg)
    }(context.system.dispatcher)
  }
}

trait TrackStatsAccess {
  def progress: Future[ProgressInfo]
  def histogram(key: String): Future[ReplyHistogram]
}
