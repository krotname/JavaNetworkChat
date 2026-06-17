package dev.krotname.networkchat.network;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Client-side TLS settings resolved from environment variables or tests. */
public record TlsClientConfig(boolean enabled, Path trustStoreFile, String trustStorePassword) {
  public static final String ENV_TLS_ENABLED = "NETWORK_CHAT_TLS";
  public static final String ENV_TRUSTSTORE = "NETWORK_CHAT_TRUSTSTORE";
  public static final String ENV_TRUSTSTORE_PASSWORD = "NETWORK_CHAT_TRUSTSTORE_PASSWORD";
  public static final String ENV_TRUST_ALL = "NETWORK_CHAT_TLS_TRUST_ALL";

  public static TlsClientConfig disabled() {
    return new TlsClientConfig(false, null, "");
  }

  public static TlsClientConfig fromEnvironment() {
    return fromEnvironment(System.getenv());
  }

  public static TlsClientConfig fromEnvironment(Map<String, String> environment) {
    boolean enabled = booleanEnvironment(environment, ENV_TLS_ENABLED);
    if (booleanEnvironment(environment, ENV_TRUST_ALL)) {
      throw new IllegalArgumentException(
          ENV_TRUST_ALL + " is not supported; configure " + ENV_TRUSTSTORE + " instead.");
    }
    String trustStore = environment.get(ENV_TRUSTSTORE);
    Path trustStoreFile = trustStore == null || trustStore.isBlank() ? null : Path.of(trustStore);
    return new TlsClientConfig(
        enabled, trustStoreFile, environment.getOrDefault(ENV_TRUSTSTORE_PASSWORD, ""));
  }

  private static boolean booleanEnvironment(Map<String, String> environment, String name) {
    String value = environment.get(name);
    return value != null && ("true".equals(value.toLowerCase(Locale.ROOT)) || "1".equals(value));
  }
}
