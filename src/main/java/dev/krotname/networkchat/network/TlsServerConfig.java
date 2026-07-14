package dev.krotname.networkchat.network;

import java.nio.file.Path;

/** Server-side TLS settings for JSSE sockets. */
public record TlsServerConfig(
    boolean enabled, Path keyStoreFile, String keyStorePassword, String keyPassword) {
  public TlsServerConfig {
    if (enabled) {
      if (keyStoreFile == null) {
        throw new IllegalArgumentException("TLS keystore file is required");
      }
      if (keyStorePassword == null || keyStorePassword.isBlank()) {
        throw new IllegalArgumentException("TLS keystore password is required");
      }
      if (keyPassword == null || keyPassword.isBlank()) {
        keyPassword = keyStorePassword;
      }
    } else {
      if (keyStoreFile != null
          || (keyStorePassword != null && !keyStorePassword.isBlank())
          || (keyPassword != null && !keyPassword.isBlank())) {
        throw new IllegalArgumentException("Disabled TLS must not contain keystore settings");
      }
      keyStorePassword = "";
      keyPassword = "";
    }
  }

  public static TlsServerConfig disabled() {
    return new TlsServerConfig(false, null, "", "");
  }

  public static TlsServerConfig enabled(Path keyStoreFile, String keyStorePassword) {
    return new TlsServerConfig(true, keyStoreFile, keyStorePassword, keyStorePassword);
  }
}
