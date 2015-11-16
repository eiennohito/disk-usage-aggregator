package controllers

import javax.inject.Inject

import code.collection.{CollectionTasksService, CollectionRegistry}
import play.api._
import play.api.mvc._

class Application @Inject()(
  registry: CollectionRegistry,
  cts: CollectionTasksService
) extends Controller {

  def index = Action {
    Ok(views.html.index(registry.items))
  }

  def reload(key: String) = Action {
    cts.reset(key)
    Redirect(routes.Application.index())
  }

}
