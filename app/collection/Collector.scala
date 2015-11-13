package collection

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import code.io.udp.InfoSink
import com.google.inject.{Binder, Module, Provides, Singleton}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.{Logger, StrictLogging}
import org.slf4j.LoggerFactory
import play.api.Configuration

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.io.Codec
import scala.util.Random

/**
  * @author eiennohito
  * @since 2015/11/13
  */
case class HostConfig(hosts: Seq[String], executorConfig: AllExecutorsConfig) {
  def appendArgs(res: ArrayBuffer[String], target: String) = {
    executorConfig.appendArgs(res, target)
  }
}

case class CollectionInstArgs(hostname: String, port: Int, label: String)

class CollectionTarget(val key: String, host: HostConfig, target: TargetPattern) {
  def makeArgs(args: CollectionInstArgs, hostname: String): Seq[String] = {
    val res = new ArrayBuffer[String]()
    host.appendArgs(res, hostname)
    res += "python"
    res += "-"
    res += args.hostname
    res += args.port.toString
    res += args.label
    res += target.prefix
    res
  }

  def selectHostname(): String = {
    val hosts = host.hosts
    val len = hosts.length
    val targetHost = if (len == 1) hosts.head else hosts(Random.nextInt(len))
    targetHost
  }

  def hosts = host.hosts
  def pattern = target.raw
}

case class TargetPattern(raw: String, prefix: String)

object TargetPattern {
  def create(pattern: String) = {
    val target = pattern.split("/").takeWhile(!_.startsWith(":")).mkString("/")
    new TargetPattern(pattern, target)
  }
}

class ProcessExecutor(pythonScript: Array[Byte], executor: ExecutionContext) extends StrictLogging {
  def launch(target: CollectionTarget, inst: CollectionInstArgs): Process = {
    val pbldr = new ProcessBuilder()

    val host = target.selectHostname()

    pbldr.command(target.makeArgs(inst, host): _*)
    val process = pbldr.start()
    val stream = process.getOutputStream
    stream.write(pythonScript)
    stream.flush()
    stream.close()

    executor.execute(streamWriter(process.getInputStream, host + "-out"))
    executor.execute(streamWriter(process.getErrorStream, host + "-err"))

    process
  }

  private def streamWriter(input: InputStream, marker: String): Runnable = {
    new Runnable {
      val logger = Logger(LoggerFactory.getLogger(s"subprocess.$marker"))

      override def run() = {
        try {
          val isr = new BufferedReader(new InputStreamReader(input, "utf-8"))
          var line = isr.readLine() //blocks
          while (line != null) {
            logger.info(s"$line")
            line = isr.readLine() //blocks
          }
        } finally {
          input.close()
        }
      }
    }
  }
}

class AllExecutorsConfig(hosts: String => ExecutorConfig) {
  def appendArgs(res: ArrayBuffer[String], host: String): Unit = {
    val cfg = hosts(host)
    cfg.appendArgs(res, host)
  }
}

case class ExecutorConfig(sshCommands: Seq[String], username: Option[String]) {
  def appendArgs(res: ArrayBuffer[String], host: String) = {
    sshCommands.foreach(res += _)
    username match {
      case Some(u) => res += s"$u@$host"
      case None => res += host
    }
  }
}

case class CollectionRegistry(items: Seq[CollectionTarget])

object CollectionRegistry {
  import scala.collection.JavaConverters._

  def makeHosts(raw: Seq[String]) = {
    val re = """^([a-z]+)(\d+)\.\.(\d+)""".r
    raw.flatMap {
      case re(host, start, end) =>
        val length = start.length max end.length
        val sint = start.toInt
        val eint = end.toInt
        val formatString = s"$host%0${length}d"
        (sint to eint).map { i => formatString.format(i) }
      case x => Seq(x)
    }
  }

  def fromConfig(conf: Configuration): CollectionRegistry = {
    val executors = conf.getConfig("aggregator.executors").getOrElse(throw new Exception("no configuration for aggregation.executors"))
    val default = executors.getConfig("default-executor").get
    val othersRaw = executors.underlying.withoutPath("default-executor")
    val others = Configuration(othersRaw)

    val targets = conf.getConfig("aggregator.collection.targets").get

    val result = new ArrayBuffer[CollectionTarget]()

    for (name <- targets.subKeys) {
      val obj = targets.getConfig(name).get.underlying
      val pattern = obj.getString("pattern")
      val patternObj = TargetPattern.create(pattern)

      val hosts = if (obj.hasPath("hosts"))  {
        makeHosts(obj.getStringList("hosts").asScala)
      } else {
        List(name)
      }



      val hostCfg: String => ExecutorConfig = { host =>
        val obj1 = others.getConfig(host).map(_.underlying).getOrElse(ConfigFactory.empty()).withFallback(othersRaw)
        val commands = obj1.getStringList("access.ssh-commands").asScala
        val username = if (obj1.hasPath("access.username"))
          Some(obj1.getString("access.username"))
        else None
        ExecutorConfig(commands, username)
      }

      val aec = new AllExecutorsConfig(hostCfg)

      val anyHost = obj.hasPath("any-host") && obj.getBoolean("any-host")


      if (anyHost) {
        val host = HostConfig(hosts, aec)
        result += new CollectionTarget(name, host, patternObj)
      } else {
        for (h <- hosts) {
          val host = HostConfig(List(h), aec)
          result += new CollectionTarget(s"$name-$h", host, patternObj)
        }
      }
    }

    CollectionRegistry(result)
  }
}


trait CollectionArgsSpawner {
  def create(): CollectionInstArgs
}

class HostModule extends Module {
  override def configure(binder: Binder) = {}

  @Provides
  @Singleton
  def executor(
    asys: ActorSystem
  ): ProcessExecutor = {
    val url = getClass.getClassLoader.getResource("/py/reporter.py")
    val bytes = scala.io.Source.fromURL(url)(Codec.UTF8).mkString.getBytes("utf-8")
    val dispatcher = asys.dispatchers.lookup("stream-reader-dispatcher")
    new ProcessExecutor(bytes, dispatcher)
  }

  @Provides
  @Singleton
  def registry(
    conf: Configuration
  ): CollectionRegistry = CollectionRegistry.fromConfig(conf)

  @Provides
  @Singleton
  def spawner(
    is: InfoSink
  ): CollectionArgsSpawner = new CollectionArgsSpawner {
    val id = new AtomicInteger(0)

    override def create() = {
      val runId = id.getAndIncrement()
      new CollectionInstArgs(is.addr.getHostName, is.addr.getPort, runId.toString)
    }
  }

}
