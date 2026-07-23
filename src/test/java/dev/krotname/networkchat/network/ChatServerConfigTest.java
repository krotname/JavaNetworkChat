package dev.krotname.networkchat.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatServerConfigTest {

  @Test
  void defaultAndPortSpecificConfigAreValid() {
    ChatServerConfig defaults = ChatServerConfig.defaultConfig();
    ChatServerConfig configuredPort = ChatServerConfig.ofPort(0);

    assertEquals(ChatServerConfig.DEFAULT_PORT, defaults.port());
    assertEquals(ChatServerConfig.DEFAULT_BIND_ADDRESS, defaults.bindAddress());
    assertEquals(ChatServerConfig.DEFAULT_MAX_CLIENTS, defaults.maxClients());
    assertEquals(0, configuredPort.port());
    assertEquals(ChatServerConfig.DEFAULT_MAX_CLIENTS, configuredPort.maxClients());
  }

  @Test
  void convertsSocketTimeoutsForServerUse() {
    ChatServerConfig roundedTimeouts =
        new ChatServerConfig(1500, 1, Duration.ofNanos(1), Duration.ofNanos(1));

    assertEquals(1, roundedTimeouts.handshakeTimeoutMillis());
    assertEquals(1, roundedTimeouts.readTimeoutMillis());
  }

  @Test
  void rejectsInvalidLimitsAndTimeouts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(-1, 1, Duration.ZERO, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(65_536, 1, Duration.ZERO, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(1500, 0, Duration.ZERO, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(1500, 1, Duration.ZERO, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(1500, 1, Duration.ofSeconds(1), Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(1500, 1, Duration.ofMillis(-1), Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatServerConfig(1500, 1, Duration.ZERO, Duration.ofMillis(-1)));
    assertThrows(
        NullPointerException.class,
        () -> new ChatServerConfig(1500, 1, null, Duration.ofSeconds(1)));
    assertThrows(
        NullPointerException.class,
        () -> new ChatServerConfig(1500, 1, Duration.ofSeconds(1), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatServerConfig(
                1500, 1, Duration.ofMillis((long) Integer.MAX_VALUE + 1L), Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatServerConfig(
                1500, 1, Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(1)));
  }

  @Test
  void legacyServerConstructorKeepsConfigAccessible() {
    ChatServer server = new ChatServer(0);

    assertEquals(0, server.getPort());
    assertEquals(0, server.getConfig().port());
    assertFalse(server.isRunning());
    server.close();
  }

  @Test
  void supportsAccountsHistoryAndTlsConfig() {
    Path accounts = Path.of("accounts.csv");
    Path history = Path.of("history.jsonl");
    TlsServerConfig tls = TlsServerConfig.enabled(Path.of("chat.p12"), "changeit");

    ChatServerConfig config =
        ChatServerConfig.ofPort(1600)
            .withAccounts(accounts)
            .withHistory(history, 25, 5)
            .withTls(tls);

    assertEquals(accounts, config.accountFile());
    assertEquals(history, config.historyFile());
    assertEquals(25, config.historyLimit());
    assertEquals(5, config.historyReplayLimit());
    assertEquals(tls, config.tls());
  }

  @Test
  void exposesRoomCapAndPerConnectionRateLimits() {
    ChatServerConfig defaults = ChatServerConfig.defaultConfig();

    assertEquals(ChatServerConfig.DEFAULT_MAX_ROOMS, defaults.maxRooms());
    assertEquals(RateLimitConfig.defaultLimits(), defaults.rateLimit());

    ChatServerConfig tightened =
        defaults.withMaxRooms(8).withRateLimit(new RateLimitConfig(5, 1, 2, 0.5));

    assertEquals(8, tightened.maxRooms());
    assertEquals(2, tightened.rateLimit().roomCreationBurst());
  }

  @Test
  void rejectsInvalidRoomCapAndRateLimits() {
    ChatServerConfig defaults = ChatServerConfig.defaultConfig();

    assertThrows(IllegalArgumentException.class, () -> assertNotNull(defaults.withMaxRooms(0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> assertNotNull(defaults.withMaxRooms(ChatServerConfig.MAX_ROOMS + 1)));
    assertThrows(NullPointerException.class, () -> assertNotNull(defaults.withRateLimit(null)));
    assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(1, 0, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RateLimitConfig(1, 1, 1, Double.POSITIVE_INFINITY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RateLimitConfig(RateLimitConfig.MAX_BURST + 1, 1, 1, 1));
  }

  @Test
  void rejectsIncompleteTlsConfig() {
    assertThrows(IllegalArgumentException.class, () -> TlsServerConfig.enabled(null, "changeit"));
    assertThrows(
        IllegalArgumentException.class, () -> TlsServerConfig.enabled(Path.of("chat.p12"), ""));
  }

  @Test
  void parsesServerOptionsAndReadsTlsSecretsOnlyFromEnvironment() {
    ChatServerConfig config =
        ChatServer.parseConfig(
            new String[] {
              "--port", "1600",
              "--bind", "0.0.0.0",
              "--max-rooms", "16",
              "--tls-keystore", "chat.p12"
            },
            Map.of(
                ChatServer.ENV_TLS_KEYSTORE_PASSWORD,
                "store-secret",
                ChatServer.ENV_TLS_KEY_PASSWORD,
                "key-secret"));

    assertEquals(1600, config.port());
    assertEquals("0.0.0.0", config.bindAddress());
    assertEquals(16, config.maxRooms());
    assertEquals("store-secret", config.tls().keyStorePassword());
    assertEquals("key-secret", config.tls().keyPassword());
    assertThrows(
        IllegalArgumentException.class,
        () -> ChatServer.parseConfig(new String[] {"--tls-password", "secret"}, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChatServer.parseConfig(new String[] {"--port"}, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChatServer.parseConfig(new String[] {"--unknown", "value"}, Map.of()));
  }

  @Test
  void failedBindDoesNotLeaveServerMarkedRunning() throws Exception {
    try (ServerSocket blocker =
            new ServerSocket(0, 1, InetAddress.getByName(ChatServerConfig.DEFAULT_BIND_ADDRESS));
        ChatServer server = new ChatServer(blocker.getLocalPort())) {
      assertThrows(java.io.IOException.class, server::start);
      assertFalse(server.isRunning());
    }
  }
}
