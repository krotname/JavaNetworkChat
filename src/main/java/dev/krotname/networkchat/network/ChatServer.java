package dev.krotname.networkchat.network;

import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
  private static final int MAX_HANDSHAKE_ATTEMPTS = 5;
  static final String ENV_TLS_KEYSTORE_PASSWORD = "NETWORK_CHAT_TLS_KEYSTORE_PASSWORD";
  static final String ENV_TLS_KEY_PASSWORD = "NETWORK_CHAT_TLS_KEY_PASSWORD";

  private final ChatServerConfig config;
  private final ChatHistoryStore historyStore;
  private final AccountStore accountStore;
  private final Map<String, ChatConnection> sessions = new ConcurrentHashMap<>();
  private final Map<String, UserRole> roles = new ConcurrentHashMap<>();
  private final Map<String, Set<String>> roomMembers = new ConcurrentHashMap<>();
  private final Object sessionsMonitor = new Object();
  private final Object roomsMonitor = new Object();
  private final AtomicInteger activeClients = new AtomicInteger();
  private final ExecutorService clientExecutor;
  private final ExecutorService acceptorExecutor =
      Executors.newSingleThreadExecutor(daemonThreadFactory("chat-acceptor"));
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
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
    roomMembers.put(ChatMessage.GENERAL_ROOM, ConcurrentHashMap.newKeySet());
    for (String roomName : historyStore.knownRooms()) {
      if (roomMembers.size() >= config.maxRooms()) {
        break;
      }
      roomMembers.putIfAbsent(roomName, ConcurrentHashMap.newKeySet());
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

  static ChatServerConfig parseConfig(String[] args) {
    return parseConfig(args, System.getenv());
  }

  static ChatServerConfig parseConfig(String[] args, Map<String, String> environment) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(environment, "environment");
    ChatServerConfig parsedConfig = ChatServerConfig.defaultConfig();
    Path tlsKeyStoreFile = null;
    if (args.length == 1 && !args[0].startsWith("--")) {
      return parsedConfig.withPort(Integer.parseInt(args[0]));
    }
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg.toLowerCase(java.util.Locale.ROOT)) {
        case "--port" ->
            parsedConfig = parsedConfig.withPort(Integer.parseInt(nextArg(args, ++i, arg)));
        case "--bind" -> parsedConfig = parsedConfig.withBindAddress(nextArg(args, ++i, arg));
        case "--max-rooms" ->
            parsedConfig = parsedConfig.withMaxRooms(Integer.parseInt(nextArg(args, ++i, arg)));
        case "--history" ->
            parsedConfig = parsedConfig.withHistory(Path.of(nextArg(args, ++i, arg)));
        case "--accounts" ->
            parsedConfig = parsedConfig.withAccounts(Path.of(nextArg(args, ++i, arg)));
        case "--tls-keystore" -> tlsKeyStoreFile = Path.of(nextArg(args, ++i, arg));
        case "--tls-password", "--tls-key-password" ->
            throw new IllegalArgumentException(
                arg
                    + " exposes secrets in the process list; use TLS password environment variables");
        default -> throw new IllegalArgumentException("Unknown server argument: " + arg);
      }
    }
    if (tlsKeyStoreFile != null) {
      parsedConfig =
          parsedConfig.withTls(
              new TlsServerConfig(
                  true,
                  tlsKeyStoreFile,
                  environment.get(ENV_TLS_KEYSTORE_PASSWORD),
                  environment.get(ENV_TLS_KEY_PASSWORD)));
    }
    return parsedConfig;
  }

  private static String nextArg(String[] args, int index, String option) {
    if (index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
      throw new IllegalArgumentException("Missing value for " + option);
    }
    return args[index];
  }

  public synchronized void start() throws IOException {
    if (closed.get()) {
      throw new IllegalStateException("A closed chat server cannot be restarted");
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    ServerSocket openedSocket = null;
    try {
      openedSocket = ChatSockets.openServerSocket(config);
      serverSocket = openedSocket;
      acceptorExecutor.execute(this::acceptLoop);
      startSignal.countDown();
    } catch (IOException | RuntimeException ex) {
      running.set(false);
      closeServerSocket(openedSocket);
      if (serverSocket == openedSocket) {
        serverSocket = null;
      }
      throw ex;
    }
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
        ServerSocket listeningSocket = serverSocket;
        if (listeningSocket == null) {
          break;
        }
        Socket socket = listeningSocket.accept();
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
    try {
      socket.setSoTimeout(config.handshakeTimeoutMillis());
      try (socket;
          ChatConnection connection = new ChatConnection(socket)) {
        connection.send(ChatMessage.withData(MessageType.ERROR, reason, null));
      }
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
    ChatConnection sessionConnection = null;
    boolean active = false;
    try {
      socket.setSoTimeout(config.handshakeTimeoutMillis());
      try (socket;
          ChatConnection connection = new ChatConnection(socket)) {
        sessionConnection = connection;
        ClientLimits limits = new ClientLimits(config.rateLimit());
        HandshakeResult handshake = serverHandshake(connection);
        userName = handshake.userName();
        joinRoom(userName, ChatMessage.GENERAL_ROOM, connection, limits);
        socket.setSoTimeout(config.readTimeoutMillis());
        sendUsersListToNewClient(connection);
        sendRoomsListToNewClient(connection);
        activeClients.incrementAndGet();
        active = true;
        broadcast(ChatMessage.withData(MessageType.USER_ADDED, userName, null), connection);
        serverMainLoop(connection, userName, limits);
      }
    } catch (IOException | RuntimeException ex) {
      LOG.log(Level.FINE, "Client session ended", ex);
    } finally {
      if (active) {
        activeClients.decrementAndGet();
      }
      if (userName != null) {
        removeSession(userName, sessionConnection);
      }
    }
  }

  /**
   * Keep requesting the name until a valid and unique value is provided by the client.
   *
   * <p>Reservation occurs atomically to avoid the duplicate-name race while handshaking.
   */
  private HandshakeResult serverHandshake(ChatConnection connection) throws IOException {
    long deadline = System.nanoTime() + config.handshakeTimeout().toNanos();
    for (int attempt = 0; attempt < MAX_HANDSHAKE_ATTEMPTS; attempt++) {
      connection.send(ChatMessage.withData(MessageType.NAME_REQUEST, null, null));
      ChatMessage request = connection.receive(remainingDuration(deadline));
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
    connection.send(ChatMessage.withData(MessageType.ERROR, "Too many handshake attempts", null));
    throw new IOException("Too many handshake attempts");
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
    List<String> roomNames;
    synchronized (roomsMonitor) {
      roomNames = List.copyOf(roomMembers.keySet());
    }
    for (String roomName : roomNames) {
      connection.send(ChatMessage.roomAdded(roomName));
    }
  }

  /** Read messages from one client and broadcast normalized text messages to all clients. */
  private void serverMainLoop(ChatConnection connection, String userName, ClientLimits limits)
      throws IOException {
    while (running.get()) {
      ChatMessage message = connection.receive(config.readTimeout());
      if (!isProtocolSupported(message)) {
        connection.send(unsupportedProtocolVersionError());
        continue;
      }
      if (!limits.frames().tryAcquire()) {
        connection.send(
            ChatMessage.withData(MessageType.ERROR, "Too many requests, slow down", null));
        continue;
      }
      if (isHealthCommand(message)) {
        handleHealthCommand(userName, connection);
        continue;
      }
      switch (message.type()) {
        case TEXT, ROOM_TEXT -> handleRoomText(message, userName, connection);
        case PRIVATE_TEXT -> handlePrivateText(message, userName, connection);
        case ROOM_JOIN -> handleRoomJoin(message, userName, connection, limits);
        case ROOM_LEAVE -> handleRoomLeave(message, userName, connection);
        case NAME_ACCEPTED,
            USER_ADDED,
            USER_REMOVED,
            ROOM_ADDED,
            ROOM_REMOVED,
            ROOM_JOINED,
            ROOM_LEFT,
            ERROR ->
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
        Instant.now().toEpochMilli(),
        UUID.randomUUID().toString(),
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
    if (!AccountStore.isValidUserName(recipient)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Invalid recipient", null));
      return;
    }
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
            Instant.now().toEpochMilli(),
            UUID.randomUUID().toString(),
            ChatMessage.PROTOCOL_VERSION,
            null,
            recipient);
    connection.send(normalized);
    if (recipientConnection != connection) {
      try {
        recipientConnection.send(normalized);
      } catch (IOException ex) {
        LOG.log(Level.WARNING, "Failed sending private message to " + recipient, ex);
        removeSession(recipient, recipientConnection);
        connection.send(
            ChatMessage.withData(
                MessageType.ERROR, "Recipient disconnected before delivery", null));
        return;
      }
    }
    historyStore.save(normalized);
  }

  private boolean isHealthCommand(ChatMessage message) {
    return (message.type() == MessageType.TEXT || message.type() == MessageType.ROOM_TEXT)
        && message.data() != null
        && "/health".equalsIgnoreCase(message.data().trim());
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

  private void handleRoomJoin(
      ChatMessage message, String userName, ChatConnection connection, ClientLimits limits)
      throws IOException {
    String roomName = roomOrGeneral(message.room());
    if (!isRoomNameValid(roomName)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Invalid room name", null));
      return;
    }
    joinRoom(userName, roomName, connection, limits);
  }

  private void handleRoomLeave(ChatMessage message, String userName, ChatConnection connection)
      throws IOException {
    String roomName = roomOrGeneral(message.room());
    if (ChatMessage.GENERAL_ROOM.equals(roomName)) {
      connection.send(ChatMessage.withData(MessageType.ERROR, "Cannot leave general room", null));
      return;
    }
    boolean reclaimed = releaseRoom(userName, roomName);
    connection.send(ChatMessage.roomLeft(roomName));
    if (reclaimed) {
      announceRoomRemoved(roomName);
    }
  }

  /**
   * Admits one user to an existing or newly created room. Creation is bounded by the configured
   * room cap and by a per-connection token bucket; rejected requests are answered with an {@code
   * ERROR} frame instead of closing the session.
   */
  private void joinRoom(
      String userName, String roomName, ChatConnection connection, ClientLimits limits)
      throws IOException {
    if (sessions.get(userName) != connection) {
      throw new IOException("Session is no longer active");
    }
    RoomAdmission admission = admitToRoom(userName, roomName, limits);
    switch (admission) {
      case THROTTLED -> {
        connection.send(
            ChatMessage.withData(MessageType.ERROR, "Too many new rooms, slow down", null));
        return;
      }
      case LIMIT_REACHED -> {
        connection.send(ChatMessage.withData(MessageType.ERROR, "Room limit reached", null));
        return;
      }
      case CREATED -> broadcast(ChatMessage.roomAdded(roomName), null);
      case JOINED -> {
        // The room already existed, so no ROOM_ADDED announcement is needed.
      }
    }
    try {
      connection.send(ChatMessage.roomJoined(roomName));
      for (ChatMessage message :
          historyStore.recentRoomMessages(roomName, config.historyReplayLimit())) {
        connection.send(message);
      }
    } catch (IOException ex) {
      if (releaseRoom(userName, roomName)) {
        announceRoomRemoved(roomName);
      }
      throw ex;
    }
  }

  /**
   * Reserves room membership under a dedicated monitor so the cap is exact and a room cannot be
   * reclaimed between a joiner observing it and joining it. No I/O runs while the monitor is held,
   * and the monitor is never taken while the sessions monitor is held.
   */
  private RoomAdmission admitToRoom(String userName, String roomName, ClientLimits limits) {
    synchronized (roomsMonitor) {
      Set<String> members = roomMembers.get(roomName);
      if (members != null) {
        members.add(userName);
        return RoomAdmission.JOINED;
      }
      if (!limits.roomCreations().tryAcquire()) {
        return RoomAdmission.THROTTLED;
      }
      if (roomMembers.size() >= config.maxRooms()) {
        return RoomAdmission.LIMIT_REACHED;
      }
      Set<String> createdMembers = ConcurrentHashMap.newKeySet();
      createdMembers.add(userName);
      roomMembers.put(roomName, createdMembers);
      return RoomAdmission.CREATED;
    }
  }

  /**
   * Drops one membership and reclaims the room once it is empty, so room names cannot accumulate
   * for the lifetime of the process. The general room is never reclaimed.
   */
  private boolean releaseRoom(String userName, String roomName) {
    synchronized (roomsMonitor) {
      Set<String> members = roomMembers.get(roomName);
      if (members == null || !members.remove(userName) || !members.isEmpty()) {
        return false;
      }
      if (ChatMessage.GENERAL_ROOM.equals(roomName)) {
        return false;
      }
      roomMembers.remove(roomName);
      return true;
    }
  }

  /** Drops every membership of one user and returns the rooms reclaimed by that disconnect. */
  private List<String> releaseAllRooms(String userName) {
    List<String> reclaimedRooms = new ArrayList<>();
    synchronized (roomsMonitor) {
      Iterator<Map.Entry<String, Set<String>>> rooms = roomMembers.entrySet().iterator();
      while (rooms.hasNext()) {
        Map.Entry<String, Set<String>> room = rooms.next();
        if (!room.getValue().remove(userName)
            || !room.getValue().isEmpty()
            || ChatMessage.GENERAL_ROOM.equals(room.getKey())) {
          continue;
        }
        rooms.remove();
        reclaimedRooms.add(room.getKey());
      }
    }
    return reclaimedRooms;
  }

  private void announceRoomRemoved(String roomName) {
    try {
      broadcast(ChatMessage.roomRemoved(roomName), null);
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Unable to broadcast ROOM_REMOVED", ex);
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
        removeSession(member, recipient);
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
        removeSession(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Drops one session and reclaims the rooms it emptied. Announcements run outside the sessions
   * monitor so the rooms monitor is never nested inside it.
   */
  boolean removeSession(String userName, ChatConnection expectedConnection) {
    synchronized (sessionsMonitor) {
      if (expectedConnection == null || !sessions.remove(userName, expectedConnection)) {
        return false;
      }
      roles.remove(userName);
      closeConnection(expectedConnection);
    }
    List<String> reclaimedRooms = releaseAllRooms(userName);
    try {
      broadcast(ChatMessage.withData(MessageType.USER_REMOVED, userName, null), null);
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Unable to broadcast USER_REMOVED", ex);
    }
    for (String reclaimedRoom : reclaimedRooms) {
      announceRoomRemoved(reclaimedRoom);
    }
    return true;
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.set(false);
    ServerSocket socket = serverSocket;
    serverSocket = null;
    closeServerSocket(socket);
    closeAllSessions();
    clientExecutor.shutdownNow();
    acceptorExecutor.shutdownNow();
    startSignal.countDown();
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

  private static Duration remainingDuration(long deadlineNanos)
      throws java.net.SocketTimeoutException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new java.net.SocketTimeoutException("Handshake deadline exceeded");
    }
    return Duration.ofNanos(remaining);
  }

  private static void closeServerSocket(ServerSocket socket) {
    if (socket == null) {
      return;
    }
    try {
      socket.close();
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Error while closing server socket", ex);
    }
  }

  private void closeAllSessions() {
    Map<String, ChatConnection> closingSessions;
    synchronized (sessionsMonitor) {
      closingSessions = Map.copyOf(sessions);
      sessions.clear();
      roles.clear();
    }
    synchronized (roomsMonitor) {
      for (Set<String> members : roomMembers.values()) {
        members.removeAll(closingSessions.keySet());
      }
    }
    for (ChatConnection connection : closingSessions.values()) {
      closeConnection(connection);
    }
  }

  private static void closeConnection(ChatConnection connection) {
    try {
      connection.close();
    } catch (IOException ex) {
      LOG.log(Level.FINE, "Error closing connection", ex);
    }
  }

  private record HandshakeResult(String userName, UserRole role) {}

  /** Per-connection throttles created once for every accepted client. */
  private record ClientLimits(TokenBucket frames, TokenBucket roomCreations) {
    ClientLimits(RateLimitConfig rateLimit) {
      this(rateLimit.newFrameBucket(), rateLimit.newRoomCreationBucket());
    }
  }

  /** Result of one room admission attempt. */
  private enum RoomAdmission {
    JOINED,
    CREATED,
    LIMIT_REACHED,
    THROTTLED
  }
}
