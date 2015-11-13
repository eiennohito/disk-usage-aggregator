package controllers

import javax.inject.Inject

import collection.CollectionRegistry
import play.api._
import play.api.mvc._

class Application @Inject()(
  registry: CollectionRegistry
) extends Controller {

  def index = Action {
    Ok(views.html.index(registry.items))
  }

}
