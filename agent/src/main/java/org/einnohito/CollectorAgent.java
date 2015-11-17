package org.einnohito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author eiennohito
 * @since 2015/11/17
 */
public class CollectorAgent {
  public static void main(String[] arg) {
    String hostname = arg[0];
    int port = Integer.parseInt(arg[1]);

    InetSocketAddress isa = new InetSocketAddress(hostname, port);
    String mark = arg[2];
    Path target = Paths.get(arg[3]);
    try (CollectorInstance instance = new CollectorInstance(isa, mark, target)) {
      System.out.println("doing work with mark " + mark + " on " + target);
      instance.doWork();
      System.out.println("finished " + target);
    } catch (IOException e) {
      System.err.println("something was wrong, " + e.getMessage());
      e.printStackTrace(System.err);
    }
  }
}
