package dev.krotname.networkchat.network;

/**
 * Per-connection token-bucket limits.
 *
 * <p>Limits are applied per client connection and answered with an {@code ERROR} frame instead of a
 * disconnect, so a misbehaving client is slowed down while legitimate bursts still pass.
 */
public record RateLimitConfig(
    int frameBurst, double framesPerSecond, int roomCreationBurst, double roomCreationsPerSecond) {
  public static final int DEFAULT_FRAME_BURST = 60;
  public static final double DEFAULT_FRAMES_PER_SECOND = 20;
  public static final int DEFAULT_ROOM_CREATION_BURST = 10;
  public static final double DEFAULT_ROOM_CREATIONS_PER_SECOND = 0.5;
  public static final int MAX_BURST = 100_000;

  public RateLimitConfig {
    validateBurst(frameBurst, "frameBurst");
    validateBurst(roomCreationBurst, "roomCreationBurst");
    validateRate(framesPerSecond, "framesPerSecond");
    validateRate(roomCreationsPerSecond, "roomCreationsPerSecond");
  }

  public static RateLimitConfig defaultLimits() {
    return new RateLimitConfig(
        DEFAULT_FRAME_BURST,
        DEFAULT_FRAMES_PER_SECOND,
        DEFAULT_ROOM_CREATION_BURST,
        DEFAULT_ROOM_CREATIONS_PER_SECOND);
  }

  TokenBucket newFrameBucket() {
    return new TokenBucket(frameBurst, framesPerSecond);
  }

  TokenBucket newRoomCreationBucket() {
    return new TokenBucket(roomCreationBurst, roomCreationsPerSecond);
  }

  private static void validateBurst(int burst, String fieldName) {
    if (burst < 1 || burst > MAX_BURST) {
      throw new IllegalArgumentException(fieldName + " must be in range 1.." + MAX_BURST);
    }
  }

  private static void validateRate(double rate, String fieldName) {
    if (!Double.isFinite(rate) || rate <= 0) {
      throw new IllegalArgumentException(fieldName + " must be a positive finite rate");
    }
  }
}
