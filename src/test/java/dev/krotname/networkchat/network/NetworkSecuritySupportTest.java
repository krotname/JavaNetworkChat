package dev.krotname.networkchat.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
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
    PrintStream originalOut = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

      AccountTool.main(new String[] {"alice", "ADMIN", "secret"});
    } finally {
      System.setOut(originalOut);
    }

    String[] columns = output.toString(StandardCharsets.UTF_8).trim().split(",");
    assertEquals("alice", columns[0]);
    assertEquals("ADMIN", columns[1]);
    assertNotNull(columns[2]);
    assertEquals(AccountStore.hashToken(columns[2], "secret"), columns[3]);
  }
}
