package controllers

import javax.inject.Inject

import code.collection._
import code.tracing.{CollectionProgress, ProgressInfo, TrackStatsAccess}
import org.joda.time.{DateTime, Duration}
import play.api._
import play.api.mvc._

import scala.concurrent.ExecutionContext

class Application @Inject()(
  registry: CollectionRegistry,
  cts: CollectionTasksService,
  skd: SavedKeyDAO,
  ded: DirectoryEntryDao,
  space: PlaceTotalDao,
  sacc: TrackStatsAccess,
  ec: ExecutionContext
) extends Controller {
  implicit def exc = ec


  def index = Action.async {
    val items = registry.items
    val all = skd.all()
    val mx = all.map {i => i.id -> i }.toMap
    sacc.progress.map { progress =>
      val data = items.map { i =>
        val o = mx.get(i.key)
        HostStatus(
          i.key,
          i.hosts,
          i.pattern,
          o.map(_.lastUpdate),
          o.map(x => Duration.millis(x.duration)),
          o.map(_.updDate),
          progress.data.get(i.key)
        )
      }
      Ok(views.html.index(data))
    }
  }

  def hist(key: String) = Action.async {
    sacc.histogram(key).map { v =>
      v.hist match {
        case None => Ok("This was not analyzed")
        case Some(h) =>
          import scala.collection.JavaConverters._
          val iter = h.logarithmicBucketValues(100, 2.0).iterator().asScala

          val data = iter.map { v => PercentileUnit(v.getPercentileLevelIteratedTo, v.getCountAddedInThisIterationStep, v.getValueIteratedTo) }
          Ok(views.html.hist(Percentiles(data.toSeq, key)))
      }
    }
  }

  def reload(key: String) = Action {
    cts.reset(key)
    Redirect(routes.Application.index())
  }

  def stats() = Action {
    val keys = ded.byKey()
    val names = ded.byName()
    val total = space.all().map(x => x.place -> x).toMap
    val dates = skd.all().map(x => x.id -> x.lastUpdate).toMap
    val stats = keys.map {  x =>
      val t = total(x.key)
      PlaceStats (
        x.key,
        t.total,
        x.total,
        t.used,
        dates.get(x.key)
      )
    }
    Ok(views.html.stats(names, stats))
  }

  def userStats(name: String) = Action {
    val info = ded.forUser(name)
    Ok(views.html.detail("user", name, info))
  }

  def hostStats(name: String) = Action {
    val info = ded.forKey(name)
    Ok(views.html.detail("host", name, info))
  }
}

case class PlaceStats(name: String, total: Long, used: Long, usedFs: Long, updDate: Option[DateTime])
case class HostStatus(key: String, hosts: Seq[String], pattern: String,
  lastDate: Option[DateTime], lastEplaced: Option[Duration], nextDate: Option[DateTime], progress: Option[CollectionProgress]
)

case class PercentileUnit(percentile: Double, count: Long, value: Long)
case class Percentiles(units: Seq[PercentileUnit], key: String)
