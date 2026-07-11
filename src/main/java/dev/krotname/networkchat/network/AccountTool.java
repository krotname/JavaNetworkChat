package dev.krotname.networkchat.network;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Command line helper that reads a token safely and prints one account-file row. */
public final class AccountTool {
  private static final SecureRandom RANDOM = new SecureRandom();

  private AccountTool() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: AccountTool <username> <USER|ADMIN>");
    }
    if (!AccountStore.isValidUserName(args[0])) {
      throw new IllegalArgumentException("Invalid username. Use 3-64 letters, digits, '_' or '-'.");
    }
    UserRole role;
    try {
      role = UserRole.valueOf(args[1].toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Role must be USER or ADMIN", ex);
    }
    char[] token = readToken();
    try {
      System.out.println(formatAccountRow(args[0], role, token));
    } finally {
      Arrays.fill(token, '\0');
    }
  }

  static String formatAccountRow(String userName, UserRole role, char[] token) {
    if (!AccountStore.isValidUserName(userName)) {
      throw new IllegalArgumentException("Invalid username");
    }
    if (role == null) {
      throw new IllegalArgumentException("Role is required");
    }
    if (token == null) {
      throw new IllegalArgumentException("Token is required");
    }
    String salt = randomSalt();
    return String.format(
        Locale.ROOT, "%s,%s,%s,%s", userName, role, salt, AccountStore.hashToken(salt, token));
  }

  private static char[] readToken() throws IOException {
    Console console = System.console();
    if (console != null) {
      char[] token = console.readPassword("Token: ");
      if (token == null) {
        throw new IOException("No token was provided");
      }
      return token;
    }
    System.err.print("Token: ");
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String token = reader.readLine();
    if (token == null) {
      throw new IOException("No token was provided");
    }
    return token.toCharArray();
  }

  private static String randomSalt() {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return HexFormat.of().formatHex(salt);
  }
}
