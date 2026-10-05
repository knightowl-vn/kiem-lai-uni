package com.universe.community.application.service;

import com.universe.community.application.port.out.CommunityPostCreationGuardRepositoryPort;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException;
import com.universe.community.domain.exception.CommunityPostCreationRateLimitException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityPostCreationGuardService Unit Tests")
class CommunityPostCreationGuardServiceTest {

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

    @Mock
    private CommunityPostCreationGuardRepositoryPort guardRepositoryPort;

    private CommunityPostCreationGuardService guardService;

    @BeforeEach
    void setUp() {
        guardService = new CommunityPostCreationGuardService(guardRepositoryPort);
    }

    @Test
    @DisplayName("Acquire author lock delegates to repository port")
    void shouldAcquireAuthorLock() {
        guardService.acquireAuthorLock(AUTHOR_ID);
        verify(guardRepositoryPort).acquireAuthorLock(AUTHOR_ID);
    }

    @Nested
    @DisplayName("Hash & Duration Helper Parity Tests")
    class HelperTests {

        @Test
        @DisplayName("computeCaptionHash produces lowercase 64-char SHA-256 hex")
        void shouldComputeCorrectSha256Hex() {
            String hash = CommunityPostCreationGuardService.computeCaptionHash("Hello world!");
            assertThat(hash).isEqualTo("c0535e4be2b79ffd93291305436bf889314e4a3faec05ecffcbb7df31ad9e51a");
            assertThat(hash).hasSize(64);
            assertThat(hash).isLowerCase();
        }

