package com.universe.wiki.infrastructure.throttle;

import com.universe.wiki.application.ports.WikiContributionSubmissionThrottleDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CaffeineWikiContributionSubmissionThrottleAdapter Unit & Concurrency Tests")
class CaffeineWikiContributionSubmissionThrottleAdapterTest {

    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");

    private CaffeineWikiContributionSubmissionThrottleAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CaffeineWikiContributionSubmissionThrottleAdapter();
    }

    @Test
    @DisplayName("Validation: null arguments throw NullPointerException")
    void nullArgumentsThrow() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> adapter.tryAcquire(null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");

        assertThatThrownBy(() -> adapter.tryAcquire(userId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian hiện tại không được để trống.");
    }

    @Test
    @DisplayName("1. Same user: first 10 attempts inside 5-minute window are allowed")
    void first10AttemptsAllowed() {
        UUID userId = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            Instant attemptTime = T0.plusSeconds(i * 10);
            WikiContributionSubmissionThrottleDecision decision = adapter.tryAcquire(userId, attemptTime);
            assertThat(decision.allowed())
                    .as("Attempt %d should be allowed", i + 1)
                    .isTrue();
            assertThat(decision.retryAfterSeconds()).isEqualTo(0L);
        }
    }

    @Test
    @DisplayName("2 & 3. 11th attempt is denied with retryAfterSeconds > 0")
    void eleventhAttemptDeniedWithRetryAfter() {
        UUID userId = UUID.randomUUID();

        // 10 attempts at T0
        for (int i = 0; i < 10; i++) {
            WikiContributionSubmissionThrottleDecision decision = adapter.tryAcquire(userId, T0);
            assertThat(decision.allowed()).isTrue();
        }

        // 11th attempt 30 seconds later
        Instant attempt11Time = T0.plusSeconds(30);
        WikiContributionSubmissionThrottleDecision decision11 = adapter.tryAcquire(userId, attempt11Time);

        assertThat(decision11.allowed()).isFalse();
        // Window is 300s. Oldest attempt was at T0. 300s - 30s = 270s remaining.
        assertThat(decision11.retryAfterSeconds()).isEqualTo(270L);
    }

    @Test
    @DisplayName("4. Different users have completely independent limits")
    void differentUsersHaveIndependentLimits() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // Exhaust User A limit
        for (int i = 0; i < 10; i++) {
            assertThat(adapter.tryAcquire(userA, T0).allowed()).isTrue();
        }
        assertThat(adapter.tryAcquire(userA, T0).allowed()).isFalse();

        // User B is unaffected
        for (int i = 0; i < 10; i++) {
            WikiContributionSubmissionThrottleDecision decisionB = adapter.tryAcquire(userB, T0);
            assertThat(decisionB.allowed())
                    .as("User B attempt %d should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("5. After window expiry (5 minutes / 300s), submissions are allowed again")
    void allowedAgainAfterWindowExpiry() {
        UUID userId = UUID.randomUUID();

        // 10 attempts at T0
        for (int i = 0; i < 10; i++) {
            assertThat(adapter.tryAcquire(userId, T0).allowed()).isTrue();
        }

        // Immediately denied
        assertThat(adapter.tryAcquire(userId, T0.plusSeconds(10)).allowed()).isFalse();

        // Exactly 300s + 1s later (outside 5m window)
        Instant afterExpiry = T0.plusSeconds(301);
        WikiContributionSubmissionThrottleDecision decisionAfterExpiry = adapter.tryAcquire(userId, afterExpiry);

        assertThat(decisionAfterExpiry.allowed()).isTrue();
        assertThat(decisionAfterExpiry.retryAfterSeconds()).isEqualTo(0L);
    }

    @Test
    @DisplayName("6. Concurrency: 30 simultaneous acquisitions on one user strictly allow exactly 10 and deny 20")
    void concurrentAcquisitionsStrictlyEnforceLimit() throws Exception {
        UUID userId = UUID.randomUUID();
        int totalThreads = 30;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    WikiContributionSubmissionThrottleDecision decision = adapter.tryAcquire(userId, T0);
                    if (decision.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                        assertThat(decision.retryAfterSeconds()).isGreaterThan(0L);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(allowedCount.get()).isEqualTo(10);
        assertThat(deniedCount.get()).isEqualTo(20);
    }
}
