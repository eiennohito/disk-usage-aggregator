package controllers

import javax.inject.Inject

import code.collection._
import org.joda.time.{DateTime, Duration}
import play.api._
import play.api.mvc._

class Application @Inject()(
  registry: CollectionRegistry,
  cts: CollectionTasksService,
  skd: SavedKeyDAO,
  ded: DirectoryEntryDao,
  space: PlaceTotalDao
) extends Controller {

  def index = Action {
    val items = registry.items
    val all = skd.all()
    val mx = all.map {i => i.id -> i }.toMap
    val data = items.map { i =>
      val o = mx.get(i.key)
      HostStatus(
        i.key,
        i.hosts,
        i.pattern,
        o.map(_.lastUpdate),
        o.map(x => Duration.millis(x.duration)),
        o.map(_.updDate)
      )
    }
    Ok(views.html.index(data))
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
  lastDate: Option[DateTime], lastEplaced: Option[Duration], nextDate: Option[DateTime]
)
