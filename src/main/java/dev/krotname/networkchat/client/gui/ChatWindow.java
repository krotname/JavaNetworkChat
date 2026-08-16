package dev.krotname.networkchat.client.gui;

import dev.krotname.networkchat.client.ClientGuiController;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Lightweight Swing view implementation. */
public final class ChatWindow {
  private static final DateTimeFormatter TIME_FORMATTER =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Controller lifecycle is intentionally bound to the view lifecycle.")
  private final ClientGuiController controller;

  private JFrame frame;
  private JPanel connectionPanel;
  private JLabel connectionErrorLabel;
  private JTextField addressField;
  private JTextField portField;
  private JTextField userNameField;
  private JPasswordField accountTokenField;
  private JComboBox<String> roomSelector;
  private JTextField roomNameField;
  private JButton joinRoomButton;
  private JButton leaveRoomButton;
  private JTextField privateRecipientField;
  private JTextField searchField;
  private JTextField messageField;
  private JButton sendButton;
  private JButton connectButton;
  private JButton cancelButton;
  private JButton reconnectButton;
  private JButton copyTimelineButton;
  private JButton selectTimelineButton;
  private JButton clearTimelineButton;
  private JButton searchTimelineButton;
  private JButton resetSearchButton;
  private JButton exportJsonButton;
  private JButton exportCsvButton;
  private JLabel statusLabel;
  private JTextArea messages;
  private JTextArea users;
  private ConnectionRequest pendingConnectionRequest;
  private boolean connected;
  private String currentUserName = "";
  private String currentError = "";
  private String timelineFilter = "";

  public ChatWindow(ClientGuiController controller) {
    this(controller, true);
  }

  public ChatWindow(ClientGuiController controller, boolean visible) {
    this.controller = controller;
    runOnEdt(() -> init(visible));
  }

