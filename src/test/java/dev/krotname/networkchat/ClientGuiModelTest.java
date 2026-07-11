package dev.krotname.networkchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.krotname.networkchat.client.gui.ClientGuiModel;
import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import org.junit.jupiter.api.Test;

class ClientGuiModelTest {

  @Test
  void tracksUsersAndMessages() {
    ClientGuiModel model = new ClientGuiModel();
    assertTrue(model.addUser("alice"));
    assertFalse(model.addUser("alice"));
    assertTrue(model.addUser("bob"));
    ChatMessage message = new ChatMessage(MessageType.TEXT, "hello", "alice", 1L, "id-1");
    assertTrue(model.addTextMessage(message, "alice"));

    assertTrue(model.getAllUserNames().contains("alice"));
    assertEquals("hello", model.getTimelineEntries().getFirst().text());
    assertTrue(model.getTimelineEntries().getFirst().ownMessage());
    assertEquals(2, model.getAllUserNames().size());
    assertEquals(2, model.getUserCount());

    assertTrue(model.deleteUser("alice"));
    assertEquals(1, model.getAllUserNames().size());
    assertFalse(model.deleteUser("unknown"));

    model.clearUsers();
    assertEquals(0, model.getUserCount());
  }

  @Test
  void tracksRoomsAndJoinedRooms() {
    ClientGuiModel model = new ClientGuiModel();

    model.addRoom("general");
    model.joinRoom("dev");
    model.leaveRoom("dev");

    assertTrue(model.getAllRoomNames().contains("general"));
    assertTrue(model.getAllRoomNames().contains("dev"));
    assertFalse(model.isJoinedRoom("dev"));

    model.clearRooms();

    assertTrue(model.getAllRoomNames().isEmpty());
    assertTrue(model.getJoinedRoomNames().isEmpty());
  }

  @Test
  void deduplicatesTimelineByMessageId() {
    ClientGuiModel model = new ClientGuiModel();
    ChatMessage message = new ChatMessage(MessageType.TEXT, "hello", "alice", 1L, "same-id");

    assertTrue(model.addTextMessage(message, "bob"));
    assertFalse(model.addTextMessage(message, "bob"));

    assertEquals(1, model.getTimelineEntries().size());
  }

  @Test
  void boundsTimelineAndCanClearIt() {
    ClientGuiModel model = new ClientGuiModel();
    for (int i = 0; i < ClientGuiModel.MAX_TIMELINE_ENTRIES + 5; i++) {
      model.addServiceMessage("event " + i);
    }

    assertEquals(ClientGuiModel.MAX_TIMELINE_ENTRIES, model.getTimelineEntries().size());

    model.clearTimeline();

    assertEquals(0, model.getTimelineEntries().size());
  }

  @Test
  void forgetsDeduplicationIdsWhenTheirTimelineEntriesAreEvicted() {
    ClientGuiModel model = new ClientGuiModel();
    ChatMessage first = new ChatMessage(MessageType.TEXT, "first", "alice", 1L, "first-id");
    assertTrue(model.addTextMessage(first, "bob"));
    for (int index = 0; index < ClientGuiModel.MAX_TIMELINE_ENTRIES; index++) {
      model.addTextMessage(
          new ChatMessage(MessageType.TEXT, "message " + index, "alice", 2L, "id-" + index), "bob");
    }

    assertTrue(model.addTextMessage(first, "bob"));
    assertEquals(ClientGuiModel.MAX_TIMELINE_ENTRIES, model.getTimelineEntries().size());
  }

  @Test
  void exportsValidJsonAndNeutralizesSpreadsheetFormulas() throws Exception {
    ClientGuiModel model = new ClientGuiModel();
    model.addServiceMessage("tab\tcarriage\rbackspace\bform-feed\f");
    model.addTextMessage(new ChatMessage(MessageType.TEXT, "=2+2", "alice", 1L, "formula"), "bob");

    String json = model.exportTimelineAsJson();
    String csv = model.exportTimelineAsCsv();

    assertEquals(2, new ObjectMapper().readTree(json).size());
    assertTrue(csv.contains("\"'=2+2\""));
  }

  @Test
  void searchesAndExportsTimeline() {
    ClientGuiModel model = new ClientGuiModel();
    model.addTextMessage(ChatMessage.roomText("deploy ready", "alice", "dev"), "bob");
    model.addTextMessage(ChatMessage.privateText("secret note", "bob", "alice"), "alice");
    model.addTextMessage(
        new ChatMessage(MessageType.ROOM_TEXT, "dated event", "carol", 1_780_000_000_000L, "date"),
        "alice");

    assertEquals(1, model.searchTimeline("deploy").size());
    assertEquals(1, model.searchTimeline("secret").size());
    assertEquals(1, model.searchTimeline("2026-05-28").size());
    assertEquals(1, model.searchTimeline("1780000000000").size());

    String json = model.exportTimelineAsJson();
    String csv = model.exportTimelineAsCsv();

    assertTrue(json.contains("deploy ready"));
    assertTrue(json.contains("\"room\":\"dev\""));
    assertTrue(csv.contains("secret note"));
    assertTrue(csv.startsWith("type,messageId"));
  }
}
