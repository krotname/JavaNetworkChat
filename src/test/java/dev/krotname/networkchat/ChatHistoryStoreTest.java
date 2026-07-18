package dev.krotname.networkchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.krotname.networkchat.network.ChatHistoryStore;
import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.ChatProtocol;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatHistoryStoreTest {

  @TempDir private Path tempDir;

  @Test
  void persistsAndRotatesRoomMessages() {
    Path historyFile = tempDir.resolve("history.jsonl");
    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 2);

    store.save(ChatMessage.roomText("one", "alice", "general"));
    store.save(ChatMessage.roomText("two", "alice", "general"));
    store.save(ChatMessage.roomText("three", "alice", "general"));

    ChatHistoryStore reloaded = ChatHistoryStore.open(historyFile, 2);

    assertEquals(2, reloaded.recentRoomMessages("general", 10).size());
    assertEquals("two", reloaded.recentRoomMessages("general", 10).getFirst().data());
    assertEquals("three", reloaded.recentRoomMessages("general", 10).getLast().data());
  }

  @Test
  void ignoresCorruptLinesOnStartup() throws Exception {
    Path historyFile = tempDir.resolve("history.jsonl");
    ChatMessage valid = ChatMessage.roomText("valid", "alice", "general");
    Files.writeString(
        historyFile, "not-json\n" + ChatProtocol.encode(valid) + "\n", StandardCharsets.UTF_8);

    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 10);

    assertEquals(1, store.corruptRecordCount());
    assertEquals(1, store.recentRoomMessages("general", 10).size());
    assertTrue(store.knownRooms().contains("general"));
  }

  @Test
  void migratesLegacyTextHistoryToCurrentRoomFrames() throws Exception {
    Path historyFile = tempDir.resolve("legacy-history.jsonl");
    Files.writeString(
        historyFile,
        """
        {"type":"TEXT","data":"legacy","sender":"alice","timestamp":1,"messageId":"old"}
        """
            .strip(),
        StandardCharsets.UTF_8);

    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 10);
    ChatMessage migrated = store.recentRoomMessages("general", 10).getFirst();

    assertEquals("legacy", migrated.data());
    assertEquals(ChatMessage.PROTOCOL_VERSION, migrated.protocolVersion());
    assertEquals("general", migrated.room());
  }

  @Test
  void boundsIndividualHistoryFramesAndRejectsDirectories() throws Exception {
    Path historyFile = tempDir.resolve("oversized-line.jsonl");
    Files.writeString(
        historyFile, "x".repeat(ChatProtocol.MAX_FRAME_LENGTH + 1) + "\n", StandardCharsets.UTF_8);

    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 10);

    assertEquals(1, store.corruptRecordCount());
    assertThrows(IllegalArgumentException.class, () -> ChatHistoryStore.open(tempDir, 10));
  }

  @Test
  void atomicRewriteLeavesNoTemporaryFiles() throws Exception {
    Path historyFile = tempDir.resolve("atomic.jsonl");
    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 10);

    store.save(ChatMessage.roomText("saved", "alice", "general"));

    assertEquals(
        1, ChatHistoryStore.open(historyFile, 10).recentRoomMessages("general", 10).size());
    try (var children = Files.list(tempDir)) {
      assertFalse(
          children.anyMatch(
              path -> {
                Path fileName = path.getFileName();
                return fileName != null && fileName.toString().endsWith(".tmp");
              }));
    }
  }

  @Test
  void failedRewriteDoesNotExposeAnUnpersistedMessageInMemory() throws Exception {
    Path historyFile = tempDir.resolve("becomes-a-directory.jsonl");
    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 10);
    Files.createDirectory(historyFile);

    store.save(ChatMessage.roomText("not persisted", "alice", "general"));

    assertTrue(store.recentRoomMessages("general", 10).isEmpty());
    assertTrue(Files.isDirectory(historyFile));
  }

  @Test
  void malformedUtf8OnlyInvalidatesItsOwnHistoryRecord() throws Exception {
    Path historyFile = tempDir.resolve("malformed-utf8.jsonl");
    ChatMessage first = ChatMessage.roomText("first", "alice", "general");
    ChatMessage second = ChatMessage.roomText("second", "alice", "general");
    try (OutputStream output = Files.newOutputStream(historyFile)) {
      output.write((ChatProtocol.encode(first) + "\n").getBytes(StandardCharsets.UTF_8));
      output.write("{\"type\":\"ROOM_TEXT\",\"data\":\"bad".getBytes(StandardCharsets.UTF_8));
      output.write(new byte[] {(byte) 0xc3, 0x28});
      output.write("\"}\n".getBytes(StandardCharsets.UTF_8));
      output.write((ChatProtocol.encode(second) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    ChatHistoryStore store = ChatHistoryStore.open(historyFile, 10);

    assertEquals(1, store.corruptRecordCount());
    assertEquals(2, store.recentRoomMessages("general", 10).size());
  }
}
