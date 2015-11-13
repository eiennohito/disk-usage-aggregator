package collection

import java.io.{InputStream, InputStreamReader, BufferedReader}
import java.util

import com.typesafe.scalalogging.slf4j.StrictLogging

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext

/**
  * @author eiennohito
  * @since 2015/11/13
  */
class Collector {

}

case class HostConfig(host: String, executorConfig: ExecutorConfig) {
  def appendArgs(res: ArrayBuffer[String]) = {
    executorConfig.appendArgs(res, host)
  }
}

case class CollectionInstArgs(hostname: String, port: Int, label: String)

class CollectionTarget(host: HostConfig, target: TargetPattern) {
  def makeArgs(args: CollectionInstArgs): Seq[String] = {
    val res = new ArrayBuffer[String]()
    host.appendArgs(res)
    res += "python"
    res += "-"
    res += (args.hostname)
    res += (args.port.toString)
    res += (args.label)
    res += (target.prefix)
    res
  }

  def hostname = host.host
}

case class TargetPattern(raw: String, prefix: String)

class ProcessExecutor(pythonScript: Array[Byte], executor: ExecutionContext) extends StrictLogging {
  def launch(target: CollectionTarget): Process = {
    val pbldr = new ProcessBuilder()
    pbldr.command(target.makeArgs(???): _*)
    val process = pbldr.start()
    val stream = process.getOutputStream
    stream.write(pythonScript)
    stream.flush()
    stream.close()

    executor.execute(streamWriter(process.getInputStream, target.hostname))
    executor.execute(streamWriter(process.getErrorStream, target.hostname))

    process
  }

  private def streamWriter(input: InputStream, marker: String): Runnable = {
    new Runnable {
      override def run() = {
        try {
          val isr = new BufferedReader(new InputStreamReader(input, "utf-8"))
          var line = isr.readLine() //blocks
          while (line != null) {
            logger.info(s"$marker $line")
            line = isr.readLine() //blocks
          }
        } finally {
          input.close()
        }
      }
    }
  }
}

case class ExecutorConfig(sshCommands: Seq[String], username: Option[String]) {
  def appendArgs(res: ArrayBuffer[String], host: String) = {
    sshCommands.foreach(res.append)
    username match {
      case Some(u) => res.add(s"$u@$host")
      case None => res.add(host)
    }
  }
}
