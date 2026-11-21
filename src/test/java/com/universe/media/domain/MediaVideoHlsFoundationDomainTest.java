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

        pkg.markPurging(T0.plusSeconds(9));
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGING);

        pkg.markPurged(T0.plusSeconds(10));
        assertThat(pkg.getStatus()).isEqualTo(MediaVideoHlsPackageStatus.PURGED);
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
}
