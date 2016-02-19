package controllers

import javax.inject.Inject

import code.collection.{CollectionRegistry, CollectionTasksService, DirectoryEntryDao, PlaceTotalDao}
import play.api._
import play.api.mvc._

class Application @Inject()(
  registry: CollectionRegistry,
  cts: CollectionTasksService,
  ded: DirectoryEntryDao,
  space: PlaceTotalDao
) extends Controller {

  def index = Action {
    Ok(views.html.index(registry.items))
  }

  def reload(key: String) = Action {
    cts.reset(key)
    Redirect(routes.Application.index())
  }

  def stats() = Action {
    val keys = ded.byKey()
    val names = ded.byName()
    val total = space.all().map(x => x.place -> x).toMap
    val stats = keys.map {  x =>
      val t = total(x.key)
      PlaceStats (
        x.key,
        t.total,
        x.total,
        t.used
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

case class PlaceStats(name: String, total: Long, used: Long, usedFs: Long)
