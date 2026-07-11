package dev.krotname.networkchat.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** File-backed account registry with versioned, deliberately slow PBKDF2 token hashes. */
public final class AccountStore {
  private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final String LEGACY_HASH_ALGORITHM = "SHA-256";
  private static final int HASH_ITERATIONS = 210_000;
  private static final int HASH_BITS = 256;
  private static final String PBKDF2_HASH_PREFIX = "pbkdf2-sha256$" + HASH_ITERATIONS + "$";
  private static final int SALT_HEX_LENGTH = 32;
  private static final int HASH_HEX_LENGTH = HASH_BITS / 4;
  private static final String DUMMY_SALT = "00000000000000000000000000000000";
  private static final int MAX_TOKEN_LENGTH = 512;
  private static final int MAX_ACCOUNT_FILE_BYTES = 1024 * 1024;
  private static final int MAX_ACCOUNT_LINE_LENGTH = 512;
  private static final int MAX_ACCOUNTS = 10_000;
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
    Path safeAccountFile = accountFile.toAbsolutePath().normalize();
    validatePath(safeAccountFile);
    BasicFileAttributes attributes =
        Files.readAttributes(safeAccountFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
      throw new IOException("Account file must be a regular file and must not be a symbolic link");
    }
    if (attributes.size() > MAX_ACCOUNT_FILE_BYTES) {
      throw new IOException("Account file is too large");
    }
    Map<String, AccountRecord> loadedAccounts = new HashMap<>();
    int lineNumber = 0;
    try (FileChannel channel =
            FileChannel.open(safeAccountFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        BufferedReader reader =
            new BufferedReader(
                Channels.newReader(
                    channel,
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT),
                    -1))) {
      if (channel.size() > MAX_ACCOUNT_FILE_BYTES) {
        throw new IOException("Account file is too large");
      }
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.length() > MAX_ACCOUNT_LINE_LENGTH) {
          throw new IllegalArgumentException("Account file line is too long: " + lineNumber);
        }
        if (line.isBlank() || line.trim().startsWith("#")) {
          continue;
        }
        if (loadedAccounts.size() >= MAX_ACCOUNTS) {
          throw new IllegalArgumentException("Account file contains too many accounts");
        }
        AccountRecord account = parseLine(line, lineNumber);
        if (loadedAccounts.putIfAbsent(account.userName(), account) != null) {
          throw new IllegalArgumentException("Duplicate account user name on line " + lineNumber);
        }
      }
    }
    return new AccountStore(loadedAccounts, true);
  }

  public static String hashToken(String salt, String token) {
    if (token == null) {
      throw new IllegalArgumentException(
          "Token must contain 1.." + MAX_TOKEN_LENGTH + " characters");
    }
    char[] tokenChars = token.toCharArray();
    try {
      return encodePbkdf2Hash(salt, tokenChars);
    } finally {
      Arrays.fill(tokenChars, '\0');
    }
  }

  static String hashToken(String salt, char[] token) {
    return encodePbkdf2Hash(salt, token);
  }

  private static String encodePbkdf2Hash(String salt, char[] token) {
    return PBKDF2_HASH_PREFIX + derivePbkdf2Hash(salt, token);
  }

  private static String derivePbkdf2Hash(String salt, char[] token) {
    if (!isValidSalt(salt)) {
      throw new IllegalArgumentException("Salt must be a 16-byte lowercase hexadecimal value");
    }
    if (!isValidToken(token)) {
      throw new IllegalArgumentException(
          "Token must contain 1.." + MAX_TOKEN_LENGTH + " characters");
    }
    char[] tokenChars = Arrays.copyOf(token, token.length);
    PBEKeySpec specification =
        new PBEKeySpec(tokenChars, HexFormat.of().parseHex(salt), HASH_ITERATIONS, HASH_BITS);
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
      byte[] hash = factory.generateSecret(specification).getEncoded();
      return HexFormat.of().formatHex(hash);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(PBKDF2_ALGORITHM + " is not available", ex);
    } finally {
      specification.clearPassword();
      Arrays.fill(tokenChars, '\0');
    }
  }

  public boolean enabled() {
    return enabled;
  }

  public Optional<UserRole> authenticate(String userName, String token) {
    if (!enabled()) {
      return Optional.of(UserRole.USER);
    }
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      return Optional.empty();
    }
    AccountRecord account = accounts.get(userName);
    if (account == null) {
      consumeDummyHash(token);
      return Optional.empty();
    }
    String actualHash =
        account.scheme() == HashScheme.PBKDF2_SHA256
            ? derivePbkdf2Hash(account.salt(), token)
            : legacyHashTokenAfterDummyWork(account.salt(), token);
    byte[] actualHashBytes = HexFormat.of().parseHex(actualHash);
    byte[] expectedHash = HexFormat.of().parseHex(account.tokenHash());
    boolean matches = MessageDigest.isEqual(actualHashBytes, expectedHash);
    Arrays.fill(actualHashBytes, (byte) 0);
    Arrays.fill(expectedHash, (byte) 0);
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
    String encodedHash = columns[3].trim().toLowerCase(Locale.ROOT);
    HashScheme scheme;
    String tokenHash;
    if (encodedHash.startsWith(PBKDF2_HASH_PREFIX)) {
      scheme = HashScheme.PBKDF2_SHA256;
      tokenHash = encodedHash.substring(PBKDF2_HASH_PREFIX.length());
    } else {
      scheme = HashScheme.LEGACY_SHA256;
      tokenHash = encodedHash;
    }
    if (!isValidUserName(userName)
        || !isValidSalt(salt)
        || tokenHash.length() != HASH_HEX_LENGTH
        || !isHex(tokenHash)) {
      throw new IllegalArgumentException("Invalid account file line " + lineNumber);
    }
    return new AccountRecord(userName, role, salt, tokenHash, scheme);
  }

  private static void consumeDummyHash(String token) {
    char[] tokenChars = token.toCharArray();
    try {
      derivePbkdf2Hash(DUMMY_SALT, tokenChars);
    } finally {
      Arrays.fill(tokenChars, '\0');
    }
  }

  private static String derivePbkdf2Hash(String salt, String token) {
    char[] tokenChars = token.toCharArray();
    try {
      return derivePbkdf2Hash(salt, tokenChars);
    } finally {
      Arrays.fill(tokenChars, '\0');
    }
  }

  private static String legacyHashToken(String salt, String token) {
    byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
    byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    try {
      MessageDigest digest = MessageDigest.getInstance(LEGACY_HASH_ALGORITHM);
      digest.update(saltBytes);
      digest.update((byte) ':');
      return HexFormat.of().formatHex(digest.digest(tokenBytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(LEGACY_HASH_ALGORITHM + " is not available", ex);
    } finally {
      Arrays.fill(saltBytes, (byte) 0);
      Arrays.fill(tokenBytes, (byte) 0);
    }
  }

  private static String legacyHashTokenAfterDummyWork(String salt, String token) {
    consumeDummyHash(token);
    return legacyHashToken(salt, token);
  }

  private static boolean isValidSalt(String salt) {
    return salt != null
        && salt.length() == SALT_HEX_LENGTH
        && salt.equals(salt.toLowerCase(Locale.ROOT))
        && isHex(salt);
  }

  private static boolean isHex(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if ((character < '0' || character > '9') && (character < 'a' || character > 'f')) {
        return false;
      }
    }
    return true;
  }

  private static boolean isValidToken(char[] token) {
    if (token == null || token.length == 0 || token.length > MAX_TOKEN_LENGTH) {
      return false;
    }
    for (char character : token) {
      if (!Character.isWhitespace(character)) {
        return true;
      }
    }
    return false;
  }

  private static void validatePath(Path path) throws IOException {
    Path current = path.getRoot();
    for (Path component : path) {
      current = current == null ? component : current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw new IOException("Account file path must not contain symbolic links");
      }
    }
  }

  private static UserRole parseRole(String value, int lineNumber) {
    try {
      return UserRole.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid account role on line " + lineNumber, ex);
    }
  }

  private enum HashScheme {
    PBKDF2_SHA256,
    LEGACY_SHA256
  }

  private record AccountRecord(
      String userName, UserRole role, String salt, String tokenHash, HashScheme scheme) {}
}
