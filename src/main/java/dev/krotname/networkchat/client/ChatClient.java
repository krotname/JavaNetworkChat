package dev.krotname.networkchat.client;

import dev.krotname.networkchat.network.ChatConnection;
import dev.krotname.networkchat.network.ChatSockets;
import dev.krotname.networkchat.network.LoginCredentials;
import dev.krotname.networkchat.network.TlsClientConfig;
import dev.krotname.networkchat.protocol.ChatMessage;
import dev.krotname.networkchat.protocol.MessageType;
import dev.krotname.networkchat.util.ConsoleInput;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/** Template client with reusable protocol loop and overridable UI/input behavior. */
public abstract class ChatClient {
  private static final Logger LOG = Logger.getLogger(ChatClient.class.getName());
  private static final String EXIT_COMMAND = "exit";

  protected volatile boolean clientConnected;
  protected ChatConnection connection;
  private volatile String resolvedServerAddress;
  private volatile int resolvedServerPort;
  private volatile String resolvedUserName;
  private volatile String resolvedAccountToken;
  private volatile TlsClientConfig resolvedTlsConfig = TlsClientConfig.disabled();
  private volatile String lastConnectionError;
  private volatile boolean disconnectRequested;
  private volatile CountDownLatch connectionEstablishedLatch = new CountDownLatch(1);

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
    clientConnected = false;
    lastConnectionError = null;
    disconnectRequested = false;
    connectionEstablishedLatch = new CountDownLatch(1);
    try {
      String requestedServerAddress = getServerAddress();
      if (requestedServerAddress == null || requestedServerAddress.isBlank()) {
        throw new IOException("Server address is required");
      }
      resolvedServerAddress = requestedServerAddress.trim();
      resolvedServerPort = getServerPort();
      String requestedUserName = getUserName();
      if (requestedUserName == null || requestedUserName.isBlank()) {
        throw new IOException("User name is required");
      }
      resolvedUserName = requestedUserName.trim();
      String requestedAccountToken = getAccountToken();
      resolvedAccountToken =
          requestedAccountToken == null || requestedAccountToken.isBlank()
              ? ""
              : requestedAccountToken.trim();
      resolvedTlsConfig = getTlsConfig();
    } catch (IOException e) {
      recordConnectionError(e.getMessage());
      LOG.warning("Unable to determine connection settings");
      return;
    }
    SocketThread socketThread = getSocketThread();
    socketThread.start();
    try {
      connectionEstablishedLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
    return sendMessage(ChatMessage.text(text, resolvedUserName));
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
      setConnectionStatus(false);
      onClientConnectionStatusChanged(false);
      return false;
    } catch (IllegalArgumentException ex) {
      recordConnectionError(ex.getMessage());
      LOG.warning(lastConnectionError);
      return false;
    }
  }

  public void disconnect() {
    disconnectRequested = true;
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
    lastConnectionError = error == null || error.isBlank() ? "Connection error" : error;
  }

  protected class SocketThread extends Thread {
    /**
     * Connection loop with explicit protocol handshake to enforce username registration before
     * entering the main message stream.
     */
    @Override
    public void run() {
      try {
        try (var socket =
            ChatSockets.openClientSocket(
                resolvedServerAddress, resolvedServerPort, resolvedTlsConfig)) {
          connection = new ChatConnection(socket);
          clientHandshake();
          clientMainLoop();
        }
      } catch (Exception ex) {
        if (!disconnectRequested) {
          recordConnectionError(ex.getMessage());
          LOG.warning("Connection error: " + lastConnectionError);
        } else {
          LOG.fine("Connection loop stopped after disconnect");
        }
        notifyConnectionStatusChanged(false);
      }
    }

    /**
     * Default client handshake: wait for server NAME_REQUEST and respond with user name, failing
     * fast on protocol violations.
     */
    protected void clientHandshake() throws IOException {
      // Handshake follows the protocol contract:
      // server sends NAME_REQUEST, client answers with USER_NAME, server returns NAME_ACCEPTED.
      while (true) {
        ChatMessage message = currentConnection().receive();
        if (message.type() == MessageType.NAME_REQUEST) {
          currentConnection()
              .send(
                  ChatMessage.withData(
                      MessageType.USER_NAME,
                      LoginCredentials.encode(resolvedUserName, resolvedAccountToken),
                      null));
        } else if (message.type() == MessageType.NAME_ACCEPTED) {
          lastConnectionError = null;
          notifyConnectionStatusChanged(true);
          return;
        } else if (message.type() == MessageType.ERROR) {
          throw new IOException(message.data());
        } else {
          throw new IOException("Unexpected message type: " + message.type());
        }
      }
    }

    /**
     * Dispatches normalized protocol events to handlers. Unexpected frames are treated as protocol
     * errors and close the connection.
     */
    protected void clientMainLoop() throws IOException {
      while (true) {
        ChatMessage message = currentConnection().receive();
        MessageType type = message.type();
        switch (type) {
          case USER_ADDED -> informAboutAddingNewUser(message.data());
          case USER_REMOVED -> informAboutDeletingNewUser(message.data());
          case ROOM_ADDED -> informAboutRoomAdded(message.room());
          case ROOM_JOINED -> informAboutRoomJoined(message.room());
          case ROOM_LEFT -> informAboutRoomLeft(message.room());
          case TEXT, ROOM_TEXT, PRIVATE_TEXT -> processIncomingMessage(message);
          case ERROR -> LOG.warning("Server error: " + message.data());
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
  }
}
