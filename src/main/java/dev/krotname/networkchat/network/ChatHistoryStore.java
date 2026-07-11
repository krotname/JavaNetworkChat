package dev.krotname.networkchat.network;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.ChatProtocol;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/** File-backed JSONL message history with bounded in-memory state and safe startup on bad lines. */
public final class ChatHistoryStore {
  private static final Logger LOG = Logger.getLogger(ChatHistoryStore.class.getName());
  private static final long MAX_HISTORY_FILE_BYTES = 64L * 1024L * 1024L;
  private static final long MIN_HISTORY_FILE_BYTES = 1024L * 1024L;
  private static final int MAX_ENCODED_FRAME_BYTES = ChatProtocol.MAX_FRAME_LENGTH;
  private static final int MAX_ROOM_NAME_LENGTH = 64;
  private static final String ROOM_NAME_PATTERN = "[\\p{L}\\p{N}_-]+";

  private final Path historyFile;
  private final int historyLimit;
  private final List<ChatMessage> messages = new ArrayList<>();
  private final Object lock = new Object();
  private final boolean enabled;
  private int corruptRecordCount;

  private ChatHistoryStore(Path historyFile, int historyLimit, boolean enabled) {
    this.historyFile = historyFile;
    this.historyLimit = historyLimit;
    this.enabled = enabled;
    if (enabled) {
      load();
    }
  }

  public static ChatHistoryStore disabled() {
    return new ChatHistoryStore(null, 1, false);
  }

  public static ChatHistoryStore open(Path historyFile, int historyLimit) {
    Objects.requireNonNull(historyFile, "historyFile");
    if (historyLimit < 1 || historyLimit > ChatServerConfig.MAX_HISTORY_LIMIT) {
      throw new IllegalArgumentException("History limit is invalid");
    }
    Path safePath = historyFile.toAbsolutePath().normalize();
    try {
      validateHistoryFile(safePath);
      if (Files.exists(safePath, LinkOption.NOFOLLOW_LINKS)
          && Files.size(safePath) > maximumFileBytes(historyLimit)) {
        throw new IllegalArgumentException("Chat history file is too large");
      }
    } catch (IOException ex) {
      throw new IllegalArgumentException("Unable to validate chat history file", ex);
    }
    return new ChatHistoryStore(safePath, historyLimit, true);
  }

  public void save(ChatMessage message) {
    synchronized (lock) {
      if (!enabled || !isPersistable(message)) {
        return;
      }
      List<ChatMessage> previousMessages = new ArrayList<>(messages);
      messages.add(message);
      trimToLimit();
      if (!rewrite()) {
        messages.clear();
        messages.addAll(previousMessages);
      }
    }
  }

  public List<ChatMessage> recentRoomMessages(String roomName, int limit) {
    synchronized (lock) {
      if (limit <= 0) {
        return List.of();
      }
      List<ChatMessage> roomMessages = new ArrayList<>();
      for (ChatMessage message : messages) {
        if (message.type() == MessageType.ROOM_TEXT && roomName.equals(message.room())) {
          roomMessages.add(message);
        }
      }
      return last(roomMessages, limit);
    }
  }

  public Set<String> knownRooms() {
    synchronized (lock) {
      Set<String> rooms = new TreeSet<>();
      rooms.add(ChatMessage.GENERAL_ROOM);
      for (ChatMessage message : messages) {
        if (message.room() != null && !message.room().isBlank()) {
          rooms.add(message.room());
        }
      }
      return Collections.unmodifiableSet(rooms);
    }
  }

  public int corruptRecordCount() {
    synchronized (lock) {
      return corruptRecordCount;
    }
  }

