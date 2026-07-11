package dev.krotname.networkchat.network;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;
import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/** Socket factory helpers for plain TCP and optional JSSE TLS mode. */
public final class ChatSockets {
  private static final String TLS_PROTOCOL = "TLS";
  private static final int CLIENT_CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int CLIENT_READ_TIMEOUT_MILLIS = 300_000;
  private static final long MAX_KEY_STORE_BYTES = 16L * 1024L * 1024L;

  private ChatSockets() {}

  public static ServerSocket openServerSocket(ChatServerConfig config) throws IOException {
    ServerSocket socket = null;
    try {
      ServerSocketFactory factory =
          config.tls().enabled()
              ? serverSslContext(config.tls()).getServerSocketFactory()
              : ServerSocketFactory.getDefault();
      socket = factory.createServerSocket();
      socket.bind(new InetSocketAddress(config.bindAddress(), config.port()));
      return socket;
    } catch (GeneralSecurityException ex) {
      closeQuietly(socket);
      throw new IOException("Unable to initialize TLS server socket", ex);
    } catch (IOException | RuntimeException ex) {
      closeQuietly(socket);
      throw ex;
    }
  }

  public static Socket openClientSocket(String host, int port, TlsClientConfig tlsConfig)
      throws IOException {
    Objects.requireNonNull(tlsConfig, "tlsConfig");
    Socket socket = null;
    try {
      SocketFactory factory =
          tlsConfig.enabled()
              ? clientSslContext(tlsConfig).getSocketFactory()
              : SocketFactory.getDefault();
      socket = factory.createSocket();
      socket.connect(new InetSocketAddress(host, port), CLIENT_CONNECT_TIMEOUT_MILLIS);
      if (socket instanceof SSLSocket sslSocket) {
        sslSocket.setSoTimeout(CLIENT_CONNECT_TIMEOUT_MILLIS);
        SSLParameters parameters = sslSocket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(parameters);
        sslSocket.startHandshake();
      }
      socket.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
      return socket;
    } catch (GeneralSecurityException ex) {
      closeQuietly(socket);
      throw new IOException("Unable to initialize TLS client socket", ex);
    } catch (IOException | RuntimeException ex) {
      closeQuietly(socket);
      throw ex;
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // Preserve the original connection/bind failure.
    }
  }

  private static SSLContext serverSslContext(TlsServerConfig config)
      throws IOException, GeneralSecurityException {
    KeyStore keyStore = loadKeyStore(config.keyStoreFile(), config.keyStorePassword());
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    char[] keyPassword = config.keyPassword().toCharArray();
    try {
      keyManagerFactory.init(keyStore, keyPassword);
    } finally {
      Arrays.fill(keyPassword, '\0');
    }
    SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
    context.init(keyManagerFactory.getKeyManagers(), null, null);
    return context;
  }

  private static SSLContext clientSslContext(TlsClientConfig config)
      throws IOException, GeneralSecurityException {
    SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
    context.init(null, trustManagers(config), null);
    return context;
  }

  private static TrustManager[] trustManagers(TlsClientConfig config)
      throws IOException, GeneralSecurityException {
    if (config.trustStoreFile() == null) {
      return null;
    }
    KeyStore trustStore = loadKeyStore(config.trustStoreFile(), config.trustStorePassword());
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(trustStore);
    return trustManagerFactory.getTrustManagers();
  }

  private static KeyStore loadKeyStore(Path file, String password)
      throws IOException, GeneralSecurityException {
    Path safeFile = file.toAbsolutePath().normalize();
    validateKeyStorePath(safeFile);
    BasicFileAttributes attributes =
        Files.readAttributes(safeFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new IOException("Key store must be a regular file no larger than 16 MiB");
    }
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    char[] passwordChars = password.toCharArray();
    try (FileChannel channel =
            FileChannel.open(safeFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        InputStream input = Channels.newInputStream(channel)) {
      if (channel.size() > MAX_KEY_STORE_BYTES) {
        throw new IOException("Key store must be a regular file no larger than 16 MiB");
      }
      keyStore.load(input, passwordChars);
    } finally {
      Arrays.fill(passwordChars, '\0');
    }
    return keyStore;
  }

  private static void validateKeyStorePath(Path path) throws IOException {
    Path current = path.getRoot();
    for (Path component : path) {
      current = current == null ? component : current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw new IOException("Key store path must not contain symbolic links");
      }
    }
  }
}
