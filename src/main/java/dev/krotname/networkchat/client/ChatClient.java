package dev.krotname.networkchat.client;

import dev.krotname.networkchat.network.AccountStore;
import dev.krotname.networkchat.network.ChatConnection;
import dev.krotname.networkchat.network.ChatSockets;
import dev.krotname.networkchat.network.LoginCredentials;
import dev.krotname.networkchat.network.TlsClientConfig;
import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import dev.krotname.networkchat.util.ConsoleInput;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Template client with reusable protocol loop and overridable UI/input behavior. */
public abstract class ChatClient {
  private static final Logger LOG = Logger.getLogger(ChatClient.class.getName());
  private static final String EXIT_COMMAND = "exit";
  private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);
  private static final int MAX_HANDSHAKE_FRAMES = 5;

  protected volatile boolean clientConnected;
  protected volatile ChatConnection connection;
  private volatile String resolvedServerAddress;
  private volatile int resolvedServerPort;
  private volatile String resolvedUserName;
  private volatile String resolvedAccountToken;
  private volatile TlsClientConfig resolvedTlsConfig = TlsClientConfig.disabled();
  private volatile String lastConnectionError;
  private volatile boolean disconnectRequested;
  private volatile CountDownLatch connectionEstablishedLatch = new CountDownLatch(1);
  private final Object lifecycleMonitor = new Object();
  private volatile SocketThread activeSocketThread;
  private boolean runInProgress;

  public abstract String getServerAddress() throws IOException;

  public abstract int getServerPort() throws IOException;

  public abstract String getUserName() throws IOException;

  protected String getAccountToken() throws IOException {
    return System.getenv().getOrDefault("NETWORK_CHAT_TOKEN", "");
  }

  protected TlsClientConfig getTlsConfig() {
    return TlsClientConfig.fromEnvironment();
  }

  protected abstract boolean shouldSendTextFromConsole();

  protected abstract SocketThread getSocketThread();

  /**
   * Coordinates one client session: resolve credentials, execute the socket thread, wait for the
   * handshake result, then optionally read local input.
   */
  public void run() {
    if (!beginRun()) {
      return;
    }
    try {
      runSession();
    } finally {
      synchronized (lifecycleMonitor) {
        runInProgress = false;
      }
    }
  }

  private void runSession() {
    clientConnected = false;
    disconnectRequested = false;
    lastConnectionError = null;
    resolvedAccountToken = "";
    connectionEstablishedLatch = new CountDownLatch(1);
    try {
      String requestedServerAddress = getServerAddress();
      if (requestedServerAddress == null || requestedServerAddress.isBlank()) {
        throw new IOException("Server address is required");
      }
      resolvedServerAddress = requestedServerAddress.trim();
      if (resolvedServerAddress.length() > 253
          || resolvedServerAddress.chars().anyMatch(Character::isISOControl)) {
        throw new IOException("Server address is invalid");
      }
      resolvedServerPort = getServerPort();
      if (resolvedServerPort < 1 || resolvedServerPort > 65_535) {
        throw new IOException("Server port must be in range 1..65535");
      }
      String requestedUserName = getUserName();
      if (requestedUserName == null || requestedUserName.isBlank()) {
        throw new IOException("User name is required");
      }
      resolvedUserName = requestedUserName.trim();
      if (!AccountStore.isValidUserName(resolvedUserName)) {
        throw new IOException("User name must contain 3..64 letters, digits, '_' or '-'");
      }
      String requestedAccountToken = getAccountToken();
      resolvedAccountToken =
          requestedAccountToken == null || requestedAccountToken.isBlank()
              ? ""
              : requestedAccountToken;
      TlsClientConfig requestedTlsConfig = getTlsConfig();
      if (requestedTlsConfig == null) {
        throw new IOException("TLS configuration is required");
      }
      resolvedTlsConfig = requestedTlsConfig;
    } catch (IOException | IllegalArgumentException e) {
      recordConnectionError(e.getMessage());
      LOG.warning("Unable to determine connection settings");
      return;
    }
    SocketThread socketThread = getSocketThread();
    if (socketThread == null) {
      recordConnectionError("Connection thread is unavailable");
      return;
    }
    synchronized (lifecycleMonitor) {
      activeSocketThread = socketThread;
    }
    try {
      socketThread.start();
    } catch (RuntimeException ex) {
      clearActiveSocketThread(socketThread);
      recordConnectionError(ex.getMessage());
      return;
    }
    try {
      if (!connectionEstablishedLatch.await(35, TimeUnit.SECONDS)) {
        recordConnectionError("Connection attempt timed out");
        disconnect();
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      disconnect();
      return;
    }
    if (!clientConnected) {
      return;
    }
    if (shouldSendTextFromConsole()) {
      try {
        while (clientConnected) {
          String text = readInputLine();
          if (text == null || EXIT_COMMAND.equalsIgnoreCase(text)) {
            break;
          }
          sendTextMessage(text);
        }
      } finally {
        disconnect();
      }
    }
  }

  protected String readInputLine() {
    try {
      String line = ConsoleInput.readLine();
      return line == null ? null : line.trim();
    } catch (Exception ex) {
      LOG.warning("Failed reading console line");
      return null;
    }
  }

  public boolean sendTextMessage(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    try {
      return sendMessage(ChatMessage.text(text, resolvedUserName));
    } catch (IllegalArgumentException ex) {
      recordConnectionError(ex.getMessage());
      return false;
    }
  }

  protected boolean sendMessage(ChatMessage message) {
    ChatConnection activeConnection = connection;
    if (message == null || activeConnection == null) {
      return false;
    }
    try {
      activeConnection.send(message);
      return true;
    } catch (IOException ex) {
      recordConnectionError("Failed to send chat message");
      LOG.warning(lastConnectionError);
      disconnect();
      return false;
    } catch (IllegalArgumentException ex) {
      recordConnectionError(ex.getMessage());
      LOG.warning(lastConnectionError);
      return false;
    }
  }

  public void disconnect() {
    disconnectRequested = true;
    resolvedAccountToken = "";
    setConnectionStatus(false);
    closeConnection();
  }

  protected void closeConnection() {
    ChatConnection activeConnection = connection;
    if (activeConnection == null) {
      return;
    }
    connection = null;
    try {
      activeConnection.close();
    } catch (IOException ex) {
      LOG.fine("Error closing client connection");
    }
  }

  private void setConnectionStatus(boolean connected) {
    this.clientConnected = connected;
    if (connectionEstablishedLatch.getCount() > 0) {
      connectionEstablishedLatch.countDown();
    }
  }

  protected boolean isClientConnected() {
    return clientConnected;
  }

  public String getLastConnectionError() {
    return lastConnectionError;
  }

  public String getResolvedUserName() {
    return resolvedUserName;
  }

  protected void onClientConnectionStatusChanged(boolean connected) {}

  private void recordConnectionError(String error) {
    lastConnectionError = safeError(error);
  }

  private static String safeError(String value) {
    if (value == null || value.isBlank()) {
      return "Connection error";
    }
    StringBuilder sanitized = new StringBuilder(Math.min(value.length(), 256));
    for (int index = 0; index < value.length() && sanitized.length() < 256; index++) {
      char character = value.charAt(index);
      sanitized.append(Character.isISOControl(character) ? ' ' : character);
    }
    return sanitized.toString();
  }

  private boolean beginRun() {
    synchronized (lifecycleMonitor) {
      if (activeSocketThread != null && !activeSocketThread.isAlive()) {
        activeSocketThread = null;
      }
      if (runInProgress || activeSocketThread != null) {
        recordConnectionError("A connection attempt is already active");
        return false;
      }
      runInProgress = true;
      return true;
    }
  }

  private boolean isActiveSocketThread(SocketThread socketThread) {
    synchronized (lifecycleMonitor) {
      return activeSocketThread == socketThread;
    }
  }

  private void clearActiveSocketThread(SocketThread socketThread) {
    synchronized (lifecycleMonitor) {
      if (activeSocketThread == socketThread) {
        activeSocketThread = null;
      }
    }
  }

  protected class SocketThread extends Thread {
    /**
     * Connection loop with explicit protocol handshake to enforce username registration before
     * entering the main message stream.
     */
    @Override
    public void run() {
      ChatConnection openedConnection = null;
      try {
        try (var socket =
            ChatSockets.openClientSocket(
                resolvedServerAddress, resolvedServerPort, resolvedTlsConfig)) {
          if (disconnectRequested) {
            return;
          }
          try (ChatConnection activeConnection = new ChatConnection(socket)) {
            openedConnection = activeConnection;
            connection = activeConnection;
            clientHandshake();
            clientMainLoop();
          }
        }
      } catch (Exception ex) {
        if (!disconnectRequested) {
          recordConnectionError(ex.getMessage());
          LOG.warning("Connection error: " + lastConnectionError);
        } else {
          LOG.fine("Connection loop stopped after disconnect");
        }
      } finally {
        resolvedAccountToken = "";
        if (connection == openedConnection) {
          connection = null;
        }
        if (isActiveSocketThread(this)) {
          notifyConnectionStatusChanged(false);
          clearActiveSocketThread(this);
        }
      }
    }

    /**
     * Default client handshake: wait for server NAME_REQUEST and respond with user name, failing
     * fast on protocol violations.
     */
    protected void clientHandshake() throws IOException {
      // Handshake follows the protocol contract:
      // server sends NAME_REQUEST, client answers with USER_NAME, server returns NAME_ACCEPTED.
      long deadline = System.nanoTime() + HANDSHAKE_TIMEOUT.toNanos();
      for (int frameCount = 0; frameCount < MAX_HANDSHAKE_FRAMES; frameCount++) {
        ChatMessage message = currentConnection().receive(remainingDuration(deadline));
        requireSupportedProtocol(message);
        if (message.type() == MessageType.NAME_REQUEST) {
          currentConnection()
              .send(
                  ChatMessage.withData(
                      MessageType.USER_NAME,
                      LoginCredentials.encode(resolvedUserName, resolvedAccountToken),
                      null));
        } else if (message.type() == MessageType.NAME_ACCEPTED) {
          if (disconnectRequested) {
            throw new IOException("Connection was cancelled");
          }
          resolvedAccountToken = "";
          lastConnectionError = null;
          notifyConnectionStatusChanged(true);
          return;
        } else if (message.type() == MessageType.ERROR) {
          throw new IOException(message.data());
        } else {
          throw new IOException("Unexpected message type: " + message.type());
        }
      }
      throw new IOException("Too many handshake frames");
    }

    /**
     * Dispatches normalized protocol events to handlers. Unexpected frames are treated as protocol
     * errors and close the connection.
     */
    protected void clientMainLoop() throws IOException {
      while (true) {
        ChatMessage message = currentConnection().receive(READ_TIMEOUT);
        validateServerFrame(message);
        MessageType type = message.type();
        switch (type) {
          case USER_ADDED -> informAboutAddingNewUser(message.data());
          case USER_REMOVED -> informAboutDeletingNewUser(message.data());
          case ROOM_ADDED -> informAboutRoomAdded(message.room());
          case ROOM_JOINED -> informAboutRoomJoined(message.room());
          case ROOM_LEFT -> informAboutRoomLeft(message.room());
          case TEXT, ROOM_TEXT, PRIVATE_TEXT -> processIncomingMessage(message);
          case ERROR -> LOG.warning("Server error: " + safeError(message.data()));
          default -> throw new IOException("Unexpected message type: " + type);
        }
      }
    }

    protected void processIncomingMessage(ChatMessage message) {
      System.out.println(formatTextMessage(message));
    }

    protected void informAboutAddingNewUser(String userName) {
      System.out.printf("Участник добавлен: %s%n", userName);
    }

    protected void informAboutDeletingNewUser(String userName) {
      System.out.printf("Участник вышел: %s%n", userName);
    }

    protected void informAboutRoomAdded(String roomName) {
      System.out.printf("Комната доступна: %s%n", roomName);
    }

    protected void informAboutRoomJoined(String roomName) {
      System.out.printf("Вы вошли в комнату: %s%n", roomName);
    }

    protected void informAboutRoomLeft(String roomName) {
      System.out.printf("Вы вышли из комнаты: %s%n", roomName);
    }

    /**
     * Updates the shared connection state before client-specific hooks run. Subclasses should
     * override {@link #onConnectionStatusChanged(boolean)} for UI or console side effects.
     */
    protected final void notifyConnectionStatusChanged(boolean clientConnected) {
      setConnectionStatus(clientConnected);
      ChatClient.this.onClientConnectionStatusChanged(clientConnected);
      onConnectionStatusChanged(clientConnected);
    }

    protected void onConnectionStatusChanged(boolean clientConnected) {}

    protected final ChatConnection currentConnection() throws IOException {
      ChatConnection activeConnection = connection;
      if (activeConnection == null) {
        throw new IOException("Connection closed");
      }
      return activeConnection;
    }

    protected final String formatTextMessage(ChatMessage message) {
      String sender = message.sender();
      String prefix = "";
      if (message.type() == MessageType.PRIVATE_TEXT) {
        prefix = String.format("[private -> %s] ", message.recipient());
      } else if (message.room() != null && !message.room().isBlank()) {
        prefix = String.format("[%s] ", message.room());
      }
      if (sender == null || sender.isBlank()) {
        return prefix + message.data();
      }
      return String.format("%s%s: %s", prefix, sender, message.data());
    }

    private Duration remainingDuration(long deadline) throws IOException {
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        throw new IOException("Client handshake timed out");
      }
      return Duration.ofNanos(remainingNanos);
    }

    private void validateServerFrame(ChatMessage message) throws IOException {
      requireSupportedProtocol(message);
      switch (message.type()) {
        case USER_ADDED, USER_REMOVED -> {
          if (!AccountStore.isValidUserName(message.data())) {
            throw new IOException("Server sent an invalid user event");
          }
        }
        case ROOM_ADDED, ROOM_JOINED, ROOM_LEFT -> {
          if (!isValidRoomName(message.room())) {
            throw new IOException("Server sent an invalid room event");
          }
        }
        case TEXT -> requireValidSender(message);
        case ROOM_TEXT -> {
          requireValidSender(message);
          if (!isValidRoomName(message.room())) {
            throw new IOException("Server sent an invalid room message");
          }
        }
        case PRIVATE_TEXT -> {
          requireValidSender(message);
          if (!AccountStore.isValidUserName(message.recipient())) {
            throw new IOException("Server sent an invalid private message");
          }
        }
        default -> {
          // Message-type dispatch below decides whether the remaining control frames are expected.
        }
      }
    }

    private void requireValidSender(ChatMessage message) throws IOException {
      if (!AccountStore.isValidUserName(message.sender())) {
        throw new IOException("Server sent a message with an invalid sender");
      }
    }

    private boolean isValidRoomName(String roomName) {
      return roomName != null
          && !roomName.isBlank()
          && roomName.length() <= ChatMessage.MAX_METADATA_LENGTH
          && roomName.matches("[\\p{L}\\p{N}_-]+");
    }

    private void requireSupportedProtocol(ChatMessage message) throws IOException {
      if (message.protocolVersion() != ChatMessage.PROTOCOL_VERSION) {
        throw new IOException("Unsupported server protocol version: " + message.protocolVersion());
      }
    }
  }
}
