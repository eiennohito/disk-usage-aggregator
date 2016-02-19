package code.collection

import akka.actor.{Actor, ActorLogging}
import org.joda.time.DateTime

/**
  * @author eiennohito
  * @since 2016/02/19
  */
class Updater(dao: DirectoryEntryDao, places: PlaceTotalDao) extends Actor with ActorLogging {
  import Updater._

  override def receive = {
    case x: UpdateTotals =>
      places.updateTotal(x.place, x.total, x.used)
    case x: UpdateStats =>
      dao.updateStats(x.place, x.user, x.size, x.files)
    case x: CleanOld =>
      dao.dropOld(x.place, x.date)
  }
}

object Updater {
  case class UpdateTotals(place: String, total: Long, used: Long)
  case class UpdateStats(place: String, user: String, size: Long, files: Long)
  case class CleanOld(place: String, date: DateTime)
}
