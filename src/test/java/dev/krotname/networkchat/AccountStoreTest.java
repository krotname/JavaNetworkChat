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
  @TempDir private Path tempDir;

  @Test
  void loadsSaltedTokenHashesAndRoles() throws Exception {
    Path accountFile = tempDir.resolve("accounts.csv");
    Files.writeString(
        accountFile,
        "# comment\n\nalice,USER,salt," + AccountStore.hashToken("salt", "secret") + "\n",
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
        "alice,USER,salt,"
            + AccountStore.hashToken("salt", "secret")
            + "\n"
            + "alice,ADMIN,other,"
            + AccountStore.hashToken("other", "secret")
            + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        invalidUser,
        "al,USER,salt," + AccountStore.hashToken("salt", "secret") + "\n",
        StandardCharsets.UTF_8);

    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(duplicateUsers));
    assertThrows(IllegalArgumentException.class, () -> AccountStore.load(invalidUser));
  }
}
