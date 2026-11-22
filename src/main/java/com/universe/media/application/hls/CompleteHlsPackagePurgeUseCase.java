package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StalePurgeClaimException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Fenced completion and failure-backoff transitions for claimed {@code PURGING} HLS packages
 * enforcing Global Lock Order: {@code MediaAsset (FOR UPDATE) -> MediaVideoHlsPackage (FOR UPDATE)}
 * and validating exact persisted {@code persistence_version} ownership (MS-08 HLS-2B3A2B).
 *
 * <p><strong>Required Future HLS-2B3C Contract:</strong> Future remote purge orchestrators must call
 * {@link #markPurged(ClaimedHlsPackagePurgeLease)} strictly OUTSIDE any outer transaction and
 * ONLY after {@code HlsPackageArtifactStoragePort.purgePackagePrefix(...)} succeeds and confirms
 * that the exact remote prefix is empty.
 */
@Service
public class CompleteHlsPackagePurgeUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;

    public CompleteHlsPackagePurgeUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.hlsPackageRepositoryPort = Objects.requireNonNull(
                hlsPackageRepositoryPort,
                "MediaVideoHlsPackageRepositoryPort cannot be null."
        );
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaVideoHlsPackage markPurged(ClaimedHlsPackagePurgeLease lease) {
        Objects.requireNonNull(lease, "ClaimedHlsPackagePurgeLease cannot be null.");
        LockedClaimContext locked = lockAndValidateActiveClaim(lease);
        locked.pkg().markPurged(locked.lockedNow());
        return hlsPackageRepositoryPort.save(locked.pkg());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MediaVideoHlsPackage recordFailure(
            ClaimedHlsPackagePurgeLease lease,
            String errorReason,
            Duration retryBackoff
    ) {
        Objects.requireNonNull(lease, "ClaimedHlsPackagePurgeLease cannot be null.");
        Duration effectiveBackoff = clampRetryBackoff(retryBackoff);
        LockedClaimContext locked = lockAndValidateActiveClaim(lease);
        Instant nextPurgeAfter;
        try {
            nextPurgeAfter = locked.lockedNow().plus(effectiveBackoff);
        } catch (ArithmeticException | DateTimeException ex) {
            throw new IllegalArgumentException("Purge retry deadline overflows Instant bounds.", ex);
        }
        locked.pkg().recordPurgeFailure(errorReason, nextPurgeAfter, locked.lockedNow());
        return hlsPackageRepositoryPort.save(locked.pkg());
    }

    public static Duration clampRetryBackoff(Duration requestedBackoff) {
        Objects.requireNonNull(requestedBackoff, "retryBackoff cannot be null.");
        if (requestedBackoff.isNegative() || requestedBackoff.isZero()) {
            throw new IllegalArgumentException(
                    "retryBackoff must be strictly positive (zero/negative retry backoff risks tight loops): "
                            + requestedBackoff
            );
        }
        return requestedBackoff.compareTo(MediaVideoHlsPackage.MAX_PURGE_RETRY_BACKOFF) > 0
                ? MediaVideoHlsPackage.MAX_PURGE_RETRY_BACKOFF
                : requestedBackoff;
    }

    private LockedClaimContext lockAndValidateActiveClaim(ClaimedHlsPackagePurgeLease lease) {
        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(lease.assetId())
                .orElseThrow(() -> new MediaAssetNotFoundException(lease.assetId()));

        // STEP 2: HLS Package lock SECOND
        MediaVideoHlsPackage pkg = hlsPackageRepositoryPort.findByIdForUpdate(lease.packageId())
                .orElseThrow(() -> new StalePurgeClaimException(
                        "HLS package [" + lease.packageId() + "] no longer exists for purge completion/failure."
                ));

        if (!asset.getId().equals(pkg.getAssetId())
                || !pkg.getId().equals(lease.packageId())
                || !pkg.getSourceVersionId().equals(lease.sourceVersionId())
                || pkg.getSourceVersionNumber() != lease.sourceVersionNumber()
                || !pkg.getPackageUuid().equals(lease.packageUuid())
                || !pkg.getStorageProviderId().equals(lease.storageProviderId())
                || !pkg.getStorageBucketName().equals(lease.storageBucketName())
                || !pkg.getBasePrefix().equals(lease.basePrefix())) {
            throw new StalePurgeClaimException(
                    "Purge lease identity mismatch for HLS package [" + lease.packageId() + "]."
            );
        }

        if (pkg.getStatus() != MediaVideoHlsPackageStatus.PURGING) {
            throw new StalePurgeClaimException(
                    "HLS package [" + lease.packageId() + "] is no longer PURGING (status=" + pkg.getStatus() + ")."
            );
        }

        Long persistedVersion = pkg.getPersistenceVersion();
        if (persistedVersion == null || persistedVersion.longValue() != lease.claimVersion()) {
            throw new StalePurgeClaimException(
                    "Stale purge claim version for HLS package [" + lease.packageId() + "]: expected "
                            + lease.claimVersion() + ", actual " + persistedVersion + "."
            );
        }

        // Capture authoritative time STRICTLY AFTER acquiring both DB row locks
        Instant lockedNow = clockPort.now();
        if (!pkg.isPurgeClaimActiveAt(lockedNow)) {
            throw new StalePurgeClaimException(
                    "Expired purge claim for HLS package [" + lease.packageId() + "] (purgeAfter="
                            + pkg.getPurgeAfter() + ", lockedNow=" + lockedNow + ")."
            );
        }

        return new LockedClaimContext(pkg, lockedNow);
    }

    private record LockedClaimContext(MediaVideoHlsPackage pkg, Instant lockedNow) {
    }
}
