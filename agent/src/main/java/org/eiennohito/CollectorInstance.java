package org.eiennohito;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author eiennohito
 * @since 2015/11/17
 */
public class CollectorInstance implements Closeable {
  private final InetSocketAddress isa;
  private final String mark;
  private final Path target;

  public static final int MESSAGE_SIZE = 16000;
  public static final int BUFFER_SIZE = 4000;

  public CollectorInstance(InetSocketAddress isa, String mark, Path target) {
    this.isa = isa;
    this.mark = mark;
    this.target = target;
  }


  public void doWork() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      Sender sndr = new Sender(socket, isa);
      AtomicLong cntr = new AtomicLong(0L);
      MessageFormatter fmtr = new MessageFormatter(sndr, BUFFER_SIZE, MESSAGE_SIZE, mark);

      PosixFileAttributes targetAttrs = Files.readAttributes(target, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      ProcessingStep targetStep = new ProcessingStep(target, targetAttrs, cntr);

      FileStore store = Files.getFileStore(target);
      fmtr.appendOverall(store.getTotalSpace(), store.getTotalSpace() - store.getUsableSpace());

      targetStep.process(fmtr);

      fmtr.flush();
      sndr.finish();
    } catch (InterruptedException e) {
      e.printStackTrace(System.err);
    }
  }


  private static class SendRequest {
    private final DatagramPacket packet;

    public SendRequest(DatagramPacket packet) {
      this.packet = packet;
    }

    public DatagramPacket getPacket() {
      return packet;
    }
  }

  private static class Sender implements MessageSender {
    private final DatagramSocket socket;
    private final InetSocketAddress remote;

    private final BlockingQueue<SendRequest> requests = new LinkedBlockingQueue<>();

    private final Thread sendThread = new Thread(new Runnable() {
      @Override
      public void run() {
        while (true) {
          try {
            SendRequest poll = requests.take();
            DatagramPacket packet = poll.getPacket();
            if (packet != null) {
              socket.send(packet);
            } else {
              return;
            }
          } catch (IOException e) {
            e.printStackTrace(System.err);
          } catch (InterruptedException e) {
            return;
          }
        }
      }
    });

    Sender(DatagramSocket socket, InetSocketAddress remote) {
      this.socket = socket;
      this.remote = remote;
      sendThread.setDaemon(true);
      sendThread.start();
    }

    @Override
    public void sendBuffer(ByteBuffer buf) throws IOException {
      byte[] copy = new byte[buf.remaining()];
      buf.get(copy);
      DatagramPacket packet = new DatagramPacket(copy, 0, copy.length, remote);
      send(packet);
    }

    public void finish() throws InterruptedException {
      requests.add(new SendRequest(null));
      sendThread.join();
    }

    public void send(DatagramPacket packet) {
      if (packet != null) {
        requests.add(new SendRequest(packet));
      }
    }
  }

  @Override
  public void close() throws IOException {}
}
