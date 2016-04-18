package org.eiennohito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author eiennohito
 * @since 2015/11/17
 */
public class CollectorAgent {

  public static AtomicLong putPackets = new AtomicLong(0L);
  public static AtomicLong sentPackets = new AtomicLong(0L);

  public static void main(String[] arg) throws IOException {
    Worker w = new Worker(arg);
    Thread t = new Thread(w, "worker");
    t.run();

    int something = System.in.read();
    System.out.println("Somebody have written to stdout, terminate");
    System.exit(-1);
  }

  public static class Worker implements Runnable {
    private final String[] arg;

    public Worker(String[] args) {
      this.arg = args;
    }

    @Override
    public void run() {
      String hostname = arg[0];
      int port = Integer.parseInt(arg[1]);

      InetSocketAddress isa = new InetSocketAddress(hostname, port);
      String mark = arg[2];
      Path target = Paths.get(arg[3]);
      try (CollectorInstance instance = new CollectorInstance(isa, mark, target)) {
        System.out.println("doing work with mark " + mark + " on " + target);
        instance.doWork();
        System.out.println("finished " + target + " with " + putPackets.get() + "/" + sentPackets.get() + " packets");
      } catch (IOException e) {
        System.err.println("something was wrong, " + e.getMessage());
        e.printStackTrace(System.err);
      }

      System.exit(0);
    }
  }
}
