package dev.krotname.networkchat.network;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Chat server that authenticates users, keeps sessions, and broadcasts messages. */
public final class ChatServer implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());
  private static final int MIN_ROOM_NAME_LENGTH = 1;
  private static final int MAX_ROOM_NAME_LENGTH = 64;

  private final ChatServerConfig config;
  private final ChatHistoryStore historyStore;
  private final AccountStore accountStore;
  private final Map<String, ChatConnection> sessions = new ConcurrentHashMap<>();
  private final Map<String, UserRole> roles = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> roomMembers = new ConcurrentHashMap<>();
  private final Object sessionsMonitor = new Object();
  private final AtomicInteger activeClients = new AtomicInteger();
  private final ExecutorService clientExecutor;
  private final ExecutorService acceptorExecutor =
      Executors.newSingleThreadExecutor(daemonThreadFactory("chat-acceptor"));
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final CountDownLatch startSignal = new CountDownLatch(1);
  private volatile ServerSocket serverSocket;

  public ChatServer(int port) {
    this(ChatServerConfig.ofPort(port));
  }

  public ChatServer(ChatServerConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.historyStore =
        config.historyFile() == null
            ? ChatHistoryStore.disabled()
            : ChatHistoryStore.open(config.historyFile(), config.historyLimit());
    this.accountStore =
        config.accountFile() == null ? AccountStore.disabled() : loadAccountStore(config);
    for (String roomName : historyStore.knownRooms()) {
      roomMembers.put(roomName, ConcurrentHashMap.newKeySet());
    }
    this.clientExecutor =
        new ThreadPoolExecutor(
            0,
            config.maxClients(),
            30L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            daemonThreadFactory("chat-client-handler"),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public static void main(String[] args) throws Exception {
    ChatServerConfig config = parseConfig(args);
    try (ChatServer server = new ChatServer(config)) {
      server.start();
      server.awaitStarted();
      Thread.currentThread().join();
    }
  }

  private static ChatServerConfig parseConfig(String[] args) {
    ChatServerConfig parsedConfig = ChatServerConfig.defaultConfig();
    Path tlsKeyStoreFile = null;
    String tlsKeyStorePassword = null;
    String tlsKeyPassword = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if ("--port".equalsIgnoreCase(arg) && i + 1 < args.length) {
        parsedConfig = parsedConfig.withPort(Integer.parseInt(args[++i]));
      } else if ("--history".equalsIgnoreCase(arg) && i + 1 < args.length) {
        parsedConfig = parsedConfig.withHistory(Path.of(args[++i]));
      } else if ("--accounts".equalsIgnoreCase(arg) && i + 1 < args.length) {
        parsedConfig = parsedConfig.withAccounts(Path.of(args[++i]));
      } else if ("--tls-keystore".equalsIgnoreCase(arg) && i + 1 < args.length) {
        tlsKeyStoreFile = Path.of(args[++i]);
      } else if ("--tls-password".equalsIgnoreCase(arg) && i + 1 < args.length) {
        tlsKeyStorePassword = args[++i];
      } else if ("--tls-key-password".equalsIgnoreCase(arg) && i + 1 < args.length) {
        tlsKeyPassword = args[++i];
      } else if (args.length == 1) {
        parsedConfig = parsedConfig.withPort(Integer.parseInt(arg));
      }
    }
    if (tlsKeyStoreFile != null) {
      parsedConfig =
          parsedConfig.withTls(
              new TlsServerConfig(true, tlsKeyStoreFile, tlsKeyStorePassword, tlsKeyPassword));
    }
    return parsedConfig;
  }

  public void start() throws IOException {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    serverSocket = ChatSockets.openServerSocket(config);
    acceptorExecutor.execute(this::acceptLoop);
    startSignal.countDown();
    LOG.info(
        StructuredLog.event(
            "server_started",
            Map.of(
                "port", getPort(),
                "tls", config.tls().enabled(),
                "accounts", accountStore.enabled(),
                "history", config.historyFile() != null)));
  }

  public void awaitStarted() {
    try {
      startSignal.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private void acceptLoop() {
    while (running.get()) {
      try {
        Socket socket = serverSocket.accept();
        submitClient(socket);
      } catch (IOException ex) {
        if (running.get()) {
          LOG.log(Level.WARNING, "Accept failed", ex);
        }
      }
    }
  }

  private void submitClient(Socket socket) {
    try {
      clientExecutor.execute(() -> handleClient(socket));
    } catch (RejectedExecutionException ex) {
      rejectClient(socket, "Server is busy, try again later");
    }
  }

  private void rejectClient(Socket socket, String reason) {
    try (socket;
        ChatConnection connection = new ChatConnection(socket)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, reason, null));
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Unable to send rejection to client", ex);
    }
  }

  /**
   * Processes a single client socket from acceptance to logout and ensures active-client metrics
   * are always accurate through finally block cleanup.
   */
  private void handleClient(Socket socket) {
    String userName = null;
    boolean active = false;
    try {
      socket.setSoTimeout(config.handshakeTimeoutMillis());
      try (socket;
          ChatConnection connection = new ChatConnection(socket)) {
        HandshakeResult handshake = serverHandshake(connection);
        userName = handshake.userName();
        joinRoom(userName, ChatMessage.GENERAL_ROOM, connection);
        socket.setSoTimeout(config.readTimeoutMillis());
        sendUsersListToNewClient(connection);
        sendRoomsListToNewClient(connection);
        activeClients.incrementAndGet();
        active = true;
        broadcast(ChatMessage.withData(MessageType.USER_ADDED, userName, null), connection);
        serverMainLoop(connection, userName);
      }
    } catch (IOException | RuntimeException ex) {
      LOG.log(Level.FINE, "Client session ended", ex);
    } finally {
      if (active) {
        activeClients.decrementAndGet();
      }
      if (userName != null) {
        removeSession(userName);
      }
    }
  }

  /**
   * Keep requesting the name until a valid and unique value is provided by the client.
   *
   * <p>Reservation occurs atomically to avoid the duplicate-name race while handshaking.
   */
  private HandshakeResult serverHandshake(ChatConnection connection) throws IOException {
    while (true) {
      connection.send(ChatMessage.withData(MessageType.NAME_REQUEST, null, null));
      ChatMessage request = connection.receive();
      if (!isProtocolSupported(request)) {
        connection.send(unsupportedProtocolVersionError());
        continue;
      }
      if (request.type() != MessageType.USER_NAME
          || request.data() == null
          || request.data().isBlank()) {
        connection.send(ChatMessage.withData(MessageType.ERROR, "Expected USER_NAME frame", null));
        continue;
      }
      LoginCredentials credentials = LoginCredentials.parse(request.data());
      String candidate = credentials.userName();
      if (!isNameValid(candidate)) {
        connection.send(ChatMessage.withData(MessageType.ERROR, "Invalid user name", null));
        continue;
      }
      UserRole role = accountStore.authenticate(candidate, credentials.token()).orElse(null);
      if (role == null) {
        connection.send(ChatMessage.withData(MessageType.ERROR, "Authentication failed", null));
        LOG.warning(StructuredLog.event("authentication_failed", Map.of("user", candidate)));
        continue;
      }
      synchronized (sessionsMonitor) {
        if (sessions.containsKey(candidate)) {
          connection.send(
              ChatMessage.withData(MessageType.ERROR, "User name already in use", null));
          continue;
        }
        sessions.put(candidate, connection);
        roles.put(candidate, role);
        try {
          connection.send(ChatMessage.withData(MessageType.NAME_ACCEPTED, null, null));
        } catch (IOException ex) {
          sessions.remove(candidate, connection);
          roles.remove(candidate);
          throw ex;
        }
        return new HandshakeResult(candidate, role);
      }
    }
  }

  private boolean isNameValid(String userName) {
    return AccountStore.isValidUserName(userName);
  }

  /** Sends all registered users to the new client so UI state is correct right after connect. */
  private void sendUsersListToNewClient(ChatConnection connection) throws IOException {
    for (String userName : sessions.keySet()) {
      connection.send(ChatMessage.withData(MessageType.USER_ADDED, userName, null));
    }
  }

  private void sendRoomsListToNewClient(ChatConnection connection) throws IOException {
    for (String roomName : roomMembers.keySet()) {
      connection.send(ChatMessage.roomAdded(roomName));
    }
  }

  /** Read messages from one client and broadcast normalized text messages to all clients. */
  private void serverMainLoop(ChatConnection connection, String userName) throws IOException {
    while (running.get()) {
      ChatMessage message = connection.receive();
      if (!isProtocolSupported(message)) {
        connection.send(unsupportedProtocolVersionError());
        continue;
      }
      if (isHealthCommand(message)) {
        handleHealthCommand(userName, connection);
        continue;
      }
      switch (message.type()) {
        case TEXT, ROOM_TEXT -> handleRoomText(message, userName, connection);
        case PRIVATE_TEXT -> handlePrivateText(message, userName, connection);
        case ROOM_JOIN -> handleRoomJoin(message, userName, connection);
        case ROOM_LEAVE -> handleRoomLeave(message, userName, connection);
        case NAME_ACCEPTED, USER_ADDED, USER_REMOVED, ROOM_ADDED, ROOM_JOINED, ROOM_LEFT, ERROR ->
            connection.send(
                ChatMessage.withData(MessageType.ERROR, "Unsupported client frame", null));
        default -> throw new IOException("Unsupported message type: " + message.type());
      }
    }
  }

  private ChatMessage normalizeTextMessage(ChatMessage message, String userName) {
    return new ChatMessage(
        MessageType.ROOM_TEXT,
        message.data(),
        userName,
        message.timestamp(),
        message.messageId(),
        ChatMessage.PROTOCOL_VERSION,
        roomOrGeneral(message.room()),
        null);
  }

  private void handleRoomText(ChatMessage message, String userName, ChatConnection connection)
      throws IOException {
    String roomName = roomOrGeneral(message.room());
    if (!isRoomMember(userName, roomName)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Join room before sending", null));
      return;
    }
    ChatMessage normalized = normalizeTextMessage(message.inRoom(roomName), userName);
    historyStore.save(normalized);
    broadcastToRoom(normalized, roomName);
  }

  private void handlePrivateText(ChatMessage message, String userName, ChatConnection connection)
      throws IOException {
    String recipient = message.recipient();
    ChatConnection recipientConnection = sessions.get(recipient);
    if (recipientConnection == null) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Recipient is not connected", null));
      return;
    }
    ChatMessage normalized =
        new ChatMessage(
            MessageType.PRIVATE_TEXT,
            message.data(),
            userName,
            message.timestamp(),
            message.messageId(),
            ChatMessage.PROTOCOL_VERSION,
            null,
            recipient);
    historyStore.save(normalized);
    connection.send(normalized);
    if (recipientConnection != connection) {
      try {
        recipientConnection.send(normalized);
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Failed sending private message to " + recipient, ex);
        removeSession(recipient);
      }
    }
  }

  private boolean isHealthCommand(ChatMessage message) {
    return message.data() != null && "/health".equalsIgnoreCase(message.data().trim());
  }

  private void handleHealthCommand(String userName, ChatConnection connection) throws IOException {
    if (roles.getOrDefault(userName, UserRole.USER) != UserRole.ADMIN) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Admin role is required", null));
      return;
    }
    connection.send(ChatMessage.privateText(healthSummary(), "server", userName));
  }

  private String healthSummary() {
    return String.format(
        "{\"status\":\"UP\",\"activeClients\":%d,\"rooms\":%d,"
            + "\"historyEnabled\":%s,\"accountsEnabled\":%s,\"tlsEnabled\":%s}",
        activeClients.get(),
        roomMembers.size(),
        config.historyFile() != null,
        accountStore.enabled(),
        config.tls().enabled());
  }

  private void handleRoomJoin(ChatMessage message, String userName, ChatConnection connection)
      throws IOException {
    String roomName = roomOrGeneral(message.room());
    if (!isRoomNameValid(roomName)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Invalid room name", null));
      return;
    }
    boolean created = roomMembers.putIfAbsent(roomName, ConcurrentHashMap.newKeySet()) == null;
    if (created) {
      broadcast(ChatMessage.roomAdded(roomName), null);
    }
    joinRoom(userName, roomName, connection);
  }

  private void handleRoomLeave(ChatMessage message, String userName, ChatConnection connection)
      throws IOException {
    String roomName = roomOrGeneral(message.room());
    if (ChatMessage.GENERAL_ROOM.equals(roomName)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Cannot leave general room", null));
      return;
    }
    Set<String> members = roomMembers.get(roomName);
    if (members != null) {
      members.remove(userName);
    }
    connection.send(ChatMessage.roomLeft(roomName));
  }

  private void joinRoom(String userName, String roomName, ChatConnection connection)
      throws IOException {
    roomMembers.computeIfAbsent(roomName, ignored -> ConcurrentHashMap.newKeySet()).add(userName);
    connection.send(ChatMessage.roomJoined(roomName));
    for (ChatMessage message :
        historyStore.recentRoomMessages(roomName, config.historyReplayLimit())) {
      connection.send(message);
    }
  }

  private boolean isRoomMember(String userName, String roomName) {
    Set<String> members = roomMembers.get(roomName);
    return members != null && members.contains(userName);
  }

  private void broadcastToRoom(ChatMessage message, String roomName) throws IOException {
    Set<String> members = roomMembers.get(roomName);
    if (members == null) {
      return;
    }
    for (String member : members) {
      ChatConnection recipient = sessions.get(member);
      if (recipient == null) {
        continue;
      }
      try {
        recipient.send(message);
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Failed sending to " + member, ex);
        removeSession(member);
      }
    }
  }

  private boolean isProtocolSupported(ChatMessage message) {
    return message.protocolVersion() == ChatMessage.PROTOCOL_VERSION;
  }

  private ChatMessage unsupportedProtocolVersionError() {
    return ChatMessage.withData(MessageType.ERROR, "Unsupported protocol version", null);
  }

  private String roomOrGeneral(String roomName) {
    return roomName == null || roomName.isBlank() ? ChatMessage.GENERAL_ROOM : roomName.trim();
  }

  private boolean isRoomNameValid(String roomName) {
    return roomName.length() >= MIN_ROOM_NAME_LENGTH
        && roomName.length() <= MAX_ROOM_NAME_LENGTH
        && roomName.matches("[\\p{L}\\p{N}_-]+");
  }

  /**
   * Broadcasts to every connected client, excluding the message source. Failed deliveries are
   * treated as dropped clients and cleaned up immediately.
   */
  private void broadcast(ChatMessage message, ChatConnection exceptConnection) throws IOException {
    for (Map.Entry<String, ChatConnection> entry : sessions.entrySet()) {
      if (entry.getValue() == exceptConnection) {
        continue;
      }
      try {
        ChatConnection recipient = entry.getValue();
        if (recipient != null) {
          recipient.send(message);
        }
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Failed sending to " + entry.getKey(), ex);
        removeSession(entry.getKey());
      }
    }
  }

  private void removeSession(String userName) {
    ChatConnection removed = sessions.remove(userName);
    if (removed == null) {
      return;
    }
    roles.remove(userName);
    try {
      removed.close();
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Error closing connection", ex);
    }
    try {
      broadcast(ChatMessage.withData(MessageType.USER_REMOVED, userName, null), null);
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Unable to broadcast USER_REMOVED", ex);
    }
    for (Set<String> members : roomMembers.values()) {
      members.remove(userName);
    }
  }

  @Override
  public void close() {
    running.set(false);
    try {
      if (serverSocket != null) {
        serverSocket.close();
      }
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Error while closing server socket", ex);
    }
    for (String userName : new HashSet<>(sessions.keySet())) {
      removeSession(userName);
    }
    clientExecutor.shutdownNow();
    acceptorExecutor.shutdownNow();
  }

  public Set<String> getConnectedUsers() {
    return Collections.unmodifiableSet(sessions.keySet());
  }

  public Set<String> getRooms() {
    return Collections.unmodifiableSet(roomMembers.keySet());
  }

  public int getPort() {
    ServerSocket socket = serverSocket;
    return socket == null ? config.port() : socket.getLocalPort();
  }

  public ChatServerConfig getConfig() {
    return config;
  }

  public boolean isRunning() {
    return running.get();
  }

  public int getActiveClients() {
    return activeClients.get();
  }

  public UserRole getUserRole(String userName) {
    return roles.get(userName);
  }

  private static AccountStore loadAccountStore(ChatServerConfig config) {
    try {
      return AccountStore.load(config.accountFile());
    } catch (IOException ex) {
      throw new IllegalArgumentException("Unable to load account file", ex);
    }
  }

  private static ThreadFactory daemonThreadFactory(String threadName) {
    return task -> {
      Thread thread = new Thread(task, threadName);
      thread.setDaemon(true);
      return thread;
    };
  }

  private record HandshakeResult(String userName, UserRole role) {}
}
