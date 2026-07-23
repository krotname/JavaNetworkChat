package dev.krotname.networkchat.client;

import dev.krotname.networkchat.client.gui.ChatWindow;
import dev.krotname.networkchat.client.gui.ClientGuiModel;
import dev.krotname.networkchat.client.gui.ConnectionSettings;
import dev.krotname.networkchat.client.gui.ConnectionSettingsStore;
import dev.krotname.networkchat.protocol.ChatMessage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.atomic.AtomicBoolean;

/** GUI-specific client controller. */
public final class ClientGuiController extends ChatClient {
  private static final long RECONNECT_BACKOFF_MILLIS = 500L;

  private final ClientGuiModel model = new ClientGuiModel();
  private final ConnectionSettingsStore settingsStore;
  private final AtomicBoolean connectionAttemptInProgress = new AtomicBoolean();
  private final ChatWindow view;
  private volatile ConnectionSettings connectionSettings;
  private volatile String currentRoom = ChatMessage.GENERAL_ROOM;

  public static void main(String[] args) {
    new ClientGuiController().run();
  }

  public ClientGuiController() {
    this(true);
  }

  public ClientGuiController(boolean visibleWindow) {
    this(visibleWindow, new ConnectionSettingsStore());
  }

  public ClientGuiController(boolean visibleWindow, ConnectionSettingsStore settingsStore) {
    this.settingsStore = settingsStore;
    connectionSettings = settingsStore.load();
    view = new ChatWindow(this, visibleWindow);
  }

