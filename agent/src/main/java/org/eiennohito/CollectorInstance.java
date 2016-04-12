package org.eiennohito;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
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
  public static final long PARENT_ID = 0L;
  private final InetSocketAddress isa;
  private final String mark;
  private final Path target;

  public static final int MESSAGE_SIZE = 8000;
  public static final int BUFFER_SIZE = 4000;

  public CollectorInstance(InetSocketAddress isa, String mark, Path target) {
    this.isa = isa;
    this.mark = mark;
    this.target = target;
  }


  public void doWork() throws IOException {
    try (SocketChannel channel = SocketChannel.open(isa)) {
      Sender sndr = new Sender(channel, isa);
      AtomicLong cntr = new AtomicLong(1L);
      MessageFormatter fmtr = new MessageFormatter(sndr, BUFFER_SIZE, MESSAGE_SIZE, mark);

      PosixFileAttributes targetAttrs = Files.readAttributes(target, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      ProcessingStep targetStep = new ProcessingStep(target, targetAttrs, cntr, PARENT_ID);

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
    private final ByteBuffer packet;

    public SendRequest(ByteBuffer packet) {
      this.packet = packet;
    }

    public ByteBuffer getPacket() {
      return packet;
    }
  }

  private static class Sender implements MessageSender {
    private final SocketChannel socket;
    private final InetSocketAddress remote;

    private final BlockingQueue<SendRequest> requests = new LinkedBlockingQueue<>();

    private final Thread sendThread = new Thread(new Runnable() {
      @Override
      public void run() {
        ByteBuffer rcvBuf = ByteBuffer.allocate(10);
        while (true) {
          try {
            SendRequest poll = requests.take();
            ByteBuffer packet = poll.getPacket();
            if (packet != null) {
              writePacket(packet);
              rcvBuf.clear();
              rcvBuf.limit(4);
              //socket.read(rcvBuf);
              //rcvBuf.flip();
              long number = CollectorAgent.sentPackets.getAndIncrement();
              int answer = rcvBuf.getInt(0);
              System.out.println("sent #" + number + " recv #" + answer);
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

      private void writePacket(ByteBuffer packet) throws IOException, InterruptedException {
        int remaining = packet.remaining();
        while (remaining > 0) {
          int written = socket.write(packet);
          remaining -= written;
          if (written == 0) {
            this.wait(1000);
          }
        }
      }
    });

    Sender(SocketChannel socket, InetSocketAddress remote) {
      this.socket = socket;
      this.remote = remote;
      sendThread.setDaemon(true);
      sendThread.start();
    }

    @Override
    public void sendBuffer(ByteBuffer buf) throws IOException {
      int size = buf.remaining();
      ByteBuffer copy = ByteBuffer.allocate(size + 4);
      copy.order(ByteOrder.BIG_ENDIAN);
      copy.putInt(size);
      copy.put(buf);
      copy.flip();
      send(copy);
    }

    public void finish() throws InterruptedException {
      requests.add(new SendRequest(null));
      sendThread.join();
    }

    public void send(ByteBuffer packet) {
      if (packet != null) {
        requests.add(new SendRequest(packet));
        CollectorAgent.putPackets.incrementAndGet();
      }
    }
  }

  @Override
  public void close() throws IOException {}
}
