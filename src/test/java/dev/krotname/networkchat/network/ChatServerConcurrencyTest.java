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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    try (ChatServer server = new ChatServer(0)) {
      server.start();
      try (ClientSession alice = connect(server, "alice")) {
        Map<String, Set<String>> rooms = rooms(server);
        for (int index = rooms.size(); index < ChatServer.MAX_ROOMS; index++) {
          rooms.put("existing_" + index, ConcurrentHashMap.newKeySet());
        }

        alice.connection().send(ChatMessage.roomJoin("overflow"));
        ChatMessage response = receiveUntil(alice, MessageType.ERROR);

        assertTrue(response.data().contains("limit"));
        assertFalse(server.getRooms().contains("overflow"));
      }
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

  @SuppressWarnings("unchecked")
  private static Map<String, ChatConnection> sessions(ChatServer server) throws Exception {
    Field field = ChatServer.class.getDeclaredField("sessions");
    field.setAccessible(true);
    return (Map<String, ChatConnection>) field.get(server);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Set<String>> rooms(ChatServer server) throws Exception {
    Field field = ChatServer.class.getDeclaredField("roomMembers");
    field.setAccessible(true);
    return (Map<String, Set<String>>) field.get(server);
  }

  private record ClientSession(Socket socket, ChatConnection connection) implements Closeable {
    @Override
    public void close() throws IOException {
      connection.close();
    }
  }
}
