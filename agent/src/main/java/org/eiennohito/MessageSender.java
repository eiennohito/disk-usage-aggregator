package org.eiennohito;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author eiennohito
 * @since 2016/02/18
 */
public interface MessageSender {
  void sendBuffer(ByteBuffer buf) throws IOException;
}