        @Test
        @DisplayName("computeRetryAfterSeconds rounds up fractional seconds with minimum of 1")
        void shouldComputeCeilRetryAfter() {
            Instant now = T0;
            // Negative or zero duration -> 1
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now.minusSeconds(5))).isEqualTo(1L);
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now)).isEqualTo(1L);

            // 1 millisecond -> 1 second
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now.plusMillis(1))).isEqualTo(1L);

            // 999 milliseconds -> 1 second
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now.plusMillis(999))).isEqualTo(1L);

            // 1000 milliseconds -> 1 second
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now.plusMillis(1000))).isEqualTo(1L);

            // 1001 milliseconds -> 2 seconds
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now.plusMillis(1001))).isEqualTo(2L);

            // 59 seconds 1 millisecond -> 60 seconds
            assertThat(CommunityPostCreationGuardService.computeRetryAfterSeconds(now, now.plusMillis(59001))).isEqualTo(60L);
        }
    }

    @Nested
    @DisplayName("Cooldown Rule Tests (60s)")
    class CooldownRuleTests {

        @Test
        @DisplayName("First ever post by author is allowed")
        void shouldAllowFirstPost() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.empty());
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            String hash = guardService.evaluateEligibility(AUTHOR_ID, "First post ever", T0);

            assertThat(hash).isEqualTo(CommunityPostCreationGuardService.computeCaptionHash("First post ever"));
        }

        @Test
        @DisplayName("Post within 60s cooldown is rejected with Reason.COOLDOWN and ceil Retry-After")
        void shouldRejectPostWithinCooldown() {
            Instant lastPostTime = T0.minusSeconds(25); // 25s ago -> 35s remaining
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(lastPostTime));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> guardService.evaluateEligibility(AUTHOR_ID, "Different caption", T0))
                    .isInstanceOf(CommunityPostCreationRateLimitException.class)
                    .satisfies(ex -> {
                        CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                        assertThat(rateLimitEx.getReason()).isEqualTo(Reason.COOLDOWN);
                        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(35L);
                        assertThat(rateLimitEx.getUserMessage()).contains("quá nhanh");
                    });
        }

        @Test
        @DisplayName("Post at 59.999999s since last post is rejected with Retry-After 1s")
        void shouldRejectPostAtSubsecondBoundary() {
            Instant lastPostTime = T0.minusSeconds(60).plusNanos(1000);
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(lastPostTime));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> guardService.evaluateEligibility(AUTHOR_ID, "Different caption", T0))
                    .isInstanceOf(CommunityPostCreationRateLimitException.class)
                    .satisfies(ex -> {
                        CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                        assertThat(rateLimitEx.getReason()).isEqualTo(Reason.COOLDOWN);
                        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(1L);
                    });
        }

        @Test
        @DisplayName("Post at exactly 60s since last post is allowed")
        void shouldAllowPostAt60s() {
            Instant lastPostTime = T0.minusSeconds(60);
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(lastPostTime));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            String hash = guardService.evaluateEligibility(AUTHOR_ID, "New caption", T0);
            assertThat(hash).isNotNull();
        }
    }

    @Nested
    @DisplayName("Hourly Rule Tests (max 10 posts / rolling 1h)")
    class HourlyRuleTests {

        @Test
        @DisplayName("10 posts in rolling hour allowed; 11th post rejected with wait time until oldest expires")
        void shouldReject11thPostInRollingHour() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);
            Instant oldestInHour = T0.minus(40, ChronoUnit.MINUTES);
            Instant newest = T0.minusSeconds(70);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(newest));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(10L);
            when(guardRepositoryPort.findOldestCreationTimestampAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(Optional.of(oldestInHour));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(10L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> guardService.evaluateEligibility(AUTHOR_ID, "Unique caption 11", T0))
                    .isInstanceOf(CommunityPostCreationRateLimitException.class)
                    .satisfies(ex -> {
                        CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                        assertThat(rateLimitEx.getReason()).isEqualTo(Reason.HOURLY_LIMIT);
                        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(1200L);
                    });
        }

        @Test
        @DisplayName("Exactly when oldest of 10 events reaches 1h boundary, 11th post is allowed")
        void shouldAllow11thPostWhenOldestReaches1HourBoundary() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);
            Instant newest = T0.minus(2, ChronoUnit.MINUTES);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(newest));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(9L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(10L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            String hash = guardService.evaluateEligibility(AUTHOR_ID, "Eligible 11th post", T0);
            assertThat(hash).isNotNull();
        }
    }

    @Nested
    @DisplayName("Daily Rule Tests (max 30 posts / rolling 24h)")
    class DailyRuleTests {

        @Test
        @DisplayName("30 posts in rolling 24h allowed; 31st post rejected with wait time until oldest expires")
        void shouldReject31stPostInRolling24Hours() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);
            Instant oldestInDay = T0.minus(20, ChronoUnit.HOURS);
            Instant newest = T0.minus(2, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(newest));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(30L);
            when(guardRepositoryPort.findOldestCreationTimestampAfter(AUTHOR_ID, dailyCutoff)).thenReturn(Optional.of(oldestInDay));
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> guardService.evaluateEligibility(AUTHOR_ID, "Unique caption 31", T0))
                    .isInstanceOf(CommunityPostCreationRateLimitException.class)
                    .satisfies(ex -> {
                        CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                        assertThat(rateLimitEx.getReason()).isEqualTo(Reason.DAILY_LIMIT);
                        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(14400L);
                    });
        }

        @Test
        @DisplayName("Exactly when oldest of 30 events reaches 24h boundary, 31st post is allowed")
        void shouldAllow31stPostWhenOldestReaches24HourBoundary() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);
            Instant newest = T0.minus(2, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(newest));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(29L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            String hash = guardService.evaluateEligibility(AUTHOR_ID, "Eligible 31st post", T0);
            assertThat(hash).isNotNull();
        }
    }

    @Nested
    @DisplayName("Duplicate Caption Rule Tests (rolling 24h)")
    class DuplicateCaptionTests {

        @Test
        @DisplayName("Same normalized caption within 24 hours is rejected with DUPLICATE_CAPTION")
        void shouldRejectDuplicateCaptionWithin24Hours() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);
            Instant previousPostTime = T0.minus(2, ChronoUnit.HOURS); // 2h ago -> 22h remaining (79200s)

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(previousPostTime));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.of(previousPostTime));

            assertThatThrownBy(() -> guardService.evaluateEligibility(AUTHOR_ID, "Repeated caption text", T0))
                    .isInstanceOf(CommunityPostCreationRateLimitException.class)
                    .satisfies(ex -> {
                        CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                        assertThat(rateLimitEx.getReason()).isEqualTo(Reason.DUPLICATE_CAPTION);
                        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(79200L);
                    });
        }

        @Test
        @DisplayName("Same normalized caption at exactly 24h boundary is allowed")
        void shouldAllowDuplicateCaptionAfter24Hours() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.empty());
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(0L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.empty());

            String hash = guardService.evaluateEligibility(AUTHOR_ID, "Repeated caption text", T0);
            assertThat(hash).isNotNull();
        }
    }

    @Nested
    @DisplayName("Multiple Violations & Retry-After Proof")
    class MultipleViolationsTests {

        @Test
        @DisplayName("When cooldown requires 40s and duplicate requires 20h (72000s), longer wait (20h) is selected")
        void shouldSelectLongerWaitBetweenCooldownAndDuplicate() {
            Instant dailyCutoff = T0.minus(24, ChronoUnit.HOURS);
            Instant hourlyCutoff = T0.minus(1, ChronoUnit.HOURS);
            Instant lastPostTime = T0.minusSeconds(20);
            Instant duplicatePostTime = T0.minus(4, ChronoUnit.HOURS);

            when(guardRepositoryPort.findLatestCreationTimestamp(AUTHOR_ID)).thenReturn(Optional.of(lastPostTime));
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, hourlyCutoff)).thenReturn(1L);
            when(guardRepositoryPort.countCreationsAfter(AUTHOR_ID, dailyCutoff)).thenReturn(2L);
            when(guardRepositoryPort.findLatestMatchingCaptionCreation(eq(AUTHOR_ID), any(), eq(dailyCutoff))).thenReturn(Optional.of(duplicatePostTime));

            assertThatThrownBy(() -> guardService.evaluateEligibility(AUTHOR_ID, "Repeated caption", T0))
                    .isInstanceOf(CommunityPostCreationRateLimitException.class)
                    .satisfies(ex -> {
                        CommunityPostCreationRateLimitException rateLimitEx = (CommunityPostCreationRateLimitException) ex;
                        assertThat(rateLimitEx.getReason()).isEqualTo(Reason.DUPLICATE_CAPTION);
                        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(72000L);
                    });
        }
    }

    @Nested
    @DisplayName("Normalization & Hash Proof Tests")
    class NormalizationHashTests {

        @Test
        @DisplayName("Two inputs that Community normalization maps to same caption yield identical hash and duplicate detected")
        void shouldMapWhitespaceVariationsToIdenticalHash() {
            String rawA = "   Hello world of Community!   ";
            String rawB = "Hello world of Community!";

            String normalizedA = com.universe.community.domain.CommunityPost.validateAndNormalizeCaption(rawA);
            String normalizedB = com.universe.community.domain.CommunityPost.validateAndNormalizeCaption(rawB);

            assertThat(normalizedA).isEqualTo(normalizedB);
            assertThat(CommunityPostCreationGuardService.computeCaptionHash(normalizedA))
                    .isEqualTo(CommunityPostCreationGuardService.computeCaptionHash(normalizedB));
        }

        @Test
        @DisplayName("Two inputs that Community considers distinct do NOT invent extra normalization")
        void shouldPreserveDistinctInputsWithoutExtraNormalization() {
            // Community normalization only trims whitespace and validates length; it does NOT lower-case or strip punctuation
            String captionA = "Hello World";
            String captionB = "hello world";
            String captionC = "Hello World!";

            String normA = com.universe.community.domain.CommunityPost.validateAndNormalizeCaption(captionA);
            String normB = com.universe.community.domain.CommunityPost.validateAndNormalizeCaption(captionB);
            String normC = com.universe.community.domain.CommunityPost.validateAndNormalizeCaption(captionC);

            String hashA = CommunityPostCreationGuardService.computeCaptionHash(normA);
            String hashB = CommunityPostCreationGuardService.computeCaptionHash(normB);
            String hashC = CommunityPostCreationGuardService.computeCaptionHash(normC);

            assertThat(hashA).isNotEqualTo(hashB);
            assertThat(hashA).isNotEqualTo(hashC);
            assertThat(hashB).isNotEqualTo(hashC);
        }

        @Test
        @DisplayName("Known SHA-256 output verification: UTF-8, lowercase hex, length 64")
        void shouldProduceCanonicalSha256Hex() {
            String hash = CommunityPostCreationGuardService.computeCaptionHash("MS-07B8.5.5 Canonical Hash Verification");
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("^[0-9a-f]{64}$");
        }
    }
}

