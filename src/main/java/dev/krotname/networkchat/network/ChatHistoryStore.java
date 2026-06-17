package dev.krotname.networkchat.network;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.ChatProtocol;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    return new ChatHistoryStore(historyFile, historyLimit, true);
  }

  public void save(ChatMessage message) {
    synchronized (lock) {
      if (!enabled || !isPersistable(message)) {
        return;
      }
      messages.add(message);
      trimToLimit();
      rewrite();
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
    if (!Files.exists(historyFile)) {
      return;
    }
    try {
      for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
        if (line.isBlank()) {
          continue;
        }
        try {
          ChatMessage message = migrate(ChatProtocol.decode(line));
          if (isPersistable(message)) {
            messages.add(message);
          }
        } catch (IOException | RuntimeException ex) {
          corruptRecordCount++;
        }
      }
      trimToLimit();
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Unable to load chat history; starting with empty history", ex);
      messages.clear();
    }
  }

  private void rewrite() {
    Path parent = historyFile.getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Unable to create chat history directory", ex);
        return;
      }
    }
    List<String> lines = new ArrayList<>();
    for (ChatMessage message : messages) {
      try {
        lines.add(ChatProtocol.encode(message));
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Unable to encode chat history message", ex);
      }
    }
    try {
      Files.write(
          historyFile,
          lines,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Unable to persist chat history", ex);
    }
  }

  private void trimToLimit() {
    while (messages.size() > historyLimit) {
      messages.remove(0);
    }
  }

  private boolean isPersistable(ChatMessage message) {
    return message != null
        && (message.type() == MessageType.ROOM_TEXT || message.type() == MessageType.PRIVATE_TEXT);
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
