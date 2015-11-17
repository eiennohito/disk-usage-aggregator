package controllers

import javax.inject.Inject

import code.collection.{DirectoryEntryDao, CollectionTasksService, CollectionRegistry}
import play.api._
import play.api.mvc._

class Application @Inject()(
  registry: CollectionRegistry,
  cts: CollectionTasksService,
  ded: DirectoryEntryDao
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
    Ok(views.html.stats(names, keys))
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
