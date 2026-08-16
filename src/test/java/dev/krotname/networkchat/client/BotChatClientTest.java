package dev.krotname.networkchat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BotChatClientTest {

  @Test
  void answersCommandUsingStructuredSender() {
    BotChatClient bot =
        new BotChatClient(Clock.fixed(Instant.parse("2026-06-10T12:34:56Z"), ZoneId.of("UTC")));

    String answer = bot.answerForCommand(ChatMessage.text("год", "alice"));

    assertEquals("Информация для alice: 2026", answer);
  }

  @Test
  void answersPrivateCommandsPrivately() {
    BotChatClient bot =
        new BotChatClient(Clock.fixed(Instant.parse("2026-06-10T12:34:56Z"), ZoneId.of("UTC")));

    ChatMessage reply = bot.replyTo(ChatMessage.privateText("год", "alice", "bot"), "answer");

    assertEquals(MessageType.PRIVATE_TEXT, reply.type());
    assertEquals("alice", reply.recipient());
    assertEquals("answer", reply.data());
  }

  @Test
  void answersRoomCommandsInTheSameRoom() {
    BotChatClient bot =
        new BotChatClient(Clock.fixed(Instant.parse("2026-06-10T12:34:56Z"), ZoneId.of("UTC")));

    ChatMessage reply = bot.replyTo(ChatMessage.roomText("год", "alice", "team"), "answer");

    assertEquals(MessageType.ROOM_TEXT, reply.type());
    assertEquals("team", reply.room());
  }

  @Test
  void ignoresUnknownOrSenderlessCommands() {
    BotChatClient bot =
        new BotChatClient(Clock.fixed(Instant.parse("2026-06-10T12:34:56Z"), ZoneId.of("UTC")));

    assertNull(bot.answerForCommand(ChatMessage.text("unknown", "alice")));
    assertNull(bot.answerForCommand(ChatMessage.text("год", null)));
  }
}
