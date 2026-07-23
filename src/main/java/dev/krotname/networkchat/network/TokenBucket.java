package dev.krotname.networkchat.network;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Minimal token bucket used to throttle a single client connection.
 *
 * <p>The bucket starts full so short legitimate bursts are never delayed, and it refills lazily
 * from a monotonic clock so no background timer is required.
 */
final class TokenBucket {
  private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  private final double capacity;
  private final double tokensPerNano;
  private final LongSupplier nanoClock;
  private double tokens;
  private long lastRefillNanos;

  TokenBucket(int capacity, double permitsPerSecond) {
    this(capacity, permitsPerSecond, System::nanoTime);
  }

  TokenBucket(int capacity, double permitsPerSecond, LongSupplier nanoClock) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Token bucket capacity must be positive");
    }
    if (!Double.isFinite(permitsPerSecond) || permitsPerSecond <= 0) {
      throw new IllegalArgumentException("Token bucket rate must be positive");
    }
    this.capacity = capacity;
    this.tokensPerNano = permitsPerSecond / NANOS_PER_SECOND;
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.tokens = capacity;
    this.lastRefillNanos = nanoClock.getAsLong();
  }

  /** Consumes one permit and reports whether the caller may proceed. */
  synchronized boolean tryAcquire() {
    long now = nanoClock.getAsLong();
    long elapsedNanos = now - lastRefillNanos;
    if (elapsedNanos > 0) {
      tokens = Math.min(capacity, tokens + elapsedNanos * tokensPerNano);
      lastRefillNanos = now;
    }
    if (tokens < 1) {
      return false;
    }
    tokens -= 1;
    return true;
  }
}