  private void load() {
    if (!Files.exists(historyFile, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      validateHistoryFile(historyFile);
      try (FileChannel channel =
              FileChannel.open(historyFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
          InputStream input = new BufferedInputStream(Channels.newInputStream(channel))) {
        if (channel.size() > maximumFileBytes(historyLimit)) {
          throw new IOException("Chat history file exceeds the configured size limit");
        }
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean oversized = false;
        int value;
        while ((value = input.read()) >= 0) {
          if (value == '\n') {
            loadLine(line, oversized);
            line.reset();
            oversized = false;
          } else if (!oversized) {
            if (line.size() >= MAX_ENCODED_FRAME_BYTES) {
              line.reset();
              oversized = true;
            } else {
              line.write(value);
            }
          }
        }
        if (line.size() > 0 || oversized) {
          loadLine(line, oversized);
        }
      }
      trimToLimit();
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Unable to load chat history; starting with empty history", ex);
      messages.clear();
    }
  }

  private boolean rewrite() {
    Path parent = historyFile.getParent();
    Path temporaryFile = null;
    try {
      if (parent == null) {
        throw new IOException("Chat history file must have a parent directory");
      }
      validateHistoryFile(historyFile);
      Files.createDirectories(parent);
      validatePath(parent);
      List<String> encodedMessages = encodeAndTrimToFileLimit();
      temporaryFile = Files.createTempFile(parent, "chat-history-", ".tmp");
      try (BufferedWriter writer =
          Files.newBufferedWriter(
              temporaryFile,
              StandardCharsets.UTF_8,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        for (String encodedMessage : encodedMessages) {
          writer.write(encodedMessage);
          writer.write('\n');
        }
      }
      try (FileChannel channel = FileChannel.open(temporaryFile, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(
            temporaryFile,
            historyFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temporaryFile, historyFile, StandardCopyOption.REPLACE_EXISTING);
      }
      temporaryFile = null;
      return true;
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Unable to persist chat history", ex);
      return false;
    } finally {
      if (temporaryFile != null) {
        try {
          Files.deleteIfExists(temporaryFile);
        } catch (IOException ex) {
          LOG.log(Level.FINE, "Unable to remove temporary chat history file", ex);
        }
      }
    }
  }

  private void trimToLimit() {
    while (messages.size() > historyLimit) {
      messages.remove(0);
    }
  }

  private List<String> encodeAndTrimToFileLimit() {
    List<ChatMessage> encodableMessages = new ArrayList<>(messages.size());
    List<String> encodedMessages = new ArrayList<>(messages.size());
    List<Integer> encodedSizes = new ArrayList<>(messages.size());
    long totalBytes = 0;
    for (ChatMessage message : messages) {
      try {
        String encoded = ChatProtocol.encode(message);
        int encodedSize = encoded.getBytes(StandardCharsets.UTF_8).length + 1;
        encodableMessages.add(message);
        encodedMessages.add(encoded);
        encodedSizes.add(encodedSize);
        totalBytes += encodedSize;
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Unable to encode chat history message", ex);
      }
    }

    int firstRetained = 0;
    long maximumBytes = maximumFileBytes(historyLimit);
    while (totalBytes > maximumBytes && firstRetained < encodedMessages.size()) {
      totalBytes -= encodedSizes.get(firstRetained);
      firstRetained++;
    }
    messages.clear();
    messages.addAll(encodableMessages.subList(firstRetained, encodableMessages.size()));
    return new ArrayList<>(encodedMessages.subList(firstRetained, encodedMessages.size()));
  }

  private boolean isPersistable(ChatMessage message) {
    if (message == null
        || message.protocolVersion() != ChatMessage.PROTOCOL_VERSION
        || !AccountStore.isValidUserName(message.sender())
        || message.messageId() == null
        || message.messageId().isBlank()) {
      return false;
    }
    if (message.type() == MessageType.ROOM_TEXT) {
      return isValidRoomName(message.room());
    }
    return message.type() == MessageType.PRIVATE_TEXT
        && AccountStore.isValidUserName(message.recipient());
  }

  private void loadLine(ByteArrayOutputStream line, boolean oversized) {
    if (oversized) {
      corruptRecordCount++;
      return;
    }
    byte[] encodedBytes = line.toByteArray();
    int length = encodedBytes.length;
    if (length > 0 && encodedBytes[length - 1] == '\r') {
      length--;
    }
    String encoded;
    try {
      encoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(encodedBytes, 0, length))
              .toString();
    } catch (CharacterCodingException ex) {
      corruptRecordCount++;
      return;
    }
    if (encoded.isBlank()) {
      return;
    }
    try {
      ChatMessage message = migrate(ChatProtocol.decode(encoded));
      if (isPersistable(message)) {
        messages.add(message);
      } else {
        corruptRecordCount++;
      }
    } catch (IOException | RuntimeException ex) {
      corruptRecordCount++;
    }
  }

  private static void validatePath(Path path) throws IOException {
    Path current = path.getRoot();
    for (Path component : path) {
      current = current == null ? component : current.resolve(component);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      BasicFileAttributes attributes =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()) {
        throw new IOException("Chat history path must not contain symbolic links");
      }
    }
  }

  private static void validateHistoryFile(Path path) throws IOException {
    validatePath(path);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Chat history path is not a regular file");
    }
  }

  private static long maximumFileBytes(int historyLimit) {
    long configuredMaximum =
        Math.multiplyExact((long) historyLimit, ChatProtocol.MAX_FRAME_LENGTH + 2L);
    return Math.min(MAX_HISTORY_FILE_BYTES, Math.max(MIN_HISTORY_FILE_BYTES, configuredMaximum));
  }

  private static boolean isValidRoomName(String roomName) {
    return roomName != null
        && roomName.length() <= MAX_ROOM_NAME_LENGTH
        && roomName.matches(ROOM_NAME_PATTERN);
  }

  private ChatMessage migrate(ChatMessage message) {
    if (message == null || message.protocolVersion() == ChatMessage.PROTOCOL_VERSION) {
      return message;
    }
    if (message.protocolVersion() != 0) {
      return message;
    }
    if (message.type() == MessageType.TEXT || message.type() == MessageType.ROOM_TEXT) {
      return new ChatMessage(
          MessageType.ROOM_TEXT,
          message.data(),
          message.sender(),
          message.timestamp(),
          message.messageId(),
          ChatMessage.PROTOCOL_VERSION,
          roomOrGeneral(message.room()),
          null);
    }
    if (message.type() == MessageType.PRIVATE_TEXT) {
      return new ChatMessage(
          MessageType.PRIVATE_TEXT,
          message.data(),
          message.sender(),
          message.timestamp(),
          message.messageId(),
          ChatMessage.PROTOCOL_VERSION,
          null,
          message.recipient());
    }
    return message;
  }

  private String roomOrGeneral(String roomName) {
    return roomName == null || roomName.isBlank() ? ChatMessage.GENERAL_ROOM : roomName;
  }

  private List<ChatMessage> last(List<ChatMessage> values, int limit) {
    int fromIndex = Math.max(0, values.size() - limit);
    return Collections.unmodifiableList(new ArrayList<>(values.subList(fromIndex, values.size())));
  }
}
