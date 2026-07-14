package dev.krotname.networkchat.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NetworkSecuritySupportTest {

  @Test
  void tlsClientConfigReadsEnvironmentMap() {
    TlsClientConfig config =
        TlsClientConfig.fromEnvironment(
            Map.of(
                TlsClientConfig.ENV_TLS_ENABLED,
                "true",
                TlsClientConfig.ENV_TRUSTSTORE,
                "chat.p12",
                TlsClientConfig.ENV_TRUSTSTORE_PASSWORD,
                "changeit"));

    assertTrue(config.enabled());
    assertEquals(Path.of("chat.p12"), config.trustStoreFile());
    assertEquals("changeit", config.trustStorePassword());
  }

  @Test
  void tlsClientConfigRejectsTrustAllMode() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                TlsClientConfig.fromEnvironment(
                    Map.of(
                        TlsClientConfig.ENV_TLS_ENABLED,
                        "true",
                        TlsClientConfig.ENV_TRUST_ALL,
                        "1")));

    assertTrue(error.getMessage().contains(TlsClientConfig.ENV_TRUST_ALL));
    assertTrue(error.getMessage().contains(TlsClientConfig.ENV_TRUSTSTORE));
  }

  @Test
  void tlsClientConfigDefaultsToPlainMode() {
    TlsClientConfig config = TlsClientConfig.fromEnvironment(Map.of());

    assertEquals(TlsClientConfig.disabled(), config);
  }

  @Test
  void tlsClientConfigRejectsInvalidBooleanAndPlainTrustStore() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TlsClientConfig.fromEnvironment(Map.of(TlsClientConfig.ENV_TLS_ENABLED, "tru")));
    assertThrows(
        IllegalArgumentException.class,
        () -> TlsClientConfig.fromEnvironment(Map.of(TlsClientConfig.ENV_TRUSTSTORE, "chat.p12")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TlsClientConfig.fromEnvironment(
                Map.of(
                    TlsClientConfig.ENV_TLS_ENABLED,
                    "true",
                    TlsClientConfig.ENV_TRUSTSTORE_PASSWORD,
                    "orphaned")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TlsServerConfig(false, Path.of("ignored.p12"), "secret", "secret"));
  }

  @Test
  void chatSocketsOpenPlainServerAndClientSockets() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket serverSocket = ChatSockets.openServerSocket(ChatServerConfig.ofPort(0))) {
      Future<Socket> accepted = executor.submit(serverSocket::accept);
      try (Socket client =
              ChatSockets.openClientSocket(
                  "127.0.0.1", serverSocket.getLocalPort(), TlsClientConfig.disabled());
          Socket peer = accepted.get(2, TimeUnit.SECONDS)) {
        assertTrue(client.isConnected());
        assertTrue(peer.isConnected());
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void accountToolPrintsUsableAccountRow() throws Exception {
    String row = AccountTool.formatAccountRow("alice", UserRole.ADMIN, "secret".toCharArray());
    String[] columns = row.split(",");
    assertEquals("alice", columns[0]);
    assertEquals("ADMIN", columns[1]);
    assertNotNull(columns[2]);
    assertEquals(AccountStore.hashToken(columns[2], "secret"), columns[3]);
    assertTrue(columns[3].startsWith("pbkdf2-sha256$210000$"));
  }

  @Test
  void accountToolRejectsTokenInProcessArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AccountTool.main(new String[] {"alice", "ADMIN", "secret"}));
  }

  @Test
  void chatConnectionRejectsOversizedFrames() throws Exception {
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peer = serverSocket.accept();
        ChatConnection connection = new ChatConnection(peer)) {
      client
          .getOutputStream()
          .write(
              ("x".repeat(ChatConnection.MAX_FRAME_LENGTH + 1) + "\n")
                  .getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();

      assertThrows(java.io.IOException.class, connection::receive);
    }
  }

  @Test
  void chatConnectionUsesTotalFrameDeadline() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peer = serverSocket.accept();
        ChatConnection connection = new ChatConnection(peer)) {
      executor.submit(
          () -> {
            try {
              for (int index = 0; index < 5; index++) {
                client.getOutputStream().write('x');
                client.getOutputStream().flush();
                Thread.sleep(200);
              }
            } catch (java.io.IOException ignored) {
              // The reader deliberately closes the socket after reaching the total deadline.
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          });

      assertThrows(
          java.net.SocketTimeoutException.class, () -> connection.receive(Duration.ofMillis(500)));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void chatConnectionBoundsUtf8BytesRatherThanJavaCharacters() throws Exception {
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peer = serverSocket.accept();
        ChatConnection connection = new ChatConnection(peer)) {
      client
          .getOutputStream()
          .write(
              ("я".repeat(ChatConnection.MAX_FRAME_LENGTH / 2 + 1) + "\n")
                  .getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();

      java.io.IOException error = assertThrows(java.io.IOException.class, connection::receive);
      assertTrue(error.getMessage().contains("too large"));
    }
  }

  @Test
  void chatConnectionRejectsTimeoutsTooLargeForSocketApis() throws Exception {
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peer = serverSocket.accept();
        ChatConnection connection = new ChatConnection(peer)) {
      assertTrue(client.isConnected());
      assertThrows(
          IllegalArgumentException.class,
          () -> connection.receive(Duration.ofSeconds(Long.MAX_VALUE)));
    }
  }

  @Test
  void chatConnectionRejectsMalformedUtf8InsideOtherwiseValidJson() throws Exception {
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peer = serverSocket.accept();
        ChatConnection connection = new ChatConnection(peer)) {
      client
          .getOutputStream()
          .write("{\"type\":\"TEXT\",\"data\":\"bad".getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().write(new byte[] {(byte) 0xc3, 0x28});
      client
          .getOutputStream()
          .write(
              "\",\"sender\":\"alice\",\"timestamp\":1,\"messageId\":\"id\"}\n"
                  .getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();

      assertThrows(java.nio.charset.MalformedInputException.class, connection::receive);
    }
  }

  @Test
  void concurrentTimedReceivesRestoreTheOriginalSocketTimeout() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch secondReceiveStarted = new CountDownLatch(1);
    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket peer = serverSocket.accept();
        ChatConnection connection = new ChatConnection(peer)) {
      peer.setSoTimeout(777);
      Future<?> first = executor.submit(() -> connection.receive(Duration.ofSeconds(2)));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (peer.getSoTimeout() == 777 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertTrue(peer.getSoTimeout() != 777);
      Future<?> second =
          executor.submit(
              () -> {
                secondReceiveStarted.countDown();
                return connection.receive(Duration.ofSeconds(2));
              });
      assertTrue(secondReceiveStarted.await(1, TimeUnit.SECONDS));
      Thread.sleep(50);
      String frames =
          dev.krotname.networkchat.protocol.ChatProtocol.encode(
                  dev.krotname.networkchat.protocol.ChatMessage.text("one", "alice"))
              + "\n"
              + dev.krotname.networkchat.protocol.ChatProtocol.encode(
                  dev.krotname.networkchat.protocol.ChatMessage.text("two", "alice"))
              + "\n";
      client.getOutputStream().write(frames.getBytes(StandardCharsets.UTF_8));
      client.getOutputStream().flush();

      first.get(2, TimeUnit.SECONDS);
      second.get(2, TimeUnit.SECONDS);
      assertEquals(777, peer.getSoTimeout());
    } finally {
      executor.shutdownNow();
    }
  }
}
