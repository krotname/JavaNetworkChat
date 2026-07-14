package dev.krotname.networkchat.client.gui;

import dev.krotname.networkchat.network.AccountStore;
import dev.krotname.networkchat.network.ChatServerConfig;

/** Immutable GUI connection settings collected before a socket session starts. */
public record ConnectionSettings(
    String serverAddress, int serverPort, String userName, String accountToken) {
  public static final String DEFAULT_SERVER_ADDRESS = "localhost";
  public static final String DEFAULT_USER_NAME = "user";

  public ConnectionSettings {
    if (serverAddress == null || serverAddress.isBlank()) {
      throw new IllegalArgumentException("Server address is required");
    }
    serverAddress = serverAddress.trim();
    if (serverAddress.length() > 253 || serverAddress.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Server address is invalid");
    }
    userName = userName == null ? null : userName.trim();
    if (!AccountStore.isValidUserName(userName)) {
      throw new IllegalArgumentException(
          "User name must contain 3..64 letters, digits, '_' or '-'");
    }
    accountToken = accountToken == null ? "" : accountToken;
    if (serverPort < 1 || serverPort > 65_535) {
      throw new IllegalArgumentException("Port must be in range 1..65535");
    }
  }

  public static ConnectionSettings defaults() {
    return new ConnectionSettings(
        DEFAULT_SERVER_ADDRESS, ChatServerConfig.DEFAULT_PORT, DEFAULT_USER_NAME, "");
  }

  public static ConnectionSettings fromInput(
      String serverAddress, String serverPort, String userName, String accountToken) {
    if (serverPort == null || serverPort.isBlank()) {
      throw new IllegalArgumentException("Port is required");
    }
    try {
      return new ConnectionSettings(
          serverAddress, Integer.parseInt(serverPort.trim()), userName, accountToken);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Port must be a number", ex);
    }
  }
}