  @Override
  public void run() {
    while (!Thread.currentThread().isInterrupted()) {
      ConnectionSettings requestedSettings =
          view.requestConnectionSettings(connectionSettings, getLastConnectionError());
      if (requestedSettings == null) {
        dispose();
        return;
      }
      connectionSettings = requestedSettings;
      runConnectionAttempt();
      if (isClientConnected()) {
        return;
      }
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "GUI model is intentionally mutable for controller-view synchronization.")
  public ClientGuiModel getModel() {
    return model;
  }

  @Override
  public String getServerAddress() {
    return connectionSettings.serverAddress();
  }

  @Override
  public int getServerPort() {
    return connectionSettings.serverPort();
  }

  @Override
  public String getUserName() {
    return connectionSettings.userName();
  }

  @Override
  protected String getAccountToken() {
    return connectionSettings.accountToken();
  }

  @Override
  protected boolean shouldSendTextFromConsole() {
    return false;
  }

  @Override
  protected SocketThread getSocketThread() {
    return new GuiSocketThread();
  }

  public void dispose() {
    view.dispose();
  }

  public void reconnectLastSettingsAsync() {
    if (!connectionAttemptInProgress.compareAndSet(false, true)) {
      return;
    }
    Thread reconnectThread =
        new Thread(
            () -> {
              try {
                view.prepareReconnectAttempt();
                Thread.sleep(RECONNECT_BACKOFF_MILLIS);
                runConnectionAttemptLocked();
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              } finally {
                connectionAttemptInProgress.set(false);
              }
            },
            "chat-gui-reconnect");
    reconnectThread.setDaemon(true);
    reconnectThread.start();
  }

  public void clearTimeline() {
    model.clearTimeline();
    view.refreshMessages();
  }

  public void selectRoom(String roomName) {
    if (roomName == null || roomName.isBlank()) {
      return;
    }
    currentRoom = roomName.trim();
  }

  public void joinRoom(String roomName) {
    if (roomName == null || roomName.isBlank()) {
      view.showError("Введите имя комнаты");
      return;
    }
    try {
      if (!sendMessage(ChatMessage.roomJoin(roomName.trim()))) {
        view.showError("Не удалось войти в комнату");
      }
    } catch (IllegalArgumentException ex) {
      view.showError("Некорректное имя комнаты");
    }
  }

  public void leaveSelectedRoom() {
    if (ChatMessage.GENERAL_ROOM.equals(currentRoom)) {
      view.showError("Нельзя выйти из комнаты general");
      return;
    }
    try {
      if (!sendMessage(ChatMessage.roomLeave(currentRoom))) {
        view.showError("Не удалось выйти из комнаты");
      }
    } catch (IllegalArgumentException ex) {
      view.showError("Некорректное имя комнаты");
    }
  }

  ChatWindow getViewForTesting() {
    return view;
  }

  @Override
  public boolean sendTextMessage(String text) {
    return sendTextMessage(text, null);
  }

  public boolean sendTextMessage(String text, String privateRecipient) {
    if (text == null || text.isBlank()) {
      return false;
    }
    if (text.length() > ChatMessage.MAX_DATA_LENGTH) {
      view.showError("Сообщение слишком длинное. Максимум: " + ChatMessage.MAX_DATA_LENGTH);
      return false;
    }
    try {
      if (privateRecipient != null && !privateRecipient.isBlank()) {
        return sendPreparedMessage(
            ChatMessage.privateText(text, getResolvedUserName(), privateRecipient));
      }
      if (!model.isJoinedRoom(currentRoom)) {
        view.showError("Сначала войдите в комнату " + currentRoom);
        return false;
      }
      return sendPreparedMessage(ChatMessage.roomText(text, getResolvedUserName(), currentRoom));
    } catch (IllegalArgumentException ex) {
      view.showError("Некорректные параметры сообщения");
      return false;
    }
  }

  private boolean sendPreparedMessage(ChatMessage message) {
    boolean sent = sendMessage(message);
    if (!sent && getLastConnectionError() != null) {
      view.showError(getLastConnectionError());
    }
    return sent;
  }

  @Override
  protected void onClientConnectionStatusChanged(boolean connected) {
    view.notifyConnectionStatusChanged(
        connected, getResolvedUserName(), getLastConnectionError(), model.getUserCount());
  }

  private void runConnectionAttempt() {
    if (!connectionAttemptInProgress.compareAndSet(false, true)) {
      return;
    }
    try {
      runConnectionAttemptLocked();
    } finally {
      connectionAttemptInProgress.set(false);
    }
  }

  private void runConnectionAttemptLocked() {
    settingsStore.save(connectionSettings);
    model.clearUsers();
    model.clearRooms();
    currentRoom = ChatMessage.GENERAL_ROOM;
    view.refreshUsers();
    view.refreshRooms(currentRoom);
    super.run();
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Inner thread intentionally uses outer controller for UI callbacks.")
  public final class GuiSocketThread extends SocketThread {
    @Override
    protected void processIncomingMessage(ChatMessage message) {
      if (model.addTextMessage(message, getResolvedUserName())) {
        view.refreshMessages();
      }
    }

    @Override
    protected void informAboutAddingNewUser(String userName) {
      if (!model.addUser(userName)) {
        return;
      }
      if (getResolvedUserName() != null && getResolvedUserName().equals(userName)) {
        model.addServiceMessage("Вы подключились к чату");
      } else {
        model.addServiceMessage("Пользователь " + userName + " в чате");
      }
      view.refreshUsers();
      view.refreshMessages();
    }

    @Override
    protected void informAboutDeletingNewUser(String userName) {
      if (!model.deleteUser(userName)) {
        return;
      }
      model.addServiceMessage("Пользователь " + userName + " вышел из чата");
      view.refreshUsers();
      view.refreshMessages();
    }

    @Override
    protected void informAboutRoomAdded(String roomName) {
      model.addRoom(roomName);
      view.refreshRooms(currentRoom);
    }

    @Override
    protected void informAboutRoomRemoved(String roomName) {
      if (!model.removeRoom(roomName)) {
        return;
      }
      if (roomName.equals(currentRoom)) {
        currentRoom = ChatMessage.GENERAL_ROOM;
      }
      model.addServiceMessage("Комната " + roomName + " закрыта");
      view.refreshRooms(currentRoom);
      view.refreshMessages();
    }

    @Override
    protected void informAboutRoomJoined(String roomName) {
      model.joinRoom(roomName);
      currentRoom = roomName;
      model.addServiceMessage("Вы вошли в комнату " + roomName);
      view.refreshRooms(currentRoom);
      view.refreshMessages();
    }

    @Override
    protected void informAboutRoomLeft(String roomName) {
      model.leaveRoom(roomName);
      if (roomName.equals(currentRoom)) {
        currentRoom = ChatMessage.GENERAL_ROOM;
      }
      model.addServiceMessage("Вы вышли из комнаты " + roomName);
      view.refreshRooms(currentRoom);
      view.refreshMessages();
    }
  }
}
