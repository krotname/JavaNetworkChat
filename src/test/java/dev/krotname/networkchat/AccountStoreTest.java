package dev.krotname.networkchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.krotname.networkchat.network.AccountStore;
import dev.krotname.networkchat.network.UserRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {
  private static final String SALT = "0123456789abcdef0123456789abcdef";
  @TempDir private Path tempDir;

  @Test
  void loadsSaltedTokenHashesAndRoles() throws Exception {
    Path accountFile = tempDir.resolve("accounts.csv");
    Files.writeString(
        accountFile,
        "# comment\n\nalice,USER," + SALT + "," + AccountStore.hashToken(SALT, "secret") + "\n",
        StandardCharsets.UTF_8);

    AccountStore store = AccountStore.load(accountFile);

    assertEquals(UserRole.USER, store.authenticate("alice", "secret").orElseThrow());
    assertFalse(store.authenticate("alice", "wrong").isPresent());
  }

  @Test
  void authenticatesCanonicalLegacySha256RowsDuringMigration() throws Exception {
    Path accountFile = tempDir.resolve("legacy-accounts.csv");
    Files.writeString(
        accountFile,
        "alice,USER,"
            + SALT
            + ",8917ae349b3abd701ffcd51e40fd8aacca08f7ed11cc2688a79f72ab380e7460\n",
        StandardCharsets.UTF_8);

    AccountStore store = AccountStore.load(accountFile);

    assertEquals(UserRole.USER, store.authenticate("alice", "secret").orElseThrow());
    assertFalse(store.authenticate("alice", "wrong").isPresent());
  }

  @Test
  void rejectsMalformedAccountFiles() throws Exception {
    Path accountFile = tempDir.resolve("bad-accounts.csv");
    Files.writeString(accountFile, "alice,OWNER,salt,hash\n", StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(accountFile));
  }

  @Test
  void disabledStoreAcceptsAnyUserAsRegularUser() {
    AccountStore store = AccountStore.disabled();

    assertEquals(UserRole.USER, store.authenticate("alice", null).orElseThrow());
  }

  @Test
  void rejectsRowsWithMissingColumnsOrValues() throws Exception {
    Path missingColumns = tempDir.resolve("missing-columns.csv");
    Path missingValues = tempDir.resolve("missing-values.csv");
    Files.writeString(missingColumns, "alice,USER,salt\n", StandardCharsets.UTF_8);
    Files.writeString(missingValues, "alice,USER,,hash\n", StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(missingColumns));
    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(missingValues));
  }

  @Test
  void rejectsDuplicateOrInvalidUserNames() throws Exception {
    Path duplicateUsers = tempDir.resolve("duplicate-users.csv");
    Path invalidUser = tempDir.resolve("invalid-user.csv");
    Files.writeString(
        duplicateUsers,
        "alice,USER,"
            + SALT
            + ","
            + AccountStore.hashToken(SALT, "secret")
            + "\n"
            + "alice,ADMIN,fedcba9876543210fedcba9876543210,"
            + AccountStore.hashToken("fedcba9876543210fedcba9876543210", "secret")
            + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        invalidUser,
        "al,USER," + SALT + "," + AccountStore.hashToken(SALT, "secret") + "\n",
        StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(duplicateUsers));
    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(invalidUser));
  }

  @Test
  void rejectsWeakOrMalformedHashMaterial() throws Exception {
    Path weakSalt = tempDir.resolve("weak-salt.csv");
    Path malformedHash = tempDir.resolve("malformed-hash.csv");
    Files.writeString(weakSalt, "alice,USER,salt," + "0".repeat(64), StandardCharsets.UTF_8);
    Files.writeString(
        malformedHash, "alice,USER," + SALT + "," + "z".repeat(64), StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(weakSalt));
    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(malformedHash));
  }

  @Test
  void rejectsOversizedAccountFile() throws Exception {
    Path oversized = tempDir.resolve("oversized.csv");
    Files.writeString(oversized, "x".repeat(1024 * 1024 + 1), StandardCharsets.UTF_8);

    assertThrows(java.io.IOException.class, () -> AccountStore.load(oversized));
  }

  @Test
  void rejectsMalformedUtf8AccountFiles() throws Exception {
    Path malformed = tempDir.resolve("malformed-utf8.csv");
    Files.write(malformed, new byte[] {'#', ' ', (byte) 0xc3, 0x28, '\n'});

    assertThrows(java.io.IOException.class, () -> AccountStore.load(malformed));
  }
}
