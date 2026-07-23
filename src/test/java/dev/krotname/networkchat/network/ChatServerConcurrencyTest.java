package dev.krotname.networkchat.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class ChatServerConcurrencyTest {

  @Test
  void staleCleanupCannotRemoveAReplacementSession() throws Exception {
    try (ChatServer server = new ChatServer(0)) {
      server.start();
      try (ClientSession first = connect(server, "alice")) {
        assertFalse(first.socket().isClosed());
        ChatConnection staleServerConnection = sessions(server).get("alice");
        assertTrue(server.removeSession("alice", staleServerConnection));

        try (ClientSession replacementClient = connect(server, "alice")) {
          assertFalse(replacementClient.socket().isClosed());
          ChatConnection replacementServerConnection = sessions(server).get("alice");

          assertFalse(server.removeSession("alice", staleServerConnection));
          assertSame(replacementServerConnection, sessions(server).get("alice"));
          assertTrue(server.getConnectedUsers().contains("alice"));
        }
      }
    }
  }

  @Test
  void closedServerCannotLeakABoundSocketThroughRestart() {
    ChatServer server = new ChatServer(0);
    server.close();

    assertThrows(IllegalStateException.class, server::start);
    assertFalse(server.isRunning());
    assertEquals(0, server.getPort());
  }

  @Test
  void closingServerClearsSessionsAndClosesTheirSockets() throws Exception {
    ChatServer server = new ChatServer(0);
    server.start();
    try (ClientSession alice = connect(server, "alice")) {
      server.close();

      assertTrue(server.getConnectedUsers().isEmpty());
      assertThrows(
          IOException.class,
          () -> {
            while (true) {
              alice.connection().receive(Duration.ofSeconds(1));
            }
          });
    } finally {
      server.close();
    }
  }

  @Test
  void roomCreationStopsAtTheConfiguredServerLimit() throws Exception {
    try (ChatServer server = new ChatServer(ChatServerConfig.ofPort(0).withMaxRooms(2))) {
      server.start();
      try (ClientSession alice = connect(server, "alice")) {
        alice.connection().send(ChatMessage.roomJoin("first"));
        receiveUntilRoom(alice, "first");

        alice.connection().send(ChatMessage.roomJoin("overflow"));
        ChatMessage response = receiveUntil(alice, MessageType.ERROR);

        assertEquals("Room limit reached", response.data());
        assertFalse(server.getRooms().contains("overflow"));
        assertEquals(2, server.getRooms().size());
      }
    }
  }

  @Test
  void roomCreationIsThrottledPerConnection() throws Exception {
    ChatServerConfig config =
        ChatServerConfig.ofPort(0).withRateLimit(new RateLimitConfig(60, 20, 1, 0.001));
    try (ChatServer server = new ChatServer(config)) {
      server.start();
      try (ClientSession alice = connect(server, "alice")) {
        alice.connection().send(ChatMessage.roomJoin("first"));
        receiveUntilRoom(alice, "first");

        alice.connection().send(ChatMessage.roomJoin("second"));
        ChatMessage response = receiveUntil(alice, MessageType.ERROR);

        assertEquals("Too many new rooms, slow down", response.data());
        assertFalse(server.getRooms().contains("second"));
        assertTrue(server.getConnectedUsers().contains("alice"));
      }
    }
  }

  @Test
  void inboundFramesAreThrottledPerConnection() throws Exception {
    ChatServerConfig config =
        ChatServerConfig.ofPort(0).withRateLimit(new RateLimitConfig(1, 0.001, 10, 0.5));
    try (ChatServer server = new ChatServer(config)) {
      server.start();
      try (ClientSession alice = connect(server, "alice")) {
        alice.connection().send(ChatMessage.roomJoin("first"));
        receiveUntilRoom(alice, "first");

        alice.connection().send(ChatMessage.text("throttled", "alice"));
        ChatMessage response = receiveUntil(alice, MessageType.ERROR);

        assertEquals("Too many requests, slow down", response.data());
        assertTrue(server.getConnectedUsers().contains("alice"));
      }
    }
  }

  @Test
  void emptyRoomIsReclaimedWhenTheLastMemberLeaves() throws Exception {
    try (ChatServer server = new ChatServer(0)) {
      server.start();
      try (ClientSession alice = connect(server, "alice");
          ClientSession observer = connect(server, "observer")) {
        alice.connection().send(ChatMessage.roomJoin("dev"));
        receiveUntilRoom(alice, "dev");
        assertTrue(server.getRooms().contains("dev"));

        alice.connection().send(ChatMessage.roomLeave("dev"));
        ChatMessage removed = receiveUntilRoomEvent(observer, MessageType.ROOM_REMOVED, "dev");

        assertEquals("dev", removed.room());
        assertFalse(server.getRooms().contains("dev"));
        assertTrue(server.getRooms().contains(ChatMessage.GENERAL_ROOM));
      }
    }
  }

  @Test
  void emptyRoomIsReclaimedWhenTheLastMemberDisconnects() throws Exception {
    try (ChatServer server = new ChatServer(0)) {
      server.start();
      try (ClientSession observer = connect(server, "observer")) {
        try (ClientSession alice = connect(server, "alice")) {
          alice.connection().send(ChatMessage.roomJoin("dev"));
          receiveUntilRoom(alice, "dev");
          assertTrue(server.getRooms().contains("dev"));
        }

        ChatMessage removed = receiveUntilRoomEvent(observer, MessageType.ROOM_REMOVED, "dev");

        assertEquals("dev", removed.room());
        assertFalse(server.getRooms().contains("dev"));
      }
    }
  }

  @Test
  void generalRoomIsNeverReclaimed() throws Exception {
    try (ChatServer server = new ChatServer(0)) {
      server.start();
      try (ClientSession alice = connect(server, "alice")) {
        alice.connection().send(ChatMessage.roomLeave(ChatMessage.GENERAL_ROOM));
        ChatMessage response = receiveUntil(alice, MessageType.ERROR);

        assertEquals("Cannot leave general room", response.data());
      }

      Awaitility.await("wait for the disconnect to be processed")
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertTrue(server.getConnectedUsers().isEmpty()));
      assertTrue(server.getRooms().contains(ChatMessage.GENERAL_ROOM));
    }
  }

  @Test
  void privateHealthTextIsDeliveredInsteadOfBeingTreatedAsAnAdminCommand() throws Exception {
    try (ChatServer server = new ChatServer(0)) {
      server.start();
      try (ClientSession alice = connect(server, "alice");
          ClientSession bob = connect(server, "bob")) {
        alice.connection().send(ChatMessage.privateText("/health", "ignored", "bob"));

        ChatMessage response = receiveUntil(bob, MessageType.PRIVATE_TEXT);

        assertEquals("/health", response.data());
        assertEquals("alice", response.sender());
      }
    }
  }

  @Test
  void failedPrivateDeliveryRemovesTheRecipientInsteadOfTheSender() throws Exception {
    try (ChatServer server = new ChatServer(0);
        ServerSocket brokenSocketServer = new ServerSocket(0)) {
      server.start();
      try (ClientSession alice = connect(server, "alice");
          Socket brokenClient = new Socket("127.0.0.1", brokenSocketServer.getLocalPort());
          Socket brokenPeer = brokenSocketServer.accept()) {
        alice.connection().send(ChatMessage.roomJoin("initialization_barrier"));
        receiveUntilRoom(alice, "initialization_barrier");
        assertTrue(brokenClient.isConnected());
        ChatConnection brokenRecipient = new ChatConnection(brokenPeer);
        brokenRecipient.close();
        sessions(server).put("bob", brokenRecipient);

        alice.connection().send(ChatMessage.privateText("hello", "ignored", "bob"));
        ChatMessage response = receiveUntil(alice, MessageType.ERROR);

        assertTrue(response.data().contains("disconnected"));
        assertTrue(server.getConnectedUsers().contains("alice"));
        assertFalse(server.getConnectedUsers().contains("bob"));
      }
    }
  }

  private static ClientSession connect(ChatServer server, String userName) throws Exception {
    Socket socket = new Socket("127.0.0.1", server.getPort());
    socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(2));
    ChatConnection connection = new ChatConnection(socket);
    try {
      assertEquals(MessageType.NAME_REQUEST, connection.receive(Duration.ofSeconds(2)).type());
      connection.send(ChatMessage.withData(MessageType.USER_NAME, userName, null));
      ChatMessage response;
      do {
        response = connection.receive(Duration.ofSeconds(2));
      } while (response.type() != MessageType.NAME_ACCEPTED);
      return new ClientSession(socket, connection);
    } catch (Exception ex) {
      connection.close();
      throw ex;
    }
  }

  private static ChatMessage receiveUntil(ClientSession session, MessageType expectedType)
      throws Exception {
    ChatMessage response;
    do {
      response = session.connection().receive(Duration.ofSeconds(2));
    } while (response.type() != expectedType);
    return response;
  }

  private static ChatMessage receiveUntilRoom(ClientSession session, String expectedRoom)
      throws Exception {
    return receiveUntilRoomEvent(session, MessageType.ROOM_JOINED, expectedRoom);
  }

  private static ChatMessage receiveUntilRoomEvent(
      ClientSession session, MessageType expectedType, String expectedRoom) throws Exception {
    ChatMessage response;
    do {
      response = session.connection().receive(Duration.ofSeconds(2));
    } while (response.type() != expectedType || !expectedRoom.equals(response.room()));
    return response;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ChatConnection> sessions(ChatServer server) throws Exception {
    Field field = ChatServer.class.getDeclaredField("sessions");
    field.setAccessible(true);
    return (Map<String, ChatConnection>) field.get(server);
  }

  private record ClientSession(Socket socket, ChatConnection connection) implements Closeable {
    @Override
    public void close() throws IOException {
      connection.close();
    }
  }
}
