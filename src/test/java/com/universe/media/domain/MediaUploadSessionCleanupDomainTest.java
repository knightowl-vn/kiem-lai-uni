package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests verifying local domain cleanup eligibility, claim acquisition,
 * lease fencing, outcome transitions, and bypass prevention in {@link MediaUploadSession}.
 * <p>
 * Follows MS-08E2 / S2D-2A specification: deterministic time, fail-closed safety,
 * strictly bounded lease duration (5 min), namespaced claim tokens ({@code CLEANUP_CLAIM:<UUID>}),
 * and zero database/external dependencies.
 */
class MediaUploadSessionCleanupDomainTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/test.mp4");
    private static final String VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String MANIFEST_HASH = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant T1 = T0.plus(1, ChronoUnit.HOURS);
    private static final Instant LEASE_EXPIRY = T1.plus(5, ChronoUnit.MINUTES);

    private static final String VALID_TOKEN = MediaUploadSession.CLEANUP_CLAIM_PREFIX + "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_TOKEN = MediaUploadSession.CLEANUP_CLAIM_PREFIX + "99999999-8888-7777-6666-555555555555";

    private static String validCleanupToken() {
        return MediaUploadSession.CLEANUP_CLAIM_PREFIX + UUID.randomUUID();
    }

    private MediaUploadSession createSession(
            MediaUploadSessionStatus status,
            UploadSessionCleanupStatus cleanupStatus,
            String providerUploadId,
            String leaseOwner,
            Instant leaseExpiresAt,
            String declaredContentHash,
            String manifestHash,
            UUID registeredAssetId,
            int cleanupRetryCount
    ) {
        Instant completedAt = (status == MediaUploadSessionStatus.COMPLETED) ? T0 : null;
        return MediaUploadSession.rehydrate(
                UUID.randomUUID(),
                "user-test",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-test-" + UUID.randomUUID(),
                MediaType.VIDEO,
                "video/mp4",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                providerUploadId,
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                2,
                declaredContentHash,
                "video.mp4",
                "screen-admin",
                status,
                T0.plus(24, ChronoUnit.HOURS),
                leaseOwner,
                leaseExpiresAt,
                registeredAssetId,
                cleanupStatus,
                cleanupRetryCount,
                null,
                null,
                T0,
                T0,
                completedAt,
                1L,
                manifestHash
        );
    }

    private MediaUploadSession createEligibleAbortedSession() {
        return createSession(
                MediaUploadSessionStatus.ABORTED,
                UploadSessionCleanupStatus.PENDING,
                "b2-upload-123",
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    private MediaUploadSession createEligibleExpiredSession() {
        return createSession(
                MediaUploadSessionStatus.EXPIRED,
                UploadSessionCleanupStatus.PENDING,
                "b2-upload-123",
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    // =============================================================================================
    // Matrix A: Domain Eligibility Tests
    // =============================================================================================

    @Nested
    @DisplayName("Matrix A — Domain Local Cleanup Eligibility")
    class EligibilityTests {

        @Test
        @DisplayName("ABORTED + PENDING without completion evidence is locally eligible")
        void abortedPendingIsEligible() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThat(session.isLocallyEligibleForCleanup(T1)).isTrue();
            assertThatCode(() -> session.validateLocalCleanupEligibility(T1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("EXPIRED + PENDING without completion evidence is locally eligible")
        void expiredPendingIsEligible() {
            MediaUploadSession session = createEligibleExpiredSession();

            assertThat(session.isLocallyEligibleForCleanup(T1)).isTrue();
            assertThatCode(() -> session.validateLocalCleanupEligibility(T1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ABORTED + FAILED without completion evidence is locally eligible as retry candidate")
        void abortedFailedRetryCandidateIsEligible() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.FAILED,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    1
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isTrue();
            assertThatCode(() -> session.validateLocalCleanupEligibility(T1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("EXPIRED + FAILED without completion evidence is locally eligible as retry candidate")
        void expiredFailedRetryCandidateIsEligible() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.EXPIRED,
                    UploadSessionCleanupStatus.FAILED,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    2
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isTrue();
            assertThatCode(() -> session.validateLocalCleanupEligibility(T1)).doesNotThrowAnyException();
        }

        @ParameterizedTest
        @EnumSource(value = MediaUploadSessionStatus.class, names = {"ALLOCATED", "INITIATED", "COMPLETING", "COMPLETED", "CLEANED_UP", "FAILED"})
        @DisplayName("Rejects ineligible lifecycle statuses from cleanup eligibility")
        void rejectsIneligibleLifecycleStatuses(MediaUploadSessionStatus status) {
            String uploadId = (status == MediaUploadSessionStatus.ALLOCATED) ? null : "b2-upload-123";
            String hash = (status == MediaUploadSessionStatus.COMPLETED) ? VALID_HASH : null;
            UUID assetId = (status == MediaUploadSessionStatus.COMPLETED) ? UUID.randomUUID() : null;

            MediaUploadSession session = createSession(
                    status,
                    UploadSessionCleanupStatus.PENDING,
                    uploadId,
                    null,
                    null,
                    hash,
                    null,
                    assetId,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not locally eligible for cleanup due to lifecycle status: " + status);
        }

        @ParameterizedTest
        @EnumSource(value = UploadSessionCleanupStatus.class, names = {"NONE", "IN_PROGRESS", "COMPLETED"})
        @DisplayName("Rejects unapproved cleanup statuses from cleanup eligibility")
        void rejectsIneligibleCleanupStatuses(UploadSessionCleanupStatus cleanupStatus) {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    cleanupStatus,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not locally eligible for cleanup due to cleanup status: " + cleanupStatus);
        }

        @Test
        @DisplayName("Rejects candidate when providerUploadId is null")
        void rejectsNullProviderUploadId() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("providerUploadId is missing or blank");
        }

        @Test
        @DisplayName("Rejects candidate when providerUploadId is blank")
        void rejectsBlankProviderUploadId() {
            assertThatThrownBy(() -> createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "   ",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Upload session field [providerUploadId] must not be blank.");
        }

        @Test
        @DisplayName("Rejects candidate when declaredContentHash is present")
        void rejectsDeclaredContentHashPresence() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    null,
                    null,
                    VALID_HASH,
                    null,
                    null,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("declaredContentHash is present");
        }

        @Test
        @DisplayName("Rejects candidate when manifestHash is present")
        void rejectsManifestHashPresence() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    MANIFEST_HASH,
                    null,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manifestHash is present");
        }

        @Test
        @DisplayName("Rejects candidate when registeredAssetId is present")
        void rejectsRegisteredAssetIdPresence() {
            UUID assetId = UUID.randomUUID();
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    assetId,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("registeredAssetId is present");
        }

        @Test
        @DisplayName("Session transitioned to EXPIRED from COMPLETING retains completion intent and is rejected")
        void completingSessionExpiredRetainsCompletionIntentAndIsRejected() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.INITIATED,
                    UploadSessionCleanupStatus.NONE,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );

            session.markCompleting(VALID_HASH, T0.plusSeconds(30));
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            assertThat(session.getDeclaredContentHash()).isEqualTo(VALID_HASH);

            session.expire(T0.plusSeconds(60));
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
            assertThat(session.getDeclaredContentHash()).isEqualTo(VALID_HASH);

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("declaredContentHash is present");
        }

        @Test
        @DisplayName("Rejects candidate when leaseOwner is held")
        void rejectsCandidateWhenLeaseHeld() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    VALID_TOKEN,
                    T1.plus(3, ChronoUnit.MINUTES),
                    null,
                    null,
                    null,
                    0
            );

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();
            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lease is held by: " + VALID_TOKEN);
        }

        @Test
        @DisplayName("validateLocalCleanupEligibility requires non-null timestamp")
        void requiresNonNullTimestamp() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.validateLocalCleanupEligibility(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Timestamp cannot be null");

            assertThatThrownBy(() -> session.isLocallyEligibleForCleanup(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Timestamp cannot be null");
        }
    }

    // =============================================================================================
    // Matrix B: Claim Acquisition Tests
    // =============================================================================================

    @Nested
    @DisplayName("Matrix B — Cleanup Claim Acquisition & Namespace Identity")
    class ClaimAcquisitionTests {

        @Test
        @DisplayName("Successfully claims cleanup lease on eligible unleased session with valid CLEANUP_CLAIM token")
        void successfullyClaimsCleanupLease() {
            MediaUploadSession session = createEligibleAbortedSession();
            String claimToken = validCleanupToken();

            session.claimCleanup(claimToken, LEASE_EXPIRY, T1);

            assertThat(session.getLeaseOwner()).isEqualTo(claimToken);
            assertThat(session.getLeaseExpiresAt()).isEqualTo(LEASE_EXPIRY);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
            assertThat(session.getCleanupLastAttemptAt()).isEqualTo(T1);
            assertThat(session.getUpdatedAt()).isEqualTo(T1);
            assertThat(session.hasActiveCleanupLease(claimToken, T1)).isTrue();
            assertThat(session.hasActiveCleanupLease(T1)).isTrue();
            assertThat(session.isCleanupLeaseExpired(T1)).isFalse();
            assertThat(session.isCleanupLeaseHeld()).isTrue();
        }

        @Test
        @DisplayName("claimCleanup accepts exact 5-minute maximum lease duration boundary")
        void acceptsExactMaximumLeaseDuration() {
            MediaUploadSession session = createEligibleAbortedSession();
            Instant maxExpiry = T1.plus(MediaUploadSession.MAX_CLEANUP_LEASE_DURATION);

            session.claimCleanup(VALID_TOKEN, maxExpiry, T1);

            assertThat(session.getLeaseExpiresAt()).isEqualTo(maxExpiry);
            assertThat(session.hasActiveCleanupLease(VALID_TOKEN, T1)).isTrue();
        }

        @Test
        @DisplayName("claimCleanup rejects excessive lease duration exceeding 5 minutes")
        void rejectsExcessiveLeaseDuration() {
            MediaUploadSession session = createEligibleAbortedSession();
            Instant excessiveExpiry = T1.plus(MediaUploadSession.MAX_CLEANUP_LEASE_DURATION).plusSeconds(1);

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, excessiveExpiry, T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds maximum allowed cleanup lease duration");
        }

        @Test
        @DisplayName("claimCleanup fails when claimToken is null or blank")
        void rejectsNullOrBlankClaimToken() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.claimCleanup(null, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claimToken must not be blank");

            assertThatThrownBy(() -> session.claimCleanup("   ", LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claimToken must not be blank");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ARBITRARY_TOKEN_123",
                "INIT_CLAIM:11111111-2222-3333-4444-555555555555",
                "COMPLETION_CLAIM:11111111-2222-3333-4444-555555555555",
                "CLEANUP_CLAIM:not-a-uuid",
                "CLEANUP_CLAIM:12345",
                "CLEANUP_CLAIM:",
                "CLEANUP_CLAIM:11111111-2222-3333-4444-55555555555g" // non-hex
        })
        @DisplayName("claimCleanup rejects tokens not conforming to CLEANUP_CLAIM:<UUID>")
        void rejectsInvalidNamespaceAndMalformedTokens(String malformedToken) {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.claimCleanup(malformedToken, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("claimCleanup fails when leaseExpiresAt is null, equal to now, or before now")
        void rejectsInvalidLeaseExpiresAt() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, null, T1))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("leaseExpiresAt");

            // Expiry equal to now
            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, T1, T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly after now");

            // Expiry in past
            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, T1.minusSeconds(1), T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly after now");
        }

        @Test
        @DisplayName("claimCleanup fails when now timestamp is null")
        void rejectsNullNowTimestamp() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Timestamp cannot be null");
        }

        @Test
        @DisplayName("claimCleanup rejected when active initiation lease is already held")
        void rejectsWhenActiveInitiationLeaseHeld() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ALLOCATED,
                    UploadSessionCleanupStatus.NONE,
                    null,
                    "INIT-LEASE-1",
                    T1.plus(3, ChronoUnit.MINUTES),
                    null,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("claimCleanup rejected when active completion lease is already held")
        void rejectsWhenActiveCompletionLeaseHeld() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.COMPLETING,
                    UploadSessionCleanupStatus.NONE,
                    "b2-upload-123",
                    "COMPLETION-LEASE-1",
                    T1.plus(3, ChronoUnit.MINUTES),
                    VALID_HASH,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("claimCleanup rejected when an expired non-cleanup lease is unresolved")
        void rejectsWhenExpiredNonCleanupLeaseIsUnresolved() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    "UNRESOLVED-PRIOR-CLAIM",
                    T1.minus(5, ChronoUnit.MINUTES),
                    null,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lease is held by: UNRESOLVED-PRIOR-CLAIM");
        }

        @Test
        @DisplayName("claimCleanup rejected when active cleanup claim is already held")
        void rejectsWhenActiveCleanupClaimHeld() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThatThrownBy(() -> session.claimCleanup(OTHER_TOKEN, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup status: IN_PROGRESS");
        }

        @Test
        @DisplayName("Expired cleanup claim is NOT automatically stolen in S2D-2A")
        void expiredCleanupClaimIsNotAutomaticallyStolen() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            Instant afterExpiry = LEASE_EXPIRY.plus(10, ChronoUnit.MINUTES);
            assertThat(session.isCleanupLeaseExpired(afterExpiry)).isTrue();

            assertThatThrownBy(() -> session.claimCleanup(OTHER_TOKEN, afterExpiry.plus(2, ChronoUnit.MINUTES), afterExpiry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup status: IN_PROGRESS");
        }

        @Test
        @DisplayName("Expired claim with PENDING cleanup status is still rejected if lease is unresolved")
        void expiredClaimWithPendingStatusRejectedIfLeaseUnresolved() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    VALID_TOKEN,
                    T1.minus(10, ChronoUnit.MINUTES),
                    null,
                    null,
                    null,
                    0
            );

            assertThat(session.isCleanupLeaseExpired(T1)).isTrue();
            assertThatThrownBy(() -> session.claimCleanup(OTHER_TOKEN, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lease is held by: " + VALID_TOKEN);
        }

        @Test
        @DisplayName("Preserves persisted completion hashes and rejects claimCleanup")
        void preservesPersistedCompletionHashesAndRejectsClaim() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.PENDING,
                    "b2-upload-123",
                    null,
                    null,
                    VALID_HASH,
                    MANIFEST_HASH,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("declaredContentHash is present");

            // Evidence preserved
            assertThat(session.getDeclaredContentHash()).isEqualTo(VALID_HASH);
            assertThat(session.getManifestHash()).isEqualTo(MANIFEST_HASH);
            assertThat(session.getLeaseOwner()).isNull();
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
        }
    }

    // =============================================================================================
    // Matrix C: Fencing, Outcome Transitions & Isolation Tests
    // =============================================================================================

    @Nested
    @DisplayName("Matrix C — Fencing, Outcome Transitions & Isolation")
    class FencingAndTransitionsTests {

        @Test
        @DisplayName("markCleanupCompleted succeeds with correct token and valid active lease")
        void markCleanupCompletedSucceedsWithValidToken() {
            MediaUploadSession session = createEligibleAbortedSession();
            String token = VALID_TOKEN;
            session.claimCleanup(token, LEASE_EXPIRY, T1);

            Instant completionTime = T1.plus(1, ChronoUnit.MINUTES);
            session.markCleanupCompleted(token, completionTime);

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.COMPLETED);
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.CLEANED_UP);
            assertThat(session.getLeaseOwner()).isNull();
            assertThat(session.getLeaseExpiresAt()).isNull();
            assertThat(session.getUpdatedAt()).isEqualTo(completionTime);
        }

        @Test
        @DisplayName("markCleanupFailed succeeds with correct token and valid active lease")
        void markCleanupFailedSucceedsWithValidToken() {
            MediaUploadSession session = createEligibleExpiredSession();
            String token = VALID_TOKEN;
            session.claimCleanup(token, LEASE_EXPIRY, T1);

            Instant failTime = T1.plus(2, ChronoUnit.MINUTES);
            session.markCleanupFailed(token, "B2 503 Service Unavailable", failTime);

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.FAILED);
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
            assertThat(session.getCleanupRetryCount()).isEqualTo(1);
            assertThat(session.getCleanupLastAttemptAt()).isEqualTo(failTime);
            assertThat(session.getCleanupErrorDetail()).isEqualTo("B2 503 Service Unavailable");
            assertThat(session.getLeaseOwner()).isNull();
            assertThat(session.getLeaseExpiresAt()).isNull();
            assertThat(session.getUpdatedAt()).isEqualTo(failTime);
        }

        @Test
        @DisplayName("markCleanupCompleted rejected when wrong claim token is provided")
        void markCleanupCompletedRejectsWrongToken() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThatThrownBy(() -> session.markCleanupCompleted(OTHER_TOKEN, T1.plusSeconds(30)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot complete cleanup outcome without holding active claim token; held by: " + VALID_TOKEN);
        }

        @Test
        @DisplayName("markCleanupFailed rejected when wrong claim token is provided")
        void markCleanupFailedRejectsWrongToken() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThatThrownBy(() -> session.markCleanupFailed(OTHER_TOKEN, "error", T1.plusSeconds(30)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot record cleanup outcome without holding active claim token; held by: " + VALID_TOKEN);
        }

        @Test
        @DisplayName("markCleanupCompleted rejected when claimToken is malformed or wrong namespace")
        void markCleanupCompletedRejectsMalformedToken() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThatThrownBy(() -> session.markCleanupCompleted("MALFORMED", T1.plusSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> session.markCleanupCompleted("INIT_CLAIM:11111111-2222-3333-4444-555555555555", T1.plusSeconds(30)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("markCleanupCompleted rejected after cleanup lease has expired")
        void markCleanupCompletedRejectsAfterLeaseExpiry() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            Instant afterExpiry = LEASE_EXPIRY.plusSeconds(1);

            assertThatThrownBy(() -> session.markCleanupCompleted(VALID_TOKEN, afterExpiry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup lease has expired");
        }

        @Test
        @DisplayName("markCleanupFailed rejected after cleanup lease has expired")
        void markCleanupFailedRejectsAfterLeaseExpiry() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            Instant afterExpiry = LEASE_EXPIRY.plusSeconds(1);

            assertThatThrownBy(() -> session.markCleanupFailed(VALID_TOKEN, "timeout", afterExpiry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup lease has expired");
        }

        @Test
        @DisplayName("hasActiveCleanupLease returns false for wrong, expired or missing token")
        void hasActiveCleanupLeaseValidation() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThat(session.hasActiveCleanupLease(VALID_TOKEN, T1)).isTrue();
            assertThat(session.hasActiveCleanupLease(T1)).isTrue();

            assertThat(session.hasActiveCleanupLease(OTHER_TOKEN, T1)).isFalse();
            assertThat(session.hasActiveCleanupLease("INVALID", T1)).isFalse();

            Instant afterExpiry = LEASE_EXPIRY.plusSeconds(1);
            assertThat(session.hasActiveCleanupLease(VALID_TOKEN, afterExpiry)).isFalse();
            assertThat(session.hasActiveCleanupLease(afterExpiry)).isFalse();
            assertThat(session.isCleanupLeaseExpired(afterExpiry)).isTrue();
        }

        @Test
        @DisplayName("Cleanup claim and outcomes do NOT alter storageAllocationId, storageKey, or persistenceVersion")
        void preservesStorageAllocationAndKeyIntegrity() {
            MediaUploadSession session = createEligibleAbortedSession();
            UUID origAllocationId = session.getStorageAllocationId();
            StorageKey origStorageKey = session.getStorageKey();
            Long origVersion = session.getPersistenceVersion();
            String origProviderUploadId = session.getProviderUploadId();

            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThat(session.getStorageAllocationId()).isEqualTo(origAllocationId);
            assertThat(session.getStorageKey()).isEqualTo(origStorageKey);
            assertThat(session.getPersistenceVersion()).isEqualTo(origVersion);
            assertThat(session.getProviderUploadId()).isEqualTo(origProviderUploadId);

            session.markCleanupCompleted(VALID_TOKEN, T1.plus(1, ChronoUnit.MINUTES));

            assertThat(session.getStorageAllocationId()).isEqualTo(origAllocationId);
            assertThat(session.getStorageKey()).isEqualTo(origStorageKey);
            assertThat(session.getPersistenceVersion()).isEqualTo(origVersion);
            assertThat(session.getProviderUploadId()).isEqualTo(origProviderUploadId);
        }

        @Test
        @DisplayName("markCleanupCompleted rejected if completion evidence is present")
        void markCleanupCompletedRejectsCompletionEvidence() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.IN_PROGRESS,
                    "b2-upload-123",
                    VALID_TOKEN,
                    LEASE_EXPIRY,
                    VALID_HASH,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.markCleanupCompleted(VALID_TOKEN, T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completion intent or registered asset is present");

            // Hash preserved
            assertThat(session.getDeclaredContentHash()).isEqualTo(VALID_HASH);
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
        }
    }

    // =============================================================================================
    // Matrix D: Lease Clearing & Bypass Prevention Tests
    // =============================================================================================

    @Nested
    @DisplayName("Matrix D — Lease Clearing & Bypass Prevention")
    class LeaseClearingTests {

        @Test
        @DisplayName("clearLease rejects active cleanup lease")
        void clearLeaseRejectsActiveCleanupLease() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThatThrownBy(() -> session.clearLease(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot clear cleanup lease using generic clearLease");

            // Lease and cleanup state remain stored
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
            assertThat(session.getLeaseExpiresAt()).isEqualTo(LEASE_EXPIRY);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("clearLease rejects expired cleanup lease")
        void clearLeaseRejectsExpiredCleanupLease() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            Instant afterExpiry = LEASE_EXPIRY.plus(10, ChronoUnit.MINUTES);
            assertThat(session.isCleanupLeaseExpired(afterExpiry)).isTrue();

            assertThatThrownBy(() -> session.clearLease(afterExpiry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot clear cleanup lease using generic clearLease");

            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("clearLease succeeds for legitimate non-cleanup (initiation) lease")
        void clearLeaseSucceedsForInitiationLease() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ALLOCATED,
                    UploadSessionCleanupStatus.NONE,
                    null,
                    "INIT-WORKER-1",
                    T1.plus(3, ChronoUnit.MINUTES),
                    null,
                    null,
                    null,
                    0
            );

            session.clearLease(T1);

            assertThat(session.getLeaseOwner()).isNull();
            assertThat(session.getLeaseExpiresAt()).isNull();
        }

        @Test
        @DisplayName("abort, fail, and expire reject session while cleanup lease is held")
        void lifecycleMutationsRejectWhileCleanupLeaseHeld() {
            MediaUploadSession session = createEligibleAbortedSession();
            session.claimCleanup(VALID_TOKEN, LEASE_EXPIRY, T1);

            assertThatThrownBy(() -> session.abort(T1.plusSeconds(30)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot abort session while cleanup lease is held");

            assertThatThrownBy(() -> session.fail("error", T1.plusSeconds(30)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot fail session while cleanup lease is held");

            assertThatThrownBy(() -> session.expire(T1.plusSeconds(30)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot expire session while cleanup lease is held");

            // State unchanged
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
        }
    }

    // =============================================================================================
    // Matrix E: Legacy API Closure & Fail-Closed Tests
    // =============================================================================================

    @Nested
    @DisplayName("Matrix E — Legacy API Closure & Fail-Closed Guards")
    class LegacyClosureTests {

        @Test
        @DisplayName("Legacy un-tokened markCleanupCompleted is prohibited even when unleased")
        void legacyMarkCleanupCompletedFailsClosedWhenUnleased() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.markCleanupCompleted(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Direct un-tokened cleanup completion is prohibited");

            // Session state completely unaffected
            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
        }

        @Test
        @DisplayName("Legacy un-tokened markCleanupInProgress is prohibited even when unleased")
        void legacyMarkCleanupInProgressFailsClosedWhenUnleased() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.markCleanupInProgress(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Direct un-tokened cleanup progress transition is prohibited");

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
        }

        @Test
        @DisplayName("Legacy un-tokened markCleanupFailed is prohibited even when unleased")
        void legacyMarkCleanupFailedFailsClosedWhenUnleased() {
            MediaUploadSession session = createEligibleAbortedSession();

            assertThatThrownBy(() -> session.markCleanupFailed("error", T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Direct un-tokened cleanup failure recording is prohibited");

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
            assertThat(session.getCleanupRetryCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Legacy un-tokened markCleanupPending succeeds ONLY on unleased NONE session without completion evidence")
        void legacyMarkCleanupPendingSucceedsOnlyFromNone() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.NONE,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );

            session.markCleanupPending(T1);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
        }

        @ParameterizedTest
        @EnumSource(value = UploadSessionCleanupStatus.class, names = {"PENDING", "IN_PROGRESS", "FAILED", "COMPLETED"})
        @DisplayName("Legacy markCleanupPending rejects resetting cleanup status from non-NONE states")
        void legacyMarkCleanupPendingRejectsResettingNonNone(UploadSessionCleanupStatus status) {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    status,
                    "b2-upload-123",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.markCleanupPending(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cleanup status cannot be reset to PENDING");
        }

        @Test
        @DisplayName("Legacy markCleanupPending rejects when completion evidence is present")
        void legacyMarkCleanupPendingRejectsCompletionEvidence() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.NONE,
                    "b2-upload-123",
                    null,
                    null,
                    VALID_HASH,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.markCleanupPending(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completion intent or registered asset is present");
        }

        @Test
        @DisplayName("Legacy markCleanupPending rejects when lease is held")
        void legacyMarkCleanupPendingRejectsWhenLeaseHeld() {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.NONE,
                    "b2-upload-123",
                    VALID_TOKEN,
                    LEASE_EXPIRY,
                    null,
                    null,
                    null,
                    0
            );

            assertThatThrownBy(() -> session.markCleanupPending(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("while lease is held by");
        }
    }

    // =============================================================================================
    // Matrix F – Durable Quarantine Transitions & Invariants (S2D-2B1)
    // =============================================================================================

    @Nested
    @DisplayName("Matrix F – Durable Quarantine Transitions & Invariants")
    class MatrixFDurableQuarantineTransitionsAndInvariants {

        private MediaUploadSession createInProgressSession(MediaUploadSessionStatus status) {
            return createSession(
                    status,
                    UploadSessionCleanupStatus.IN_PROGRESS,
                    "b2-upload-123",
                    VALID_TOKEN,
                    LEASE_EXPIRY,
                    null,
                    null,
                    null,
                    1
            );
        }

        @Test
        @DisplayName("1. Valid cleanup claim transitions to QUARANTINED, updates timestamps and bounds reason")
        void validClaimTransitionsToQuarantined() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);

            session.markCleanupQuarantined(VALID_TOKEN, "Ambiguous complete outcome: diagnostic probe timed out", T1);

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(session.isQuarantined()).isTrue();
            assertThat(session.getCleanupErrorDetail()).isEqualTo("Ambiguous complete outcome: diagnostic probe timed out");
            assertThat(session.getCleanupLastAttemptAt()).isEqualTo(T1);
            assertThat(session.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        @DisplayName("2. Quarantine preserves ABORTED lifecycle status")
        void quarantinePreservesAbortedLifecycleStatus() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);

            session.markCleanupQuarantined(VALID_TOKEN, "Object detected on storage", T1);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
        }

        @Test
        @DisplayName("3. Quarantine preserves EXPIRED lifecycle status")
        void quarantinePreservesExpiredLifecycleStatus() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.EXPIRED);

            session.markCleanupQuarantined(VALID_TOKEN, "Object detected on storage", T1);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.EXPIRED);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
        }

        @Test
        @DisplayName("4. Claim lease is released only on valid transition")
        void claimLeaseReleasedOnlyOnValidTransition() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
            assertThat(session.getLeaseExpiresAt()).isEqualTo(LEASE_EXPIRY);
            assertThat(session.isCleanupLeaseHeld()).isTrue();

            session.markCleanupQuarantined(VALID_TOKEN, "Valid reason", T1);

            assertThat(session.getLeaseOwner()).isNull();
            assertThat(session.getLeaseExpiresAt()).isNull();
            assertThat(session.isCleanupLeaseHeld()).isFalse();
        }

        @Test
        @DisplayName("5. Wrong token is rejected and leaves state unchanged")
        void wrongTokenRejected() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);

            assertThatThrownBy(() -> session.markCleanupQuarantined(OTHER_TOKEN, "Reason", T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without holding active claim token");

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
            assertThat(session.getLeaseExpiresAt()).isEqualTo(LEASE_EXPIRY);
        }

        @Test
        @DisplayName("6. Expired token is rejected and leaves state unchanged")
        void expiredTokenRejected() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            Instant afterExpiry = LEASE_EXPIRY.plusSeconds(1);

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, "Reason", afterExpiry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup lease has expired");

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
        }

        @Test
        @DisplayName("7. Missing or malformed token is rejected")
        void missingOrMalformedTokenRejected() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);

            assertThatThrownBy(() -> session.markCleanupQuarantined(null, "Reason", T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claimToken must not be blank");

            assertThatThrownBy(() -> session.markCleanupQuarantined("   ", "Reason", T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claimToken must not be blank");

            assertThatThrownBy(() -> session.markCleanupQuarantined("GENERIC_TOKEN", "Reason", T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("claimToken must belong to cleanup namespace");
        }

        @Test
        @DisplayName("8. Blank quarantine reason is rejected and leaves state unchanged")
        void blankQuarantineReasonRejected() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, null, T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quarantine reason must not be blank");

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, "", T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quarantine reason must not be blank");

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, "   \t\n  ", T1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quarantine reason must not be blank");

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
        }

        @ParameterizedTest
        @EnumSource(value = MediaUploadSessionStatus.class, names = {"ALLOCATED", "INITIATED", "COMPLETING", "COMPLETED", "FAILED", "CLEANED_UP"})
        @DisplayName("9. Invalid lifecycle status is rejected")
        void invalidLifecycleRejected(MediaUploadSessionStatus invalidStatus) {
            String hash = (invalidStatus == MediaUploadSessionStatus.COMPLETED) ? VALID_HASH : null;
            UUID assetId = (invalidStatus == MediaUploadSessionStatus.COMPLETED) ? UUID.randomUUID() : null;
            MediaUploadSession session = createSession(
                    invalidStatus,
                    UploadSessionCleanupStatus.IN_PROGRESS,
                    "b2-upload-123",
                    VALID_TOKEN,
                    LEASE_EXPIRY,
                    hash,
                    null,
                    assetId,
                    1
            );

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, "Reason", T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("for session in lifecycle status: " + invalidStatus);
        }


        @ParameterizedTest
        @EnumSource(value = UploadSessionCleanupStatus.class, names = {"NONE", "PENDING", "COMPLETED", "FAILED", "QUARANTINED"})
        @DisplayName("10. Invalid cleanup status is rejected when not IN_PROGRESS")
        void invalidCleanupStatusRejected(UploadSessionCleanupStatus invalidCleanupStatus) {
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    invalidCleanupStatus,
                    "b2-upload-123",
                    VALID_TOKEN,
                    LEASE_EXPIRY,
                    null,
                    null,
                    null,
                    1
            );

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, "Reason", T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("when cleanupStatus is not IN_PROGRESS: " + invalidCleanupStatus);
        }

        @Test
        @DisplayName("11. Repeated quarantine attempt is rejected")
        void repeatedQuarantineAttemptRejected() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            session.markCleanupQuarantined(VALID_TOKEN, "First quarantine", T1);

            assertThatThrownBy(() -> session.markCleanupQuarantined(VALID_TOKEN, "Second quarantine", T1.plusSeconds(10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("without holding active claim token");
        }

        @Test
        @DisplayName("12. QUARANTINED session cannot be claimed again")
        void quarantinedSessionCannotBeClaimedAgain() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            session.markCleanupQuarantined(VALID_TOKEN, "Quarantined reason", T1);

            assertThat(session.isLocallyEligibleForCleanup(T1)).isFalse();

            String newClaimToken = validCleanupToken();
            assertThatThrownBy(() -> session.claimCleanup(newClaimToken, T1.plus(Duration.ofMinutes(5)), T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not locally eligible for cleanup due to cleanup status: QUARANTINED");
        }

        @Test
        @DisplayName("13. QUARANTINED cannot become retryable via legacy or direct methods")
        void quarantinedCannotBecomeRetryableViaLegacyMethods() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            session.markCleanupQuarantined(VALID_TOKEN, "Quarantined reason", T1);

            assertThatThrownBy(() -> session.markCleanupPending(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot mark cleanup pending from cleanup status: QUARANTINED");

            assertThatThrownBy(() -> session.markCleanupInProgress(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Direct un-tokened cleanup progress transition is prohibited");

            assertThatThrownBy(() -> session.markCleanupCompleted(T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Direct un-tokened cleanup completion is prohibited");

            assertThatThrownBy(() -> session.markCleanupFailed("detail", T1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Direct un-tokened cleanup failure recording is prohibited");
        }

        @Test
        @DisplayName("14. Completion markers and registeredAssetId are preserved across quarantine")
        void completionMarkersArePreserved() {
            UUID assetId = UUID.randomUUID();
            MediaUploadSession session = createSession(
                    MediaUploadSessionStatus.ABORTED,
                    UploadSessionCleanupStatus.IN_PROGRESS,
                    "b2-upload-123",
                    VALID_TOKEN,
                    LEASE_EXPIRY,
                    VALID_HASH,
                    MANIFEST_HASH,
                    assetId,
                    1
            );

            session.markCleanupQuarantined(VALID_TOKEN, "Quarantine with preserved markers", T1);

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.QUARANTINED);
            assertThat(session.getDeclaredContentHash()).isEqualTo(VALID_HASH);
            assertThat(session.getManifestHash()).isEqualTo(MANIFEST_HASH);
            assertThat(session.getRegisteredAssetId()).isEqualTo(assetId);
        }

        @Test
        @DisplayName("15. Storage allocation identity and provider binding are unchanged")
        void storageAllocationIdentityIsUnchanged() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            UUID allocId = session.getStorageAllocationId();
            StorageKey key = session.getStorageKey();
            String bucket = session.getStorageBucketName();
            StorageProviderId provider = session.getStorageProviderId();

            session.markCleanupQuarantined(VALID_TOKEN, "Reason", T1);

            assertThat(session.getStorageAllocationId()).isEqualTo(allocId);
            assertThat(session.getStorageKey()).isEqualTo(key);
            assertThat(session.getStorageBucketName()).isEqualTo(bucket);
            assertThat(session.getStorageProviderId()).isEqualTo(provider);
        }

        @Test
        @DisplayName("16. Failed guards do not mutate state")
        void failedGuardsDoNotMutateState() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);

            assertThatThrownBy(() -> session.markCleanupQuarantined(OTHER_TOKEN, "Reason", T1))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.IN_PROGRESS);
            assertThat(session.getLeaseOwner()).isEqualTo(VALID_TOKEN);
            assertThat(session.getLeaseExpiresAt()).isEqualTo(LEASE_EXPIRY);
            assertThat(session.getCleanupErrorDetail()).isNull();
        }

        @Test
        @DisplayName("17. Quarantine reason exceeding 1000 characters is bounded to 1000 characters")
        void quarantineReasonIsBoundedTo1000Chars() {
            MediaUploadSession session = createInProgressSession(MediaUploadSessionStatus.ABORTED);
            String longReason = "A".repeat(1500);

            session.markCleanupQuarantined(VALID_TOKEN, longReason, T1);

            assertThat(session.getCleanupErrorDetail()).hasSize(1000);
            assertThat(session.getCleanupErrorDetail()).isEqualTo("A".repeat(1000));
        }
    }
}
