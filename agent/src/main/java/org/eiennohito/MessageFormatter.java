package org.eiennohito;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * Formats messages.
 *
 * A message consists of several ENTRIES.
 * An entry consist of a TYPE (byte from 0 to 127),
 * a list of FIELDS separated by 0x00 bytes and
 * a 0xff byte as message end flag.
 *
 * @author eiennohito
 * @since 2016/02/18
 */
public class MessageFormatter implements MessageFlags {
  private final ByteBuffer buffer;
  private final ByteBuffer message;
  private final byte[] mark;
  private final MessageSender sender;

  private final int payloadMax;

  private final Object sync = new Object();

  public MessageFormatter(MessageSender sender, int medSize, int msgSize, String mark) {
    this.sender = sender;
    buffer = ByteBuffer.allocate(medSize);
    message = ByteBuffer.allocate(msgSize);
    this.mark = mark.getBytes(UTF8);
    if (msgSize <= medSize) {
      throw new RuntimeException("message maximum size should not be larger than of buffer buffer");
    }

    int headerSize = this.mark.length + 3;
    int minMsgSize = 20 * headerSize;

    if (msgSize < minMsgSize) {
      throw new RuntimeException("message size should be at least " + minMsgSize);
    }

    payloadMax = msgSize - headerSize;

    appendHead();
  }

  public void flush() throws IOException {
    synchronized (sync) {
      message.flip();
      sender.sendBuffer(message);
      message.clear();
      appendHead();
    }
  }

  private void appendEntry() throws IOException {
    synchronized (sync) {
      buffer.flip();
      if (canAppendMsg()) {
        flush();
      }
      message.put(buffer);
    }
  }

  private synchronized boolean canAppendMsg() {
    return message.remaining() < buffer.remaining();
  }

  /**
   * Header
   * TYPE: 0
   * FIELDS:
   *  mark
   */
  private void appendHead() {
    message.put(ENT_HEADER);
    message.put(SEP_FLD);
    message.put(mark);
    message.put(SEP_ENT);
  }

  public void appendOverall(long totalBytes, long usedBytes) throws IOException {
    buffer.clear();
    buffer.put(ENT_OVERALL);
    buffer.put(SEP_FLD);
    formatPositive(buffer, totalBytes);
    buffer.put(SEP_FLD);
    formatPositive(buffer, usedBytes);
    buffer.put(SEP_ENT);
    appendEntry();
  }

  private final CharsetEncoder utf8enc = UTF8.newEncoder();

  public void appendDirectoryDown(String name, long id, long parent, int userId) throws IOException {
    buffer.clear();

    buffer.put(ENT_DIRECTORY_DOWN);
    buffer.put(SEP_FLD);
    utf8enc.encode(CharBuffer.wrap(name), buffer, true);
    buffer.put(SEP_FLD);
    formatPositive(buffer, id);
    buffer.put(SEP_FLD);
    formatPositive(buffer, parent);
    buffer.put(SEP_FLD);
    formatPositive(buffer, userId);
    buffer.put(SEP_ENT);

    appendEntry();
  }

  public void appendDirectoryUp(long id, long ownSize, long filesOwn, long recSize, long recFiles) throws IOException {
    buffer.clear();

    buffer.put(ENT_DIRECTORY_UP);
    buffer.put(SEP_FLD);
    formatPositive(buffer, id);
    buffer.put(SEP_FLD);
    formatPositive(buffer, ownSize);
    buffer.put(SEP_FLD);
    formatPositive(buffer, filesOwn);
    buffer.put(SEP_FLD);
    formatPositive(buffer, recSize);
    buffer.put(SEP_FLD);
    formatPositive(buffer, recFiles);
    buffer.put(SEP_ENT);

    appendEntry();
  }

  public void appendError(long id, String content) throws IOException {
    buffer.clear();

    buffer.put(ENT_ERROR);
    buffer.put(SEP_FLD);
    formatPositive(buffer, id);
    buffer.put(SEP_FLD);
    CharBuffer wrapped = CharBuffer.wrap(content);
    CoderResult res = utf8enc.encode(wrapped, buffer, true);
    if (!res.isOverflow()) {
      buffer.put(SEP_ENT);
      appendEntry();
    } else {
      synchronized (sync) {
        flush();
        int canPut = payloadMax - 1;
        while (canPut > 0) {
          buffer.flip();
          if (canPut > buffer.remaining()) {
            canPut -= buffer.remaining();
          } else {
            buffer.limit(canPut);
          }
          message.put(buffer);
          buffer.clear();
          utf8enc.encode(wrapped, buffer, true);
        }

        appendEntry();
        message.put(SEP_ENT);
      }
    }
  }

  private final static Charset UTF8 = Charset.forName("utf-8");

  /**
   * ASCII code of 0 character
   */
  private final static byte ZERO = 0x30;

  private final static byte[] CHARS = {
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
      'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
      'u', 'v', 'w', 'x', 'y', 'z'
  };

  private static final int CHARLEN = CHARS.length;

  /**
   * Does base 36 formatting of a long
   * @param buf
   * @param value
   * @return
   */
  static int formatPositive(ByteBuffer buf, long value) {
    assert (value >= 0);

    if (value < CHARLEN) {
      buf.put(CHARS[(int) value]);
      return 1;
    }

    int printed = 0;

    while (value != 0) {
      long rem = value % CHARLEN;
      long val2 = value / CHARLEN;

      buf.put(CHARS[(int) rem]);
      value = val2;
      printed += 1;
    }


    int pos = buf.position();
    int start = pos - printed;

    pos -= 1;
    int toReverse = printed / 2;

    for (int i = 0; i < toReverse; ++i) {
      byte x = buf.get(start + i);
      buf.put(start + i, buf.get(pos - i));
      buf.put(pos - i, x);
    }

    return printed;
  }

}
