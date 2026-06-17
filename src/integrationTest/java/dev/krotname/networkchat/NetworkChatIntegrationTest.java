package dev.krotname.networkchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.krotname.networkchat.network.AccountStore;
import dev.krotname.networkchat.network.ChatConnection;
import dev.krotname.networkchat.network.ChatServer;
import dev.krotname.networkchat.network.ChatServerConfig;
import dev.krotname.networkchat.network.LoginCredentials;
import dev.krotname.networkchat.network.UserRole;
import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkChatIntegrationTest {
  @TempDir private Path tempDir;

  @Test
  void serverBroadcastsMessagesBetweenClients() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port);
          TestClient bob = new TestClient("bob", port)) {
        alice.connect();
        bob.connect();

        assertTrue(server.getConnectedUsers().contains("alice"));
        assertTrue(server.getConnectedUsers().contains("bob"));

        ChatMessage sentMessage = alice.sendText("Привет всем");
        awaitTextMessage(
            alice, MessageType.ROOM_TEXT, "alice", "Привет всем", sentMessage.messageId());
        awaitTextMessage(
            bob, MessageType.ROOM_TEXT, "alice", "Привет всем", sentMessage.messageId());
      }
    }
  }

  @Test
  void newClientReceivesOwnUserListEntry() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port)) {
        alice.connect();

        awaitProtocolEvent(alice, MessageType.USER_ADDED, "alice");
      }
    }
  }

  @Test
  void roomMessagesReachOnlyRoomMembers() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port);
          TestClient bob = new TestClient("bob", port);
          TestClient carol = new TestClient("carol", port)) {
        alice.connect();
        bob.connect();
        carol.connect();

        alice.joinRoom("dev");
        bob.joinRoom("dev");
        awaitProtocolEvent(alice, MessageType.ROOM_JOINED, "dev");
        awaitProtocolEvent(bob, MessageType.ROOM_JOINED, "dev");

        ChatMessage message = alice.sendRoomText("dev", "room secret");

        awaitTextMessage(alice, MessageType.ROOM_TEXT, "alice", "room secret", message.messageId());
        awaitTextMessage(bob, MessageType.ROOM_TEXT, "alice", "room secret", message.messageId());
        assertNoMessage(carol, MessageType.ROOM_TEXT, "room secret");
      }
    }
  }

  @Test
  void privateMessagesReachOnlySenderAndRecipient() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port);
          TestClient bob = new TestClient("bob", port);
          TestClient carol = new TestClient("carol", port)) {
        alice.connect();
        bob.connect();
        carol.connect();

        ChatMessage message = alice.sendPrivateText("bob", "private secret");

        awaitTextMessage(
            alice, MessageType.PRIVATE_TEXT, "alice", "private secret", message.messageId());
        awaitTextMessage(
            bob, MessageType.PRIVATE_TEXT, "alice", "private secret", message.messageId());
        assertNoMessage(carol, MessageType.PRIVATE_TEXT, "private secret");
      }
    }
  }

  @Test
  void joiningAndLeavingRoomsProducesRoomEvents() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port)) {
        alice.connect();
        alice.joinRoom("dev");
        awaitProtocolEvent(alice, MessageType.ROOM_ADDED, "dev");
        awaitProtocolEvent(alice, MessageType.ROOM_JOINED, "dev");

        alice.leaveRoom("dev");
        awaitProtocolEvent(alice, MessageType.ROOM_LEFT, "dev");
      }
    }
  }

  @Test
  void serverReplaysRoomHistoryAfterRestart() throws Exception {
    Path historyFile = tempDir.resolve("history.jsonl");
    int firstPort = 0;
    try (ChatServer server =
        new ChatServer(ChatServerConfig.ofPort(firstPort).withHistory(historyFile, 100, 10))) {
      server.start();
      server.awaitStarted();
      firstPort = server.getPort();

      try (TestClient alice = new TestClient("alice", firstPort)) {
        alice.connect();
        alice.sendText("persisted hello");
        awaitTextMessage(alice, MessageType.ROOM_TEXT, "alice", "persisted hello", null);
      }
    }

    int secondPort = 0;
    try (ChatServer server =
        new ChatServer(ChatServerConfig.ofPort(secondPort).withHistory(historyFile, 100, 10))) {
      server.start();
      server.awaitStarted();
      secondPort = server.getPort();

      try (TestClient bob = new TestClient("bob", secondPort)) {
        bob.connect();

        awaitTextMessage(bob, MessageType.ROOM_TEXT, "alice", "persisted hello", null);
      }
    }
  }

  @Test
  void serverRequiresConfiguredAccountToken() throws Exception {
    Path accountFile = writeAccounts("alice", UserRole.USER, "secret");
    int port = 0;
    try (ChatServer server =
        new ChatServer(ChatServerConfig.ofPort(port).withAccounts(accountFile))) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (ChatConnection connection = new ChatConnection(connectWithRetry(port))) {
        consumeNameRequest(connection);
        connection.send(ChatMessage.withData(MessageType.USER_NAME, "alice", null));

        ChatMessage response = connection.receive();
        assertEquals(MessageType.ERROR, response.type());
        assertTrue(response.data().contains("Authentication failed"));
      }

      try (TestClient alice = new TestClient("alice", port, "secret")) {
        alice.connect();

        Awaitility.await("wait for authenticated user")
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertTrue(server.getConnectedUsers().contains("alice")));
      }
    }
  }

  @Test
  void adminCanRequestServerHealth() throws Exception {
    Path accountFile = writeAccounts("admin", UserRole.ADMIN, "secret");
    int port = 0;
    try (ChatServer server =
        new ChatServer(ChatServerConfig.ofPort(port).withAccounts(accountFile))) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient admin = new TestClient("admin", port, "secret")) {
        admin.connect();
        assertEquals(UserRole.ADMIN, server.getUserRole("admin"));

        admin.sendText("/health");

        awaitTextMessageContaining(admin, MessageType.PRIVATE_TEXT, "server", "\"status\":\"UP\"");
      }
    }
  }

  @Test
  void unversionedClientReceivesExplicitProtocolError() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      Socket socket = connectWithRetry(port);
      socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(2));
      try (ChatConnection connection = new ChatConnection(socket)) {
        assertEquals(MessageType.NAME_REQUEST, connection.receive().type());
        socket
            .getOutputStream()
            .write(
                """
                {"type":"USER_NAME","data":"legacy","sender":null,"timestamp":1,"messageId":"old"}
                """
                    .strip()
                    .concat("\n")
                    .getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();

        ChatMessage response = connection.receive();
        assertEquals(MessageType.ERROR, response.type());
        assertTrue(response.data().contains("Unsupported protocol version"));
      }
    }
  }

  @Test
  void serverRejectsDuplicateUserName() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port);
          ChatConnection duplicate = new ChatConnection(connectWithRetry(port))) {
        alice.connect();
        consumeNameRequest(duplicate);
        duplicate.send(ChatMessage.withData(MessageType.USER_NAME, "alice", null));
        ChatMessage response;
        do {
          response = duplicate.receive();
        } while (response.type() != MessageType.ERROR
            && response.type() != MessageType.NAME_ACCEPTED);
        assertEquals(MessageType.ERROR, response.type());
      }
    }
  }

  @Test
  void serverRejectsInvalidUserName() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port);
          ChatConnection invalid = new ChatConnection(connectWithRetry(port))) {
        alice.connect();
        consumeNameRequest(invalid);
        invalid.send(ChatMessage.withData(MessageType.USER_NAME, "!!", null));
        ChatMessage response;
        do {
          response = invalid.receive();
        } while (response.type() != MessageType.ERROR
            && response.type() != MessageType.NAME_ACCEPTED);
        assertEquals(MessageType.ERROR, response.type());
      }
    }
  }

  @Test
  void serverRemovesUserWhenConnectionCloses() throws Exception {
    int port = 0;
    try (ChatServer server = new ChatServer(port)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port);
          TestClient bob = new TestClient("bob", port)) {
        alice.connect();
        bob.connect();

        awaitProtocolEvent(alice, MessageType.USER_ADDED, "bob");

        bob.disconnect();
        awaitProtocolEvent(alice, MessageType.USER_REMOVED, "bob");
      }
    }
  }

  @Test
  void serverRejectsConnectionsOverConfiguredLimit() throws Exception {
    int port = 0;
    ChatServerConfig config =
        new ChatServerConfig(port, 1, Duration.ofSeconds(2), Duration.ofSeconds(5));
    try (ChatServer server = new ChatServer(config)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      try (TestClient alice = new TestClient("alice", port)) {
        alice.connect();
        Socket rejectedSocket = connectWithRetry(port);
        rejectedSocket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(2));
        try (ChatConnection rejected = new ChatConnection(rejectedSocket)) {
          ChatMessage response = rejected.receive();
          assertEquals(MessageType.ERROR, response.type());
          assertTrue(response.data().contains("busy"));
        }
      }
    }
  }

  @Test
  void serverClosesClientThatDoesNotCompleteHandshake() throws Exception {
    int port = 0;
    ChatServerConfig config =
        new ChatServerConfig(port, 2, Duration.ofMillis(200), Duration.ofSeconds(5));
    try (ChatServer server = new ChatServer(config)) {
      server.start();
      server.awaitStarted();
      port = server.getPort();

      Socket socket = connectWithRetry(port);
      socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(2));
      try (ChatConnection silent = new ChatConnection(socket)) {
        assertEquals(MessageType.NAME_REQUEST, silent.receive().type());
        assertThrows(IOException.class, silent::receive);
      }
    }
  }

  @Test
  void closingConnectionUnblocksPendingReceive() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peerSocket = serverSocket.accept()) {
      assertTrue(peerSocket.isConnected());
      ChatConnection connection = new ChatConnection(clientSocket);
      try {
        Future<?> pendingReceive =
            executor.submit(() -> assertThrows(IOException.class, connection::receive));

        Thread.sleep(100);
        connection.close();

        pendingReceive.get(2, TimeUnit.SECONDS);
      } finally {
        connection.close();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static void consumeNameRequest(ChatConnection connection) throws IOException {
    ChatMessage request;
    do {
      request = connection.receive();
    } while (request.type() != MessageType.NAME_REQUEST
        && request.type() != MessageType.NAME_ACCEPTED);
  }

  private static void awaitTextMessage(
      TestClient client,
      MessageType expectedType,
      String expectedSender,
      String expectedData,
      String expectedMessageId) {
    Awaitility.await("wait for broadcast")
        .pollInterval(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertTrue(
                    containsTextMessage(
                        client, expectedType, expectedSender, expectedData, expectedMessageId)));
  }

  private static void awaitTextMessageContaining(
      TestClient client, MessageType expectedType, String expectedSender, String expectedData) {
    Awaitility.await("wait for matching message")
        .pollInterval(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertTrue(
                    containsTextMessageContaining(
                        client, expectedType, expectedSender, expectedData)));
  }

  private static boolean containsTextMessage(
      TestClient client,
      MessageType expectedType,
      String expectedSender,
      String expectedData,
      String expectedMessageId) {
    ChatMessage message;
    while ((message = client.drainQueue()) != null) {
      if (expectedType.equals(message.type())
          && expectedSender.equals(message.sender())
          && expectedData.equals(message.data())
          && (expectedMessageId == null || expectedMessageId.equals(message.messageId()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsTextMessageContaining(
      TestClient client, MessageType expectedType, String expectedSender, String expectedData) {
    ChatMessage message;
    while ((message = client.drainQueue()) != null) {
      if (expectedType.equals(message.type())
          && expectedSender.equals(message.sender())
          && message.data() != null
          && message.data().contains(expectedData)) {
        return true;
      }
    }
    return false;
  }

  private static void assertNoMessage(
      TestClient client, MessageType expectedType, String expectedData)
      throws InterruptedException {
    Thread.sleep(300);
    ChatMessage message;
    while ((message = client.drainQueue()) != null) {
      assertTrue(
          message.type() != expectedType
              || message.data() == null
              || !message.data().contains(expectedData));
    }
  }

  private static void awaitProtocolEvent(
      TestClient client, MessageType expectedType, String expectedData) {
    Awaitility.await("wait for protocol event")
        .pollInterval(Duration.ofMillis(200))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertTrue(containsInQueue(client, expectedType, expectedData)));
  }

  private static boolean containsInQueue(
      TestClient client, MessageType expectedType, String expectedData) {
    ChatMessage message;
    while ((message = client.drainQueue()) != null) {
      if (message.type() == expectedType
          && message.data() != null
          && message.data().contains(expectedData)) {
        return true;
      }
    }
    return false;
  }

  private static Socket connectWithRetry(int port) throws Exception {
    IOException lastError = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
        return socket;
      } catch (IOException ex) {
        lastError = ex;
        socket.close();
        Thread.sleep(50);
      }
    }
    throw lastError == null ? new IOException("Unable to connect") : lastError;
  }

  private Path writeAccounts(String userName, UserRole role, String token) throws IOException {
    Path accountFile = tempDir.resolve(userName + "-accounts.csv");
    String salt = userName + "-salt";
    Files.writeString(
        accountFile,
        userName + "," + role + "," + salt + "," + AccountStore.hashToken(salt, token) + "\n",
        StandardCharsets.UTF_8);
    return accountFile;
  }

  private static final class TestClient implements AutoCloseable {
    private final String userName;
    private final int port;
    private final String token;
    private final LinkedBlockingQueue<ChatMessage> messages = new LinkedBlockingQueue<>();
    private ChatConnection connection;
    private Thread reader;

    private TestClient(String userName, int port) {
      this(userName, port, "");
    }

    private TestClient(String userName, int port, String token) {
      this.userName = userName;
      this.port = port;
      this.token = token;
    }

    void connect() throws Exception {
      Socket socket = NetworkChatIntegrationTest.connectWithRetry(port);
      connection = new ChatConnection(socket);
      ChatMessage request;
      do {
        request = connection.receive();
      } while (request.type() != MessageType.NAME_REQUEST);
      connection.send(
          ChatMessage.withData(
              MessageType.USER_NAME, LoginCredentials.encode(userName, token), null));
      ChatMessage accepted;
      do {
        accepted = connection.receive();
        if (accepted.type() == MessageType.ERROR) {
          throw new IllegalStateException("Expected NAME_ACCEPTED, got ERROR: " + accepted.data());
        }
        if (accepted.type() != MessageType.NAME_ACCEPTED) {
          messages.offer(accepted);
        }
      } while (accepted.type() != MessageType.NAME_ACCEPTED);

      reader =
          new Thread(
              () -> {
                while (!Thread.currentThread().isInterrupted()) {
                  try {
                    messages.offer(connection.receive());
                  } catch (IOException ex) {
                    break;
                  }
                }
              });
      reader.setDaemon(true);
      reader.start();
    }

    ChatMessage sendText(String text) throws Exception {
      ChatMessage message = ChatMessage.text(text, userName);
      connection.send(message);
      return message;
    }

    ChatMessage sendRoomText(String roomName, String text) throws Exception {
      ChatMessage message = ChatMessage.roomText(text, userName, roomName);
      connection.send(message);
      return message;
    }

    ChatMessage sendPrivateText(String recipient, String text) throws Exception {
      ChatMessage message = ChatMessage.privateText(text, userName, recipient);
      connection.send(message);
      return message;
    }

    void joinRoom(String roomName) throws Exception {
      connection.send(ChatMessage.roomJoin(roomName));
    }

    void leaveRoom(String roomName) throws Exception {
      connection.send(ChatMessage.roomLeave(roomName));
    }

    ChatMessage drainQueue() {
      return messages.poll();
    }

    void disconnect() {
      close();
    }

    @Override
    public void close() {
      if (reader != null) {
        reader.interrupt();
      }
      try {
        if (connection != null) {
          connection.close();
        }
      } catch (IOException ignored) {
      }
      if (reader != null) {
        try {
          reader.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
