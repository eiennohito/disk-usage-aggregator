package org.eiennohito;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author eiennohito
 * @since 2015/11/17
 */
public class CollectorInstance implements Closeable {
  private final InetSocketAddress isa;
  private final String mark;
  private final Path target;

  public static final int BUFFER_LIMIT = 16000;

  public CollectorInstance(InetSocketAddress isa, String mark, Path target) {
    this.isa = isa;
    this.mark = mark;
    this.target = target;
  }


  public void doWork() throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      Sender sndr = new Sender(socket);
      SendManager mgr = new SendManager(sndr, isa, mark.getBytes(Charset.forName("utf-8")));
      long total = 0;
      FileStore fileStore = Files.getFileStore(target);
      mgr.pushStore(fileStore);
      try (DirectoryStream<Path> paths = Files.newDirectoryStream(target)) {
        for (Path item : paths) {
          PosixFileAttributes attrs = Files.readAttributes(item, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (attrs.isDirectory()) {
            try { total += process(item, attrs, mgr); } catch (AccessDeniedException e) { /*ignore*/ }
          } else {
            total += attrs.size();
          }
        }
      }
      PosixFileAttributeView attrs = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      mgr.push(target, total, attrs.getOwner());
      mgr.flush();
      sndr.finish();
    } catch (InterruptedException e) {
      e.printStackTrace(System.err);
    }
  }

  private static long process(Path path, PosixFileAttributes pattrs, SendManager mgr) throws IOException {
    long result = 0;
    try (DirectoryStream<Path> contents = Files.newDirectoryStream(path)) {
      for (Path p: contents) {
        PosixFileAttributes childAttrs;
        try {
          childAttrs = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          if (childAttrs.isDirectory()) {
            try {
              result += process(p, childAttrs, mgr);
            } catch (AccessDeniedException e) {
              //swallow it
            }
          } else {
            result += childAttrs.size();
          }
        } catch (SecurityException e) {
          //do nothing
        }
      }
    }
    mgr.push(path, result, pattrs.owner());
    return result;
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

  private static class Sender {
    private final DatagramSocket socket;

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

    Sender(DatagramSocket socket) {
      this.socket = socket;
      sendThread.setDaemon(true);
      sendThread.start();
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

  private static class SendManager {
    private final Sender sender;
    private final InetSocketAddress remote;
    private final byte[] mark;
    private final ByteBuffer sendBuffer = ByteBuffer.allocate(BUFFER_LIMIT);
    private final ByteBuffer dataBuffer = ByteBuffer.allocate(2048);

    private final Charset charset = Charset.forName("utf-8");

    SendManager(Sender sender, InetSocketAddress remote, byte[] mark) {
      this.sender = sender;
      this.remote = remote;
      this.mark = mark;
    }

    void push(Path path, long size, UserPrincipal user) throws IOException {
      String p = path.toString();
      dataBuffer.put(mark);
      dataBuffer.put((byte)0);
      dataBuffer.put(p.getBytes(charset));
      dataBuffer.put((byte)0);
      dataBuffer.put(String.valueOf(user.hashCode()).getBytes(charset));
      dataBuffer.put((byte)0);
      dataBuffer.put(String.valueOf(size).getBytes(charset));
      dataBuffer.put((byte)'\n');
      finish();
    }

    private void finish() throws IOException {
      if (!haveSpace()) {
        send();
      }
      append();
    }

    void flush() throws IOException {
      send();
    }

    private void send() throws IOException {
      sendBuffer.flip();
      byte[] send = new byte[sendBuffer.remaining()];
      sendBuffer.get(send);
      DatagramPacket packet = new DatagramPacket(
          send,
          0,
          send.length,
          remote
      );
      sender.send(packet);
      sendBuffer.clear();
    }

    private void append() {
      dataBuffer.flip();
      sendBuffer.put(dataBuffer);
      dataBuffer.clear();
    }

    private boolean haveSpace() {
      return sendBuffer.remaining() > dataBuffer.remaining();
    }

    public void pushStore(FileStore fileStore) throws IOException {
      long total = fileStore.getTotalSpace();
      long free = fileStore.getUnallocatedSpace();
      long used = total - free;
      dataBuffer.put(mark);
      dataBuffer.put((byte)0);
      dataBuffer.put(String.valueOf(total).getBytes(charset));
      dataBuffer.put((byte)0);
      dataBuffer.put(String.valueOf(used).getBytes(charset));
      dataBuffer.put((byte)'\n');
      finish();
    }
  }

  @Override
  public void close() throws IOException {}
}