  private void init(boolean visible) {
    frame = new JFrame("Network Chat");
    addressField = new JTextField(22);
    portField = new JTextField(8);
    userNameField = new JTextField(18);
    accountTokenField = new JPasswordField(18);
    roomSelector = new JComboBox<>();
    roomNameField = new JTextField(12);
    joinRoomButton = new JButton("Войти");
    leaveRoomButton = new JButton("Выйти");
    privateRecipientField = new JTextField(12);
    searchField = new JTextField(12);
    connectButton = new JButton("Подключиться");
    cancelButton = new JButton("Отмена");
    reconnectButton = new JButton("Повторить");
    copyTimelineButton = new JButton("Копировать");
    selectTimelineButton = new JButton("Выделить всё");
    clearTimelineButton = new JButton("Очистить");
    searchTimelineButton = new JButton("Найти");
    resetSearchButton = new JButton("Сброс");
    exportJsonButton = new JButton("JSON");
    exportCsvButton = new JButton("CSV");
    connectionErrorLabel = new JLabel(" ");
    connectionPanel = buildConnectionPanel();
    messageField = new JTextField(50);
    sendButton = new JButton("Отправить");
    statusLabel = new JLabel("Не подключено");
    messages = new JTextArea(10, 50);
    users = new JTextArea(10, 10);

    messages.setEditable(false);
    users.setEditable(false);
    messageField.setEditable(false);
    roomSelector.setEnabled(false);
    roomNameField.setEnabled(false);
    joinRoomButton.setEnabled(false);
    leaveRoomButton.setEnabled(false);
    privateRecipientField.setEnabled(false);
    sendButton.setEnabled(false);
    reconnectButton.setEnabled(false);
    messages.setLineWrap(true);
    messages.setWrapStyleWord(true);

    frame.setLayout(new BorderLayout(8, 8));
    frame.add(connectionPanel, BorderLayout.NORTH);
    frame.add(new JScrollPane(messages), BorderLayout.CENTER);
    frame.add(new JScrollPane(users), BorderLayout.EAST);

    JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
    inputPanel.add(messageField, BorderLayout.CENTER);
    inputPanel.add(sendButton, BorderLayout.EAST);

    JPanel bottomPanel = new JPanel(new BorderLayout(0, 6));
    bottomPanel.add(statusLabel, BorderLayout.NORTH);
    bottomPanel.add(buildRoomPanel(), BorderLayout.WEST);
    bottomPanel.add(inputPanel, BorderLayout.CENTER);
    bottomPanel.add(buildTimelineActionsPanel(), BorderLayout.SOUTH);
    frame.add(bottomPanel, BorderLayout.SOUTH);

    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            // Otherwise a thread waiting in requestConnectionSettings never wakes up.
            cancelConnectionSettings();
            controller.disconnect();
          }
        });
    frame.pack();
    frame.setVisible(visible);

    messageField.addActionListener(
        (ActionEvent e) -> {
          sendCurrentMessage();
        });
    sendButton.addActionListener((ActionEvent e) -> sendCurrentMessage());
    connectButton.addActionListener((ActionEvent e) -> submitConnectionSettings());
    cancelButton.addActionListener((ActionEvent e) -> cancelConnectionSettings());
    reconnectButton.addActionListener((ActionEvent e) -> controller.reconnectLastSettingsAsync());
    roomSelector.addActionListener((ActionEvent e) -> selectCurrentRoom());
    joinRoomButton.addActionListener(
        (ActionEvent e) -> controller.joinRoom(roomNameField.getText()));
    leaveRoomButton.addActionListener((ActionEvent e) -> controller.leaveSelectedRoom());
    copyTimelineButton.addActionListener((ActionEvent e) -> copyTimeline());
    selectTimelineButton.addActionListener((ActionEvent e) -> messages.selectAll());
    clearTimelineButton.addActionListener((ActionEvent e) -> controller.clearTimeline());
    searchTimelineButton.addActionListener((ActionEvent e) -> applyTimelineSearch());
    resetSearchButton.addActionListener((ActionEvent e) -> resetTimelineSearch());
    exportJsonButton.addActionListener(
        (ActionEvent e) -> copyText(controller.getModel().exportTimelineAsJson()));
    exportCsvButton.addActionListener(
        (ActionEvent e) -> copyText(controller.getModel().exportTimelineAsCsv()));
  }

  public ConnectionSettings requestConnectionSettings(
      ConnectionSettings defaults, String errorMessage) {
    ConnectionRequest request = new ConnectionRequest();
    runOnEdt(
        () -> {
          pendingConnectionRequest = request;
          addressField.setText(defaults.serverAddress());
          portField.setText(Integer.toString(defaults.serverPort()));
          userNameField.setText(defaults.userName());
          accountTokenField.setText(defaults.accountToken());
          connectionErrorLabel.setText(normalizeError(errorMessage));
          connectionPanel.setVisible(true);
          connectButton.setEnabled(true);
          cancelButton.setEnabled(true);
          reconnectButton.setEnabled(errorMessage != null && !errorMessage.isBlank());
          frame.pack();
          updateStatusLabel();
        });
    try {
      request.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return null;
    }
    return request.result();
  }

  public void notifyConnectionStatusChanged(
      boolean clientConnected, String userName, String errorMessage, int userCount) {
    runOnEdt(
        () -> {
          connected = clientConnected;
          currentUserName = userName == null ? "" : userName;
          currentError = errorMessage == null ? "" : errorMessage;
          messageField.setEditable(clientConnected);
          sendButton.setEnabled(clientConnected);
          roomSelector.setEnabled(clientConnected);
          roomNameField.setEnabled(clientConnected);
          joinRoomButton.setEnabled(clientConnected);
          leaveRoomButton.setEnabled(clientConnected);
          privateRecipientField.setEnabled(clientConnected);
          reconnectButton.setEnabled(!clientConnected && !currentUserName.isBlank());
          if (clientConnected) {
            connectionPanel.setVisible(false);
          } else {
            connectionPanel.setVisible(true);
            connectionErrorLabel.setText(normalizeError(errorMessage));
          }
          updateStatusLabel(userCount);
          frame.pack();
        });
  }

  public void showError(String message) {
    runOnEdt(
        () ->
            JOptionPane.showMessageDialog(
                frame, message, "Network Chat", JOptionPane.ERROR_MESSAGE));
  }

  public void prepareReconnectAttempt() {
    runOnEdt(
        () -> {
          messageField.setEditable(false);
          sendButton.setEnabled(false);
          roomSelector.setEnabled(false);
          roomNameField.setEnabled(false);
          joinRoomButton.setEnabled(false);
          leaveRoomButton.setEnabled(false);
          privateRecipientField.setEnabled(false);
          connectButton.setEnabled(false);
          cancelButton.setEnabled(false);
          reconnectButton.setEnabled(false);
          connectionPanel.setVisible(true);
          connectionErrorLabel.setText(" ");
          updateStatusLabel("Повторное подключение...");
          frame.pack();
        });
  }

  public void refreshMessages() {
    runOnEdt(
        () -> {
          messages.setText(renderTimeline());
          messages.setCaretPosition(messages.getDocument().getLength());
        });
  }

  public void refreshUsers() {
    runOnEdt(
        () -> {
          StringBuilder sb = new StringBuilder();
          for (String user : new TreeSet<>(controller.getModel().getAllUserNames())) {
            sb.append(user).append('\n');
          }
          users.setText(sb.toString());
          updateStatusLabel();
        });
  }

  public void refreshRooms(String selectedRoom) {
    runOnEdt(
        () -> {
          DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
          for (String room : controller.getModel().getAllRoomNames()) {
            model.addElement(room);
          }
          roomSelector.setModel(model);
          if (selectedRoom != null && !selectedRoom.isBlank()) {
            roomSelector.setSelectedItem(selectedRoom);
          }
        });
  }

  private void runOnEdt(Runnable action) {
    // Ensure deterministic Swing state updates even when background threads push data.
    if (SwingUtilities.isEventDispatchThread()) {
      action.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(action);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (InvocationTargetException ex) {
      throw new IllegalStateException("UI update failed", ex);
    }
  }

  public String getMessagesText() {
    return callOnEdt(messages::getText);
  }

  public String getUsersText() {
    return callOnEdt(users::getText);
  }

  public boolean isMessageInputEditable() {
    return callOnEdt(messageField::isEditable);
  }

  public boolean isReconnectButtonEnabled() {
    return callOnEdt(reconnectButton::isEnabled);
  }

  public boolean isConnectionPanelVisible() {
    return callOnEdt(connectionPanel::isVisible);
  }

  public String getStatusText() {
    return callOnEdt(statusLabel::getText);
  }

  public int getRoomCount() {
    return callOnEdt(roomSelector::getItemCount);
  }

  public String getSelectedRoom() {
    return callOnEdt(
        () -> {
          Object selected = roomSelector.getSelectedItem();
          return selected == null ? "" : selected.toString();
        });
  }

  public void dispose() {
    runOnEdt(frame::dispose);
  }

  private void sendCurrentMessage() {
    String text = messageField.getText();
    if (controller.sendTextMessage(text, privateRecipientField.getText())) {
      messageField.setText("");
    }
  }

  private void submitConnectionSettings() {
    try {
      ConnectionSettings settings =
          ConnectionSettings.fromInput(
              addressField.getText(),
              portField.getText(),
              userNameField.getText(),
              new String(accountTokenField.getPassword()));
      connectionErrorLabel.setText(" ");
      connectButton.setEnabled(false);
      cancelButton.setEnabled(false);
      reconnectButton.setEnabled(false);
      updateStatusLabel("Подключение...");
      ConnectionRequest request = pendingConnectionRequest;
      if (request != null) {
        request.complete(settings);
      }
    } catch (IllegalArgumentException ex) {
      connectionErrorLabel.setText(ex.getMessage());
      updateStatusLabel();
    }
  }

  private void cancelConnectionSettings() {
    ConnectionRequest request = pendingConnectionRequest;
    if (request != null) {
      request.complete(null);
    }
  }

  private JPanel buildConnectionPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.insets = new Insets(4, 4, 4, 4);
    constraints.anchor = GridBagConstraints.WEST;
    constraints.fill = GridBagConstraints.HORIZONTAL;

    int row = 0;
    connectionErrorLabel.setForeground(Color.RED.darker());
    constraints.gridx = 0;
    constraints.gridy = row++;
    constraints.gridwidth = 4;
    panel.add(connectionErrorLabel, constraints);
    constraints.gridwidth = 1;
    addConnectionRow(panel, constraints, row++, "Сервер:", addressField);
    addConnectionRow(panel, constraints, row++, "Порт:", portField);
    addConnectionRow(panel, constraints, row++, "Имя:", userNameField);
    addConnectionRow(panel, constraints, row++, "Токен:", accountTokenField);

    constraints.gridx = 0;
    constraints.gridy = row;
    constraints.weightx = 0;
    panel.add(connectButton, constraints);
    constraints.gridx = 1;
    panel.add(reconnectButton, constraints);
    constraints.gridx = 2;
    panel.add(cancelButton, constraints);
    return panel;
  }

  private JPanel buildTimelineActionsPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    panel.add(copyTimelineButton);
    panel.add(selectTimelineButton);
    panel.add(clearTimelineButton);
    panel.add(new JLabel("Поиск:"));
    panel.add(searchField);
    panel.add(searchTimelineButton);
    panel.add(resetSearchButton);
    panel.add(new JLabel("Экспорт:"));
    panel.add(exportJsonButton);
    panel.add(exportCsvButton);
    return panel;
  }

  private JPanel buildRoomPanel() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    panel.add(new JLabel("Комната:"));
    panel.add(roomSelector);
    panel.add(roomNameField);
    panel.add(joinRoomButton);
    panel.add(leaveRoomButton);
    panel.add(new JLabel("Лично:"));
    panel.add(privateRecipientField);
    return panel;
  }

  private void addConnectionRow(
      JPanel panel, GridBagConstraints constraints, int row, String label, JTextField field) {
    constraints.gridx = 0;
    constraints.gridy = row;
    constraints.weightx = 0;
    panel.add(new JLabel(label), constraints);
    constraints.gridx = 1;
    constraints.weightx = 1;
    panel.add(field, constraints);
  }

  private void updateStatusLabel() {
    updateStatusLabel(controller.getModel().getUserCount());
  }

  private void updateStatusLabel(int userCount) {
    if (connected) {
      statusLabel.setText("Подключено. Вы: " + currentUserName + ". Участников: " + userCount);
      return;
    }
    String suffix = currentError == null || currentError.isBlank() ? "" : ". " + currentError;
    statusLabel.setText("Нет соединения" + suffix);
  }

  private void updateStatusLabel(String message) {
    statusLabel.setText(message);
  }

  private String normalizeError(String errorMessage) {
    return errorMessage == null || errorMessage.isBlank() ? " " : errorMessage;
  }

  private String renderTimeline() {
    StringBuilder sb = new StringBuilder();
    for (TimelineEntry entry : controller.getModel().searchTimeline(timelineFilter)) {
      sb.append(renderTimelineEntry(entry)).append('\n');
    }
    return sb.toString();
  }

  private String renderTimelineEntry(TimelineEntry entry) {
    String time = TIME_FORMATTER.format(Instant.ofEpochMilli(entry.timestamp()));
    if (entry.type() == TimelineEntry.Type.SERVICE) {
      return String.format("[%s] * %s", time, entry.text());
    }
    String scope = "";
    if (entry.recipient() != null && !entry.recipient().isBlank()) {
      scope = String.format("[лично -> %s] ", entry.recipient());
    } else if (entry.room() != null && !entry.room().isBlank()) {
      scope = String.format("[%s] ", entry.room());
    }
    String sender = entry.ownMessage() ? "Вы" : entry.sender();
    if (sender == null || sender.isBlank()) {
      sender = "unknown";
    }
    return String.format("[%s] %s%s: %s", time, scope, sender, entry.text());
  }

  private void copyTimeline() {
    messages.selectAll();
    messages.copy();
  }

  private void copyText(String text) {
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
  }

  private void applyTimelineSearch() {
    timelineFilter = searchField.getText();
    refreshMessages();
  }

  private void resetTimelineSearch() {
    timelineFilter = "";
    searchField.setText("");
    refreshMessages();
  }

  private void selectCurrentRoom() {
    Object selected = roomSelector.getSelectedItem();
    if (selected != null) {
      controller.selectRoom(selected.toString());
    }
  }

  private <T> T callOnEdt(Supplier<T> action) {
    if (SwingUtilities.isEventDispatchThread()) {
      return action.get();
    }
    AtomicReference<T> result = new AtomicReference<>();
    runOnEdt(() -> result.set(action.get()));
    return result.get();
  }

  private static final class ConnectionRequest {
    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<ConnectionSettings> result = new AtomicReference<>();

    void complete(ConnectionSettings settings) {
      result.set(settings);
      latch.countDown();
    }

    void await() throws InterruptedException {
      latch.await();
    }

    ConnectionSettings result() {
      return result.get();
    }
  }
}
