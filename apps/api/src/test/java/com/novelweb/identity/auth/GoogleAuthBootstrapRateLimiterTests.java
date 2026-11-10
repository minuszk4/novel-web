package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GoogleAuthBootstrapRateLimiterTests {

  private static final Duration REFILL_INTERVAL = Duration.ofSeconds(6);
  private static final Instant REFILLED_AT = Instant.parse("2026-11-11T00:00:00Z");

  @Test
  void remainsBelowOneTokenOneNanosecondBeforeTheRefillBoundary() {
    BigDecimal available =
        GoogleAuthBootstrapRateLimiter.refill(
            BigDecimal.ZERO, REFILLED_AT, REFILLED_AT.plusNanos(5_999_999_999L), REFILL_INTERVAL);

    assertEquals(new BigDecimal("0.999999"), available);
  }

  @Test
  void reachesOneTokenAtTheExactRefillBoundary() {
    BigDecimal available =
        GoogleAuthBootstrapRateLimiter.refill(
            BigDecimal.ZERO, REFILLED_AT, REFILLED_AT.plus(REFILL_INTERVAL), REFILL_INTERVAL);

    assertEquals(new BigDecimal("1.000000"), available);
  }

  @Test
  void capsRefilledTokensAtTheConfiguredBucketCapacity() {
    BigDecimal available =
        GoogleAuthBootstrapRateLimiter.refill(
            new BigDecimal("9.500000"),
            REFILLED_AT,
            REFILLED_AT.plus(REFILL_INTERVAL),
            REFILL_INTERVAL);

    assertEquals(new BigDecimal("10"), available);
  }

  @Test
  void doesNotCreateTokensWhenTheDatabaseTimestampMovesBackwards() {
    BigDecimal available =
        GoogleAuthBootstrapRateLimiter.refill(
            new BigDecimal("0.500000"), REFILLED_AT, REFILLED_AT.minusNanos(1), REFILL_INTERVAL);

    assertEquals(new BigDecimal("0.500000"), available);
  }
}
