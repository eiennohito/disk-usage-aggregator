package code.collection

import akka.actor.{Actor, ActorLogging, ActorRef}
import code.tracing.{Tracer, TrackingApi}
import org.joda.time.DateTime

import scala.collection.mutable

/**
  * @author eiennohito
  * @since 2016/02/19
  */
class Collector(mark: String, ct: CollectionTarget, updater: ActorRef) extends Actor with ActorLogging with TrackingApi {
  private val dirs = new mutable.HashMap[Long, DirectoryInformation]()
  dirs.put(0L, DirectoryInformation(ct.target.prefix, 0L, null, 0, 0))

  private val collectionStart = DateTime.now()

  def processEvents(events: Seq[CollectionEntry]): Unit = {
    val data = events.iterator
    while (data.hasNext) {
      val e = data.next()
      e match {
        case x: DeviceStat =>
          val msg = Updater.UpdateTotals(ct.key, x.total, x.used)
          log.debug("message for totals: total={} used={}", x.total, x.used)
          trace(msg)
          updater ! msg
        case x: DirectoryDown =>
          dirs.get(x.parent) match {
            case Some(p) =>
              dirs.put(x.id, DirectoryInformation(x.name, x.id, p, x.uid, p.level + 1))
            case None =>
              log.error("no parent directory registered for {}, {}, {}, {}", mark, x.name, x.id, x.parent)
          }
        case x: DirectoryUp =>
          dirs.get(x.id) match {
            case Some(de) =>
              trace(Tracer.UpWalk(ct, de, x))
              if (de.level == 2) {
                val msg = Updater.UpdateStats(
                  ct.key,
                  de.name,
                  x.recSize,
                  x.recFiles
                )
                updater ! msg
              }
              dirs.remove(x.id)
            case None =>
              log.error(s"could not find saved directory for id={}, mark={}", x.id, mark)
          }
        case x: Error =>
      }
    }
  }

  override def receive = {
    case CollectionMessage(_, events) => processEvents(events)
    case x: Collection.CollectionFinished =>
      updater ! Updater.CleanOld(ct.key, collectionStart)
      context.parent ! x
  }
}

case class DirectoryInformation(name: String, id: Long, parent: DirectoryInformation, uid: Int, level: Int)
