package dev.krotname.networkchat.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Serializes and deserializes chat messages to a single-line JSON format. */
public final class ChatProtocol {
  public static final int MAX_FRAME_LENGTH = 8 * 1024;
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_FRAME_LENGTH)
                          .maxTokenCount(64)
                          .maxNestingDepth(8)
                          .maxNumberLength(32)
                          .maxStringLength(4_096)
                          .maxNameLength(64)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .findAndRegisterModules();

  private ChatProtocol() {}

  public static String encode(ChatMessage message) throws IOException {
    try {
      String encoded = MAPPER.writeValueAsString(Objects.requireNonNull(message, "message"));
      if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_LENGTH) {
        throw new IOException("Encoded protocol frame is too large");
      }
      return encoded;
    } catch (JsonProcessingException ex) {
      throw new IOException("Failed to encode protocol message", ex);
    }
  }

  /**
   * Decodes one JSON line into a validated protocol message. Unknown or malformed frames are
   * rejected with IOException to keep protocol failures explicit.
   */
  public static ChatMessage decode(String line) throws IOException {
    if (line == null
        || line.isBlank()
        || line.length() > MAX_FRAME_LENGTH
        || line.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_LENGTH) {
      throw new IOException("Protocol frame is empty or too large");
    }
    try {
      return MAPPER.readValue(line, ChatMessage.class);
    } catch (JsonProcessingException ex) {
      throw new IOException("Failed to decode protocol message", ex);
    }
  }
}
