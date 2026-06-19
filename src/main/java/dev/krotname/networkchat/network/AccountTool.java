package dev.krotname.networkchat.network;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/** Command line helper that prints one account-file row for a username, role, and token. */
public final class AccountTool {
  private static final SecureRandom RANDOM = new SecureRandom();

  private AccountTool() {}

  public static void main(String[] args) {
    if (args.length != 3) {
      System.err.println("Usage: AccountTool <username> <USER|ADMIN> <token>");
      System.exit(2);
    }
    if (!AccountStore.isValidUserName(args[0])) {
      System.err.println("Invalid username. Use 3-64 letters, digits, '_' or '-'.");
      System.exit(2);
    }
    UserRole role = UserRole.valueOf(args[1].toUpperCase(Locale.ROOT));
    String salt = randomSalt();
    System.out.printf("%s,%s,%s,%s%n", args[0], role, salt, AccountStore.hashToken(salt, args[2]));
  }

  private static String randomSalt() {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return HexFormat.of().formatHex(salt);
  }
}
