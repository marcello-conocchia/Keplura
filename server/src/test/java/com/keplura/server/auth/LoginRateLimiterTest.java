package com.keplura.server.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2b: unit test for the in-memory fixed-window {@link LoginRateLimiter}.
 * Uses the package-private {@link Clock} seam instead of sleeping 15 minutes.
 */
class LoginRateLimiterTest {

    /** Mutable clock so a test can "advance time" deterministically. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-04T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void blocksAfterMaxFailedAttemptsForSameKey() {
        LoginRateLimiter limiter = new LoginRateLimiter(new TestClock());
        String ip = "10.0.0.1";
        String email = "t@example.com";

        assertThat(limiter.isBlocked(ip, email)).isFalse();
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS - 1; i++) {
            limiter.recordFailure(ip, email);
            assertThat(limiter.isBlocked(ip, email)).isFalse();
        }
        limiter.recordFailure(ip, email);
        assertThat(limiter.isBlocked(ip, email)).isTrue();
    }

    @Test
    void differentEmailOrDifferentIpHaveSeparateWindows() {
        LoginRateLimiter limiter = new LoginRateLimiter(new TestClock());
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("10.0.0.1", "a@example.com");
        }
        assertThat(limiter.isBlocked("10.0.0.1", "a@example.com")).isTrue();
        assertThat(limiter.isBlocked("10.0.0.1", "b@example.com")).isFalse();
        assertThat(limiter.isBlocked("10.0.0.2", "a@example.com")).isFalse();
    }

    @Test
    void successClearsTheWindow() {
        LoginRateLimiter limiter = new LoginRateLimiter(new TestClock());
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("ip", "e");
        }
        assertThat(limiter.isBlocked("ip", "e")).isTrue();

        limiter.recordSuccess("ip", "e");
        assertThat(limiter.isBlocked("ip", "e")).isFalse();
    }

    @Test
    void expiredWindowIsNotBlockedAnymoreAndStartsAFreshWindow() {
        TestClock clock = new TestClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure("ip", "e");
        }
        assertThat(limiter.isBlocked("ip", "e")).isTrue();

        clock.advance(Duration.ofMinutes(LoginRateLimiter.WINDOW_MINUTES + 1));
        assertThat(limiter.isBlocked("ip", "e")).isFalse();

        // fresh window: one more failure after expiry counts from 1, not 11
        limiter.recordFailure("ip", "e");
        assertThat(limiter.isBlocked("ip", "e")).isFalse();
    }

    @Test
    void manyIpsAgainstOneEmailHitTheSharedPerEmailCeiling() {
        // Email-spray defence (M2c): rotating IPs keeps every (ip, email) window
        // fresh, but the single per-email counter still caps total attempts.
        LoginRateLimiter limiter = new LoginRateLimiter(new TestClock());
        String email = "target@example.com";

        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_EMAIL - 1; i++) {
            limiter.recordFailure("10.9.9." + i, email);
            assertThat(limiter.isBlocked("10.9.9." + i, email))
                    .as("attempt %s from a fresh IP must still pass", i)
                    .isFalse();
        }
        limiter.recordFailure("10.9.9.99", email);
        // Ceiling reached: now even a brand-new IP is blocked for this email.
        assertThat(limiter.isBlocked("192.168.0.1", email)).isTrue();
        // ...but only for THIS email — other accounts are unaffected.
        assertThat(limiter.isBlocked("192.168.0.1", "other@example.com")).isFalse();
    }

    @Test
    void retryAfterReflectsRemainingWindowAndRoundsUpToAtLeastOneSecond() {
        TestClock clock = new TestClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        String ip = "10.0.0.7";
        String email = "ra@example.com";
        for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS; i++) {
            limiter.recordFailure(ip, email);
        }
        assertThat(limiter.isBlocked(ip, email)).isTrue();
        // Fresh full window: capped at the window size itself.
        assertThat(limiter.retryAfterSeconds(ip, email))
                .isEqualTo(Duration.ofMinutes(LoginRateLimiter.WINDOW_MINUTES).toSeconds());

        clock.advance(Duration.ofMinutes(14));
        // ~60s left (+59s round-up = <=119); the important contract is a small
        // positive number that shrinks as the window drains.
        long remaining = limiter.retryAfterSeconds(ip, email);
        assertThat(remaining).isBetween(1L, 120L);

        clock.advance(Duration.ofMinutes(LoginRateLimiter.WINDOW_MINUTES + 1));
        assertThat(limiter.isBlocked(ip, email)).isFalse();
    }
}
