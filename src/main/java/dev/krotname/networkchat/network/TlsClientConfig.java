package dev.krotname.networkchat.network;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Client-side TLS settings resolved from environment variables or tests. */
public record TlsClientConfig(boolean enabled, Path trustStoreFile, String trustStorePassword) {
  public static final String ENV_TLS_ENABLED = "NETWORK_CHAT_TLS";
  public static final String ENV_TRUSTSTORE = "NETWORK_CHAT_TRUSTSTORE";
  public static final String ENV_TRUSTSTORE_PASSWORD = "NETWORK_CHAT_TRUSTSTORE_PASSWORD";
  public static final String ENV_TRUST_ALL = "NETWORK_CHAT_TLS_TRUST_ALL";

  public TlsClientConfig {
    trustStorePassword = Objects.requireNonNullElse(trustStorePassword, "");
    if (trustStoreFile == null && !trustStorePassword.isBlank()) {
      throw new IllegalArgumentException("A truststore password requires a truststore file");
    }
    if (!enabled && trustStoreFile != null) {
      throw new IllegalArgumentException("A truststore requires TLS to be enabled");
    }
  }

  public static TlsClientConfig disabled() {
    return new TlsClientConfig(false, null, "");
  }

  public static TlsClientConfig fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static TlsClientConfig fromEnvironment(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment");
    boolean enabled = booleanEnvironment(environment, ENV_TLS_ENABLED, false);
    if (booleanEnvironment(environment, ENV_TRUST_ALL, false)) {
      throw new IllegalArgumentException(
          ENV_TRUST_ALL + " is not supported; configure " + ENV_TRUSTSTORE + " instead.");
    }
    String trustStore = environment.get(ENV_TRUSTSTORE);
    Path trustStoreFile =
        trustStore == null || trustStore.isBlank() ? null : Path.of(trustStore.trim());
    return new TlsClientConfig(
        enabled, trustStoreFile, environment.getOrDefault(ENV_TRUSTSTORE_PASSWORD, ""));
  }

  private static boolean booleanEnvironment(
      Map<String, String> environment, String name, boolean defaultValue) {
    String value = environment.get(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "true", "1" -> true;
      case "false", "0" -> false;
      default -> throw new IllegalArgumentException(name + " must be true, false, 1, or 0");
    };
  }
}
