package dev.krotname.networkchat.client.gui;

import dev.krotname.networkchat.protocol.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** GUI state for users and the local chat timeline. */
public final class ClientGuiModel {
  public static final int MAX_TIMELINE_ENTRIES = 500;

  private final Set<String> allUserNames = new HashSet<>();
  private final Set<String> allRoomNames = new HashSet<>();
  private final Set<String> joinedRoomNames = new HashSet<>();
  private final Set<String> seenMessageIds = new HashSet<>();
  private final List<TimelineEntry> timelineEntries = new ArrayList<>();

  /** Returns a defensive copy to prevent external mutation by UI tests or clients. */
  public synchronized Set<String> getAllUserNames() {
    return Collections.unmodifiableSet(new HashSet<>(allUserNames));
  }

  public synchronized List<TimelineEntry> getTimelineEntries() {
    return Collections.unmodifiableList(new ArrayList<>(timelineEntries));
  }

  public synchronized List<TimelineEntry> searchTimeline(String query) {
    if (query == null || query.isBlank()) {
      return getTimelineEntries();
    }
    String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
    List<TimelineEntry> matches = new ArrayList<>();
    for (TimelineEntry entry : timelineEntries) {
      if (containsIgnoreCase(entry.text(), normalizedQuery)
          || containsIgnoreCase(entry.sender(), normalizedQuery)
          || containsIgnoreCase(entry.room(), normalizedQuery)
          || containsIgnoreCase(entry.recipient(), normalizedQuery)
          || containsIgnoreCase(timestampText(entry), normalizedQuery)) {
        matches.add(entry);
      }
    }
    return Collections.unmodifiableList(matches);
  }

  public synchronized String exportTimelineAsJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < timelineEntries.size(); i++) {
      TimelineEntry entry = timelineEntries.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{")
          .append("\"type\":\"")
          .append(entry.type())
          .append("\",")
          .append("\"messageId\":")
          .append(jsonString(entry.messageId()))
          .append(',')
          .append("\"sender\":")
          .append(jsonString(entry.sender()))
          .append(',')
          .append("\"text\":")
          .append(jsonString(entry.text()))
          .append(',')
          .append("\"timestamp\":")
          .append(entry.timestamp())
          .append(',')
          .append("\"ownMessage\":")
          .append(entry.ownMessage())
          .append(',')
          .append("\"room\":")
          .append(jsonString(entry.room()))
          .append(',')
          .append("\"recipient\":")
          .append(jsonString(entry.recipient()))
          .append("}");
    }
    sb.append("]");
    return sb.toString();
  }

  public synchronized String exportTimelineAsCsv() {
    StringBuilder sb =
        new StringBuilder("type,messageId,sender,text,timestamp,ownMessage,room,recipient\n");
    for (TimelineEntry entry : timelineEntries) {
      sb.append(csv(entry.type().name()))
          .append(',')
          .append(csv(entry.messageId()))
          .append(',')
          .append(csv(entry.sender()))
          .append(',')
          .append(csv(entry.text()))
          .append(',')
          .append(entry.timestamp())
          .append(',')
          .append(entry.ownMessage())
          .append(',')
          .append(csv(entry.room()))
          .append(',')
          .append(csv(entry.recipient()))
          .append('\n');
    }
    return sb.toString();
  }

  public synchronized Set<String> getAllRoomNames() {
    return Collections.unmodifiableSet(new TreeSet<>(allRoomNames));
  }

  public synchronized Set<String> getJoinedRoomNames() {
    return Collections.unmodifiableSet(new TreeSet<>(joinedRoomNames));
  }

  public synchronized boolean addTextMessage(ChatMessage message, String currentUserName) {
    Objects.requireNonNull(message, "message");
    boolean ownMessage =
        currentUserName != null
            && message.sender() != null
            && currentUserName.equals(message.sender());
    TimelineEntry entry = TimelineEntry.text(message, ownMessage);
    String messageId = message.messageId();
    if (messageId != null && !seenMessageIds.add(messageId)) {
      return false;
    }
    appendTimelineEntry(entry);
    return true;
  }

  public synchronized void addServiceMessage(String text) {
    appendTimelineEntry(TimelineEntry.service(text));
  }

  public synchronized void clearTimeline() {
    timelineEntries.clear();
    seenMessageIds.clear();
  }

  public synchronized boolean addUser(String newUserName) {
    return allUserNames.add(newUserName);
  }

  public synchronized boolean deleteUser(String userName) {
    return allUserNames.remove(userName);
  }

  public synchronized int getUserCount() {
    return allUserNames.size();
  }

  public synchronized void clearUsers() {
    allUserNames.clear();
  }

  public synchronized void addRoom(String roomName) {
    allRoomNames.add(roomName);
  }

  public synchronized void joinRoom(String roomName) {
    allRoomNames.add(roomName);
    joinedRoomNames.add(roomName);
  }

  public synchronized void leaveRoom(String roomName) {
    joinedRoomNames.remove(roomName);
  }

  public synchronized boolean isJoinedRoom(String roomName) {
    return joinedRoomNames.contains(roomName);
  }

  public synchronized void clearRooms() {
    allRoomNames.clear();
    joinedRoomNames.clear();
  }

  private void appendTimelineEntry(TimelineEntry entry) {
    timelineEntries.add(entry);
    if (timelineEntries.size() > MAX_TIMELINE_ENTRIES) {
      TimelineEntry removed = timelineEntries.remove(0);
      if (removed.messageId() != null) {
        seenMessageIds.remove(removed.messageId());
      }
    }
  }

  private boolean containsIgnoreCase(String value, String normalizedQuery) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
  }

  private String timestampText(TimelineEntry entry) {
    return entry.timestamp() + " " + Instant.ofEpochMilli(entry.timestamp());
  }

  private String jsonString(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }

  private String csv(String value) {
    if (value == null) {
      return "";
    }
    String safeValue = startsSpreadsheetFormula(value) ? "'" + value : value;
    return "\"" + safeValue.replace("\"", "\"\"") + "\"";
  }

  private boolean startsSpreadsheetFormula(String value) {
    int index = 0;
    while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
      index++;
    }
    if (index >= value.length()) {
      return false;
    }
    char first = value.charAt(index);
    return first == '=' || first == '+' || first == '-' || first == '@';
  }
}
