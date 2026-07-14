package dev.krotname.networkchat.network;

import java.util.Map;
import java.util.StringJoiner;

/** Minimal JSON-line formatter for operator-readable runtime events. */
final class StructuredLog {
  private StructuredLog() {}

  static String event(String name, Map<String, ?> fields) {
    StringJoiner joiner = new StringJoiner(",", "{", "}");
    joiner.add("\"event\":" + json(name));
    for (Map.Entry<String, ?> entry : fields.entrySet()) {
      joiner.add(json(entry.getKey()) + ":" + jsonValue(entry.getValue()));
    }
    return joiner.toString();
  }

  private static String jsonValue(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    return json(value.toString());
  }

  private static String json(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (Character.isISOControl(character)) {
            escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }
}
