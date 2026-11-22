package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaVideoHlsFoundationDomainTest {

    private static final UUID ASSET_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID PKG_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PKG_UUID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID JOB_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final ContentHash SHA256 =
            ContentHash.of("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

    @Test
    @DisplayName("MediaVideoHlsPackage enforces fMP4, segment duration bounds, prefix containment, and strict status transitions")
    void shouldEnforceHlsPackageDomainInvariantsAndTransitions() {
        MediaVideoHlsPackage pkg = MediaVideoHlsPackage.startProcessing(
                PKG_ID,
                ASSET_ID,
                VERSION_ID,
                1,
                PKG_UUID,
                1,
                StorageProviderId.of("r2"),
                "kiemlai-media-test",
                StorageKey.of("video/hls/" + ASSET_ID + "/v1/" + PKG_UUID + "/"),
                4,
                T0
        );

        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PROCESSING);
        assertThat(pkg.getMasterManifestKey().value())
                .isEqualTo("video/hls/" + ASSET_ID + "/v1/" + PKG_UUID + "/master.m3u8");
        assertThat(pkg.getSegmentFormat()).isEqualTo("FMP4");
        assertThat(pkg.getHlsVersion()).isEqualTo(7);

        // Reject invalid segment duration
        assertThatThrownBy(() -> MediaVideoHlsPackage.startProcessing(
                PKG_ID, ASSET_ID, VERSION_ID, 1, PKG_UUID, 1,
                StorageProviderId.of("r2"), "bucket", StorageKey.of("video/hls/a/"), 11, T0
        )).isInstanceOf(IllegalArgumentException.class);

        // Reject invalid READY evidence (objectCount < MIN_READY_OBJECT_COUNT or size <= 0)
        assertThatThrownBy(() -> pkg.markReady(SHA256, 0L, 4, "[{\"height\":720}]", T0.plusSeconds(5), T0.plusSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pkg.markReady(SHA256, 1024L, 1, "[{\"height\":720}]", T0.plusSeconds(5), T0.plusSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class);

        // Transition PROCESSING -> READY -> SUPERSEDED -> PURGING -> PURGED
        pkg.markReady(SHA256, 4096L, 5, "[{\"height\":720,\"bandwidth\":2800000}]", T0.plusSeconds(5), T0.plusSeconds(6));
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.READY);
        assertThat(pkg.getReadyAt()).isEqualTo(T0.plusSeconds(6));

        // Cannot abandon or fail a READY package
        assertThatThrownBy(() -> pkg.markAbandoned("late", T0.plusSeconds(10), T0.plusSeconds(7)))
                .isInstanceOf(IllegalStateException.class);

        Instant purgeAfter = T0.plus(Duration.ofDays(7));
        pkg.markSuperseded(purgeAfter, T0.plusSeconds(8));
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.SUPERSEDED);

        // Premature purge claim before 7-day supersession floor is rejected
        assertThatThrownBy(() -> pkg.markPurging(T0.plusSeconds(9)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not purge-eligible");

        Instant eligibleAt = T0.plus(Duration.ofDays(7)).plusSeconds(10);
        pkg.markPurging(eligibleAt);
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(pkg.getPurgeAfter()).isEqualTo(eligibleAt.plus(MediaVideoHlsPackage.DEFAULT_PURGE_CLAIM_LEASE));

        pkg.markPurged(eligibleAt.plusSeconds(5));
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
        assertThat(pkg.getPurgeAfter()).isNull();
    }

    @Test
    @DisplayName("Purge lifecycle enforces 24h FAILED/ABANDONED & 7d SUPERSEDED floors, legacy protection, claim fencing, failure backoff, and READY/PROCESSING/PURGED immunity")
    void shouldEnforcePurgeEligibilityRetentionFloorsClaimReclaimAndBackoffInvariants() {
        // 1. FAILED package (including legacy purgeAfter = T0) cannot enter PURGING before T0 + 24h
        MediaVideoHlsPackage legacyFailed = MediaVideoHlsPackage.startProcessing(
                PKG_ID, ASSET_ID, VERSION_ID, 1, PKG_UUID, 1,
                StorageProviderId.of("r2-hls"), "hls-bucket",
                StorageKey.of("objects/hls/" + ASSET_ID + "/v1/" + PKG_UUID + "/"),
                6, T0
        );
        // PROCESSING is never purge-eligible
        assertThat(legacyFailed.isPurgeEligibleAt(T0.plus(Duration.ofDays(30)))).isFalse();
        assertThatThrownBy(() -> legacyFailed.claimForPurge(T0.plus(Duration.ofDays(30)).plusSeconds(60), T0.plus(Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class);

        Instant failTime = T0.plusSeconds(5);
        legacyFailed.markFailed("Encoder crashed on HDR", failTime, failTime); // legacy immediate purgeAfter = failTime
        assertThat(legacyFailed.isPurgeEligibleAt(failTime)).isFalse();
        assertThat(legacyFailed.isPurgeEligibleAt(failTime.plus(Duration.ofHours(24)).minusSeconds(1))).isFalse();
        assertThatThrownBy(() -> legacyFailed.claimForPurge(
                failTime.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(5)),
                failTime.plus(Duration.ofHours(23))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(legacyFailed.isPurgeEligibleAt(failTime.plus(Duration.ofHours(24)))).isTrue();

        // 2. ABANDONED package (legacy purgeAfter = abandonTime) also enforces 24h floor, while 7d stays >= 7d
        MediaVideoHlsPackage legacyAbandoned = MediaVideoHlsPackage.startProcessing(
                UUID.randomUUID(), ASSET_ID, VERSION_ID, 1, UUID.randomUUID(), 2,
                StorageProviderId.of("r2-hls"), "hls-bucket",
                StorageKey.of("objects/hls/" + ASSET_ID + "/v1/" + UUID.randomUUID() + "/"),
                6, T0
        );
        Instant abandonTime = T0.plusSeconds(10);
        legacyAbandoned.markAbandoned("Reclaimed", abandonTime, abandonTime);
        assertThat(legacyAbandoned.isPurgeEligibleAt(abandonTime.plus(Duration.ofHours(24)).minusNanos(1))).isFalse();
        assertThat(legacyAbandoned.isPurgeEligibleAt(abandonTime.plus(Duration.ofHours(24)))).isTrue();

        // 3. SUPERSEDED missing supersededAt fails closed cleanly
        UUID supUuid = UUID.randomUUID();
        MediaVideoHlsPackage corruptSuperseded = MediaVideoHlsPackage.rehydrate(
                UUID.randomUUID(), ASSET_ID, VERSION_ID, 1, supUuid, 3,
                StorageProviderId.of("r2-hls"), "hls-bucket",
                StorageKey.of("objects/hls/" + ASSET_ID + "/v1/" + supUuid + "/"),
                StorageKey.of("objects/hls/" + ASSET_ID + "/v1/" + supUuid + "/master.m3u8"),
                7, "FMP4", 6, null, null, null, null,
                MediaVideoHlsPackageStatus.SUPERSEDED, null,
                T0, null, null, null, T0.plusSeconds(5), T0.plusSeconds(5), 1L
        );
        assertThat(corruptSuperseded.effectivePurgeEligibleAt()).isEmpty();
        assertThat(corruptSuperseded.isPurgeEligibleAt(T0.plus(Duration.ofDays(30)))).isFalse();

        // 4. READY can never enter PURGING
        MediaVideoHlsPackage readyPkg = MediaVideoHlsPackage.startProcessing(
                UUID.randomUUID(), ASSET_ID, VERSION_ID, 1, UUID.randomUUID(), 4,
                StorageProviderId.of("r2-hls"), "hls-bucket",
                StorageKey.of("objects/hls/" + ASSET_ID + "/v1/" + UUID.randomUUID() + "/"),
                6, T0
        );
        readyPkg.markReady(SHA256, 4096L, 5, "[{\"height\":720}]", T0.plusSeconds(2), T0.plusSeconds(3));
        assertThat(readyPkg.isPurgeEligibleAt(T0.plus(Duration.ofDays(30)))).isFalse();
        assertThatThrownBy(() -> readyPkg.claimForPurge(T0.plus(Duration.ofDays(30)).plusSeconds(60), T0.plus(Duration.ofDays(30))))
                .isInstanceOf(IllegalStateException.class);

        // 5. Valid initial claimForPurge + non-expired steal rejection + invalid timestamps rejected
        Instant claimAt = failTime.plus(Duration.ofHours(24));
        Instant claimExpires = claimAt.plus(Duration.ofMinutes(5));
        assertThatThrownBy(() -> legacyFailed.claimForPurge(claimAt, claimAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacyFailed.claimForPurge(T0.plusSeconds(1), T0.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        StorageKey originalPrefix = legacyFailed.getBasePrefix();
        UUID originalPkgUuid = legacyFailed.getPackageUuid();
        legacyFailed.claimForPurge(claimExpires, claimAt);
        assertThat(legacyFailed.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(legacyFailed.getPurgeAfter()).isEqualTo(claimExpires);
        assertThat(legacyFailed.getUpdatedAt()).isEqualTo(claimAt);
        assertThat(legacyFailed.getBasePrefix()).isEqualTo(originalPrefix);
        assertThat(legacyFailed.getPackageUuid()).isEqualTo(originalPkgUuid);

        // Non-expired PURGING cannot be stolen
        Instant midClaim = claimAt.plus(Duration.ofMinutes(2));
        assertThat(legacyFailed.isPurgeClaimActiveAt(midClaim)).isTrue();
        assertThat(legacyFailed.isPurgeEligibleAt(midClaim)).isFalse();
        assertThatThrownBy(() -> legacyFailed.claimForPurge(midClaim.plus(Duration.ofMinutes(5)), midClaim))
                .isInstanceOf(IllegalStateException.class);

        // 6. Valid failure backoff preserves original diagnostic and bumps retry purgeAfter without reapplying 24h floor
        Instant retryAt = midClaim.plus(Duration.ofMinutes(15));
        assertThatThrownBy(() -> legacyFailed.recordPurgeFailure("zero-backoff", midClaim, midClaim))
                .isInstanceOf(IllegalArgumentException.class);
        legacyFailed.recordPurgeFailure("R2 503 DeleteObjects", retryAt, midClaim);
        assertThat(legacyFailed.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(legacyFailed.getPurgeAfter()).isEqualTo(retryAt);
        assertThat(legacyFailed.getFailureReason()).startsWith("Encoder crashed on HDR");
        assertThat(legacyFailed.getFailureReason()).contains("R2 503 DeleteObjects");

        // Second purge failure replaces previous [purge-error: ...] cleanly while preserving original reason
        Instant retryAt2 = midClaim.plus(Duration.ofMinutes(30));
        legacyFailed.recordPurgeFailure("R2 Timeout", retryAt2, midClaim.plusSeconds(5));
        assertThat(legacyFailed.getFailureReason()).isEqualTo("Encoder crashed on HDR | [purge-error: R2 Timeout]");

        // 7. Reclaim once retryAt2 expires -> completes cleanly -> PURGED clears purgeAfter and cannot be reclaimed
        assertThat(legacyFailed.isPurgeClaimActiveAt(retryAt2)).isFalse();
        assertThatThrownBy(() -> legacyFailed.recordPurgeFailure("too-late", retryAt2.plusSeconds(60), retryAt2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> legacyFailed.markPurged(retryAt2))
                .isInstanceOf(IllegalStateException.class);

        Instant reclaimAt = retryAt2.plusSeconds(1);
        Instant reclaimExpires = reclaimAt.plus(Duration.ofMinutes(5));
        legacyFailed.claimForPurge(reclaimExpires, reclaimAt);
        assertThat(legacyFailed.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);
        assertThat(legacyFailed.getPurgeAfter()).isEqualTo(reclaimExpires);

        // 8. Domain claimForPurge & recordPurgeFailure enforce bounded lease/backoff limits and overflow guards directly
        assertThatThrownBy(() -> MediaVideoHlsPackage.validatePurgeClaimDuration(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MediaVideoHlsPackage.validatePurgeClaimDuration(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaVideoHlsPackage.validatePurgeClaimDuration(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaVideoHlsPackage.validatePurgeClaimDuration(Duration.ofNanos(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaVideoHlsPackage.validatePurgeClaimDuration(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaVideoHlsPackage.validatePurgeClaimDuration(
                MediaVideoHlsPackage.MAX_PURGE_CLAIM_LEASE.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaVideoHlsPackage.computePurgeClaimExpiresAt(
                Instant.MAX.minusSeconds(10), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacyFailed.recordPurgeFailure(
                "direct-excessive-backoff",
                reclaimAt.plusSeconds(1).plus(MediaVideoHlsPackage.MAX_PURGE_RETRY_BACKOFF).plusSeconds(1),
                reclaimAt.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> legacyFailed.recordPurgeFailure(
                "direct-overflow",
                Instant.MAX,
                reclaimAt.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        Instant purgedAt = reclaimAt.plusSeconds(2);
        legacyFailed.markPurged(purgedAt);
        assertThat(legacyFailed.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
        assertThat(legacyFailed.getPurgeAfter()).isNull();
        assertThat(legacyFailed.getUpdatedAt()).isEqualTo(purgedAt);
        assertThat(legacyFailed.isPurgeEligibleAt(purgedAt.plus(Duration.ofDays(365)))).isFalse();
        assertThatThrownBy(() -> legacyFailed.claimForPurge(purgedAt.plusSeconds(60), purgedAt))
                .isInstanceOf(IllegalStateException.class);

        // 9. Direct domain claimForPurge rejects micro-leases (< 1s DATETIME(6)-safe min) and excessive leases (> 30m)
        MediaVideoHlsPackage boundTestPkg = MediaVideoHlsPackage.startProcessing(
                UUID.randomUUID(), ASSET_ID, VERSION_ID, 1, UUID.randomUUID(), 5,
                StorageProviderId.of("r2-hls"), "hls-bucket",
                StorageKey.of("objects/hls/" + ASSET_ID + "/v1/" + UUID.randomUUID() + "/"),
                6, T0
        );
        boundTestPkg.markFailed("fail", T0, T0);
        Instant eligibleNow = T0.plus(Duration.ofHours(24));
        assertThatThrownBy(() -> boundTestPkg.claimForPurge(eligibleNow.plusNanos(500), eligibleNow))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> boundTestPkg.claimForPurge(eligibleNow.plusMillis(999), eligibleNow))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> boundTestPkg.claimForPurge(
                eligibleNow.plus(MediaVideoHlsPackage.MAX_PURGE_CLAIM_LEASE).plusSeconds(1), eligibleNow))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> boundTestPkg.claimForPurge(Instant.MAX, eligibleNow))
                .isInstanceOf(IllegalArgumentException.class);
        // Min (1s) and Max (30m) succeed cleanly
        boundTestPkg.claimForPurge(eligibleNow.plus(MediaVideoHlsPackage.MIN_PURGE_CLAIM_LEASE), eligibleNow);
        assertThat(boundTestPkg.getPurgeAfter()).isEqualTo(eligibleNow.plus(MediaVideoHlsPackage.MIN_PURGE_CLAIM_LEASE));
    }

    @Test
    @DisplayName("MediaVideoPackagingJob increments leaseGeneration monotonically and rejects stale owners/generations")
    void shouldEnforceMonotonicFencingAndRetryLifecycle() {
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                JOB_ID,
                ASSET_ID,
                VERSION_ID,
                1,
                2,
                T0
        );

        assertThat(job.isClaimableAt(T0)).isTrue();
        UUID pkg1 = UUID.randomUUID();
        long gen1 = job.claimOrReclaimLease("worker-a", pkg1, Duration.ofMinutes(5), T0);
        assertThat(gen1).isEqualTo(1L);
        assertThat(job.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.LEASED);
        assertThat(job.isClaimableAt(T0.plusSeconds(60))).isFalse();

        // Stale generation completion rejected
        assertThatThrownBy(() -> job.markSucceeded("worker-a", 0L, pkg1, T0.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);

        // Wrong worker completion rejected
        assertThatThrownBy(() -> job.markSucceeded("worker-b", gen1, pkg1, T0.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);

        // After lease expiry, worker-b can reclaim at generation 2
        Instant afterExpiry = T0.plus(Duration.ofMinutes(6));
        assertThat(job.isClaimableAt(afterExpiry)).isTrue();
        UUID pkg2 = UUID.randomUUID();
        long gen2 = job.claimOrReclaimLease("worker-b", pkg2, Duration.ofMinutes(5), afterExpiry);
        assertThat(gen2).isEqualTo(2L);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.hasExhaustedAttempts()).isTrue();

        // Worker-a with stale gen1 cannot fail or succeed
        assertThatThrownBy(() -> job.recordAttemptFailure("worker-a", gen1, "boom", Duration.ofSeconds(30), afterExpiry))
                .isInstanceOf(IllegalStateException.class);

        // Worker-b with gen2 succeeds before expiry
        job.markSucceeded("worker-b", gen2, pkg2, afterExpiry.plusSeconds(10));
        assertThat(job.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("MediaVideoPackagingJob enforces exact leaseExpiresAt > now validity boundary and reclaim symmetry")
    void shouldEnforceExactLeaseExpiryBoundaryInDomain() {
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                JOB_ID,
                ASSET_ID,
                VERSION_ID,
                1,
                3,
                T0
        );
        UUID pkg1 = UUID.randomUUID();
        long gen1 = job.claimOrReclaimLease("worker-a", pkg1, Duration.ofMinutes(5), T0);
        Instant expiresAt = T0.plus(Duration.ofMinutes(5));

        // 1 microsecond before expiry: lease is active, not claimable
        Instant justBefore = expiresAt.minusNanos(1000);
        assertThat(job.isLeaseActiveAt(justBefore)).isTrue();
        assertThat(job.isClaimableAt(justBefore)).isFalse();

        // Exactly at expiry: lease is inactive AND immediately reclaimable
        assertThat(job.isLeaseActiveAt(expiresAt)).isFalse();
        assertThat(job.isClaimableAt(expiresAt)).isTrue();

        assertThatThrownBy(() -> job.markSucceeded("worker-a", gen1, pkg1, expiresAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lease expired");
        assertThatThrownBy(() -> job.recordAttemptFailure("worker-a", gen1, "late failure", Duration.ofSeconds(10), expiresAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lease expired");

        // After expiry: also inactive & claimable, completion & failure remain rejected
        Instant after = expiresAt.plusSeconds(1);
        assertThat(job.isLeaseActiveAt(after)).isFalse();
        assertThat(job.isClaimableAt(after)).isTrue();
        assertThatThrownBy(() -> job.markSucceeded("worker-a", gen1, pkg1, after))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lease expired");

        // Reclaim at exact expiry boundary increments generation cleanly
        UUID pkg2 = UUID.randomUUID();
        long gen2 = job.claimOrReclaimLease("worker-b", pkg2, Duration.ofMinutes(5), expiresAt);
        assertThat(gen2).isEqualTo(2L);
        assertThat(job.isLeaseActiveAt(expiresAt.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("MediaVideoPackagingJob enforces fenced permanent failure even when attempts remain, while preserving attemptCount and leaseGeneration")
    void shouldEnforceFencedPermanentFailureAndTransientRetentionHelpers() {
        assertThat(MediaVideoHlsPackage.computeFailedPurgeAfter(T0)).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(MediaVideoHlsPackage.computeAbandonedOnReclaimPurgeAfter(T0)).isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(MediaVideoHlsPackage.computeSupersededPurgeAfter(T0)).isEqualTo(T0.plus(Duration.ofDays(7)));

        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                JOB_ID,
                ASSET_ID,
                VERSION_ID,
                1,
                3,
                T0
        );
        UUID pkg1 = UUID.randomUUID();
        long gen1 = job.claimOrReclaimLease("worker-a", pkg1, Duration.ofMinutes(5), T0);
        Instant expiresAt = T0.plus(Duration.ofMinutes(5));

        // 1. Transient failure while attempts remain schedules PENDING retry with backoff
        Instant t1 = T0.plusSeconds(10);
        job.recordAttemptFailure("worker-a", gen1, "transient network reset", Duration.ofSeconds(30), t1);
        assertThat(job.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.PENDING);
        assertThat(job.getNextAttemptAt()).isEqualTo(t1.plusSeconds(30));
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();

        // Claim attempt 2 (still < maxAttempts=3)
        Instant t2 = t1.plusSeconds(35);
        UUID pkg2 = UUID.randomUUID();
        long gen2 = job.claimOrReclaimLease("worker-b", pkg2, Duration.ofMinutes(5), t2);
        assertThat(gen2).isEqualTo(2L);
        assertThat(job.getAttemptCount()).isEqualTo(2);

        // 2. Permanent failure rejected for wrong worker, stale generation, and expired lease
        Instant tFail = t2.plusSeconds(15);
        assertThatThrownBy(() -> job.recordPermanentFailure("worker-a", gen2, "unsupported HDR", tFail))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> job.recordPermanentFailure("worker-b", gen1, "unsupported HDR", tFail))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> job.recordPermanentFailure("worker-b", gen2, "unsupported HDR", t2.plus(Duration.ofMinutes(5))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lease expired");

        // Still active at tFail
        assertThat(job.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.LEASED);

        // 3. Valid worker & generation marks terminal FAILED immediately even though attemptCount (2) < maxAttempts (3)
        String longReason = "HDR10+ unsupported: " + "x".repeat(1200);
        job.recordPermanentFailure("worker-b", gen2, longReason, tFail);

        assertThat(job.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.FAILED);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getLeaseGeneration()).isEqualTo(2L);
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getUpdatedAt()).isEqualTo(tFail);
        assertThat(job.getLastError()).hasSize(1000);
        assertThat(job.isClaimableAt(tFail.plus(Duration.ofDays(30)))).isFalse();
        assertThat(job.isLeaseActiveAt(tFail)).isFalse();

        // 4. Final-attempt transient failure also becomes terminal FAILED
        MediaVideoPackagingJob singleAttemptJob = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(), ASSET_ID, VERSION_ID, 1, 1, T0
        );
        long sGen = singleAttemptJob.claimOrReclaimLease("worker-c", UUID.randomUUID(), Duration.ofMinutes(5), T0);
        singleAttemptJob.recordAttemptFailure("worker-c", sGen, "timeout", Duration.ofSeconds(10), T0.plusSeconds(5));
        assertThat(singleAttemptJob.getStatus()).isEqualTo(MediaVideoPackagingJobStatus.FAILED);
        assertThat(singleAttemptJob.getLeaseOwner()).isNull();
        assertThat(singleAttemptJob.getLeaseExpiresAt()).isNull();
    }
}
