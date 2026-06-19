package dev.krotname.networkchat.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** File-backed account registry with salted SHA-256 token hashes. */
public final class AccountStore {
  private static final String HASH_ALGORITHM = "SHA-256";
  private static final int MIN_USER_NAME_LENGTH = 3;
  private static final int MAX_USER_NAME_LENGTH = 64;
  private static final String USER_NAME_PATTERN = "[\\p{L}\\p{N}_-]+";

  private final Map<String, AccountRecord> accounts;
  private final boolean enabled;

  private AccountStore(Map<String, AccountRecord> accounts, boolean enabled) {
    this.accounts = Map.copyOf(accounts);
    this.enabled = enabled;
  }

  public static AccountStore disabled() {
    return new AccountStore(Map.of(), false);
  }

  public static AccountStore load(Path accountFile) throws IOException {
    Objects.requireNonNull(accountFile, "accountFile");
    Map<String, AccountRecord> loadedAccounts = new HashMap<>();
    int lineNumber = 0;
    for (String line : Files.readAllLines(accountFile, StandardCharsets.UTF_8)) {
      lineNumber++;
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      AccountRecord account = parseLine(line, lineNumber);
      if (loadedAccounts.putIfAbsent(account.userName(), account) != null) {
        throw new IllegalArgumentException("Duplicate account user name on line " + lineNumber);
      }
    }
    return new AccountStore(loadedAccounts, true);
  }

  public static String hashToken(String salt, String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
      byte[] hash = digest.digest((salt + ":" + token).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(HASH_ALGORITHM + " is not available", ex);
    }
  }

  public boolean enabled() {
    return enabled;
  }

  public Optional<UserRole> authenticate(String userName, String token) {
    if (!enabled()) {
      return Optional.of(UserRole.USER);
    }
    AccountRecord account = accounts.get(userName);
    if (account == null || token == null || token.isBlank()) {
      return Optional.empty();
    }
    String actualHash = hashToken(account.salt(), token);
    boolean matches =
        MessageDigest.isEqual(
            actualHash.getBytes(StandardCharsets.UTF_8),
            account.tokenHash().getBytes(StandardCharsets.UTF_8));
    return matches ? Optional.of(account.role()) : Optional.empty();
  }

  public int size() {
    return accounts.size();
  }

  public static boolean isValidUserName(String userName) {
    return userName != null
        && userName.length() >= MIN_USER_NAME_LENGTH
        && userName.length() <= MAX_USER_NAME_LENGTH
        && userName.matches(USER_NAME_PATTERN);
  }

  private static AccountRecord parseLine(String line, int lineNumber) {
    String[] columns = line.split(",", -1);
    if (columns.length != 4) {
      throw new IllegalArgumentException("Invalid account file line " + lineNumber);
    }
    String userName = columns[0].trim();
    UserRole role = parseRole(columns[1].trim(), lineNumber);
    String salt = columns[2].trim();
    String tokenHash = columns[3].trim().toLowerCase(Locale.ROOT);
    if (!isValidUserName(userName) || salt.isBlank() || tokenHash.isBlank()) {
      throw new IllegalArgumentException("Invalid account file line " + lineNumber);
    }
    return new AccountRecord(userName, role, salt, tokenHash);
  }

  private static UserRole parseRole(String value, int lineNumber) {
    try {
      return UserRole.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid account role on line " + lineNumber, ex);
    }
  }

  private record AccountRecord(String userName, UserRole role, String salt, String tokenHash) {}
}
