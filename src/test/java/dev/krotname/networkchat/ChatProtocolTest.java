package dev.krotname.networkchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.ChatProtocol;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ChatProtocolTest {

  @Test
  void roundTripTextMessage() throws IOException {
    ChatMessage message = ChatMessage.withData(MessageType.TEXT, "hello", "alice");
    String json = ChatProtocol.encode(message);
    ChatMessage restored = ChatProtocol.decode(json);
    assertEquals(message, restored);
  }

  @Test
  void textMessageKeepsSenderOutOfPayload() {
    ChatMessage message = ChatMessage.text("hello", "alice");
    assertEquals("hello", message.data());
    assertEquals("alice", message.sender());
    assertEquals(ChatMessage.GENERAL_ROOM, message.room());
    assertEquals(MessageType.ROOM_TEXT, message.type());
  }

  @Test
  void roundTripsRoomAndPrivateMetadata() throws IOException {
    ChatMessage roomMessage = ChatMessage.roomText("hello", "alice", "dev");
    ChatMessage privateMessage = ChatMessage.privateText("secret", "alice", "bob");

    assertEquals(roomMessage, ChatProtocol.decode(ChatProtocol.encode(roomMessage)));
    assertEquals(privateMessage, ChatProtocol.decode(ChatProtocol.encode(privateMessage)));
    assertEquals("dev", roomMessage.room());
    assertEquals("bob", privateMessage.recipient());
    assertEquals(ChatMessage.PROTOCOL_VERSION, privateMessage.protocolVersion());
  }

  @Test
  void rejectsOverlongPayload() {
    String overlong = "a".repeat(ChatMessage.MAX_DATA_LENGTH + 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> ChatMessage.withData(MessageType.TEXT, overlong, "alice"));
  }

  @Test
  void rejectsBlankTextPayload() {
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.text(null, "alice"));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.text(" ", "alice"));
    assertThrows(
        IOException.class,
        () ->
            ChatProtocol.decode(
                """
                {"type":"TEXT","data":"","sender":"alice","timestamp":1,"messageId":"id"}
                """));
  }

  @Test
  void decodeRejectsInvalidFrame() {
    assertThrows(IOException.class, () -> ChatProtocol.decode("{\"broken\":true}"));
  }

  @Test
  void decodeRejectsDuplicateAndOversizedFrames() {
    assertThrows(
        IOException.class,
        () ->
            ChatProtocol.decode(
                "{\"type\":\"TEXT\",\"type\":\"TEXT\",\"data\":\"hello\","
                    + "\"sender\":\"alice\",\"timestamp\":1,\"messageId\":\"id\"}"));
    assertThrows(
        IOException.class,
        () -> ChatProtocol.decode("x".repeat(ChatProtocol.MAX_FRAME_LENGTH + 1)));
  }

  @Test
  void decodeRejectsTrailingJsonTokens() throws IOException {
    String validFrame = ChatProtocol.encode(ChatMessage.text("hello", "alice"));

    assertThrows(IOException.class, () -> ChatProtocol.decode(validFrame + " {}"));
  }

  @Test
  void messageRejectsUnsafeMetadataAndTimestamp() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatMessage(
                MessageType.ROOM_TEXT,
                "hello",
                "alice\nadmin",
                1,
                "id",
                ChatMessage.PROTOCOL_VERSION,
                "general",
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatMessage(
                MessageType.ROOM_TEXT,
                "hello",
                "alice",
                -1,
                "id",
                ChatMessage.PROTOCOL_VERSION,
                "general",
                null));
  }
}
