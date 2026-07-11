package dev.krotname.networkchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.krotname.networkchat.client.gui.ConnectionSettings;
import dev.krotname.networkchat.client.gui.ConnectionSettingsStore;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class ConnectionSettingsTest {

  @Test
  void trimsConnectionFieldsButPreservesTheExactAccountToken() {
    ConnectionSettings settings =
        ConnectionSettings.fromInput(" localhost ", " 1500 ", " alice ", " token ");

    assertEquals("localhost", settings.serverAddress());
    assertEquals(1500, settings.serverPort());
    assertEquals("alice", settings.userName());
    assertEquals(" token ", settings.accountToken());
  }

  @Test
  void rejectsInvalidPortInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ConnectionSettings.fromInput("localhost", "abc", "alice", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> ConnectionSettings.fromInput("localhost", "70000", "alice", ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> ConnectionSettings.fromInput("localhost", "0", "alice", ""));
  }

  @Test
  void storeRoundTripsLastSettings() throws Exception {
    Preferences preferences =
        Preferences.userRoot().node("dev.krotname.networkchat.tests." + UUID.randomUUID());
    try {
      ConnectionSettingsStore store = new ConnectionSettingsStore(preferences);
      ConnectionSettings settings = new ConnectionSettings("chat.local", 1600, "alice", "secret");
      ConnectionSettings persistedSettings =
          new ConnectionSettings("chat.local", 1600, "alice", "");

      store.save(settings);

      assertEquals(persistedSettings, store.load());
    } finally {
      preferences.removeNode();
    }
  }
}
