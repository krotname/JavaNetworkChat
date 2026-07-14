package dev.krotname.networkchat.network;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.ChatProtocol;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** A small socket abstraction that serializes protocol messages over text frames. */
public final class ChatConnection implements Closeable {
  public static final int MAX_FRAME_LENGTH = ChatProtocol.MAX_FRAME_LENGTH;
  private final Socket socket;
  private final BufferedWriter writer;
  private final InputStream input;

  public ChatConnection(Socket socket) throws IOException {
    this.socket = Objects.requireNonNull(socket, "socket");
    this.writer =
        new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    this.input = new BufferedInputStream(socket.getInputStream());
  }

  public void send(ChatMessage message) throws IOException {
    synchronized (writer) {
      writer.write(ChatProtocol.encode(message));
      writer.newLine();
      writer.flush();
    }
  }

  /**
   * Reads exactly one protocol frame from the socket. If the remote endpoint closed the connection,
   * EOF is translated into IOException so all callers follow one error-handling path.
   */
  public ChatMessage receive() throws IOException {
    synchronized (input) {
      return decodeFrame(readFrame(null));
    }
  }

  /** Reads one frame with a total deadline, not a timeout that resets after every received byte. */
  public ChatMessage receive(Duration timeout) throws IOException {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Receive timeout must be positive");
    }
    synchronized (input) {
      int originalTimeout = socket.getSoTimeout();
      try {
        return decodeFrame(readFrame(timeout));
      } finally {
        restoreSocketTimeout(originalTimeout);
      }
    }
  }

  private ChatMessage decodeFrame(String line) throws IOException {
    return ChatProtocol.decode(line);
  }

  private String readFrame(Duration timeout) throws IOException {
    long deadlineNanos =
        timeout == null ? Long.MAX_VALUE : System.nanoTime() + validatedTimeoutNanos(timeout);
    ByteArrayOutputStream frame = new ByteArrayOutputStream(Math.min(1024, MAX_FRAME_LENGTH));
    while (true) {
      if (timeout != null) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
          throw new java.net.SocketTimeoutException("Protocol frame deadline exceeded");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
      }
      int value = input.read();
      if (value < 0) {
        if (frame.size() == 0) {
          throw new EOFException("Connection closed by peer");
        }
        break;
      }
      if (value == '\n') {
        break;
      }
      if (frame.size() >= MAX_FRAME_LENGTH) {
        throw new IOException("Protocol frame is too large");
      }
      frame.write(value);
    }
    byte[] encodedFrame = frame.toByteArray();
    int length = encodedFrame.length;
    if (length > 0 && encodedFrame[length - 1] == '\r') {
      length--;
    }
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(encodedFrame, 0, length))
        .toString();
  }

  private static long validatedTimeoutNanos(Duration timeout) {
    long timeoutNanos;
    try {
      timeoutNanos = timeout.toNanos();
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Receive timeout is too large", ex);
    }
    if (timeoutNanos > TimeUnit.MILLISECONDS.toNanos(Integer.MAX_VALUE)) {
      throw new IllegalArgumentException("Receive timeout is too large");
    }
    return timeoutNanos;
  }

  private void restoreSocketTimeout(int originalTimeout) {
    if (socket.isClosed()) {
      return;
    }
    try {
      socket.setSoTimeout(originalTimeout);
    } catch (IOException ignored) {
      // Preserve the frame result or the original read failure.
    }
  }

  @Override
  public void close() throws IOException {
    IOException first = null;
    try {
      socket.close();
    } catch (IOException ex) {
      first = ex;
    }
    try {
      writer.close();
    } catch (IOException ex) {
      if (first == null) {
        first = ex;
      }
    }
    try {
      input.close();
    } catch (IOException ex) {
      if (first == null) {
        first = ex;
      }
    }
    if (first != null) {
      throw first;
    }
  }
}
