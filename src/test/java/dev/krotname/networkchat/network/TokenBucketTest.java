package dev.krotname.networkchat.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

  @Test
  void throttlesAfterTheBurstAndRefillsOverTime() {
    AtomicLong clock = new AtomicLong();
    TokenBucket bucket = new TokenBucket(2, 1, clock::get);

    assertTrue(bucket.tryAcquire());
    assertTrue(bucket.tryAcquire());
    assertFalse(bucket.tryAcquire());

    clock.addAndGet(TimeUnit.SECONDS.toNanos(1));

    assertTrue(bucket.tryAcquire());
    assertFalse(bucket.tryAcquire());
  }

  @Test
  void refillNeverExceedsTheConfiguredBurst() {
    AtomicLong clock = new AtomicLong();
    TokenBucket bucket = new TokenBucket(2, 1, clock::get);

    clock.addAndGet(TimeUnit.HOURS.toNanos(1));

    assertTrue(bucket.tryAcquire());
    assertTrue(bucket.tryAcquire());
    assertFalse(bucket.tryAcquire());
  }

  @Test
  void rejectsInvalidBucketSettings() {
    assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0));
    assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, Double.NaN));
    assertThrows(NullPointerException.class, () -> new TokenBucket(1, 1, null));
  }
}
