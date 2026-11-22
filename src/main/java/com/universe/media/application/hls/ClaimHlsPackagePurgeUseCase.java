package com.universe.media.application.hls;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackagePurgeCandidate;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Discovers purge-eligible HLS packages via a bounded lock-free scan and claims at most one
 * package per call using independent short {@code REQUIRES_NEW} transactions enforcing
 * Global Lock Order: {@code MediaAsset (FOR UPDATE) -> MediaVideoHlsPackage (FOR UPDATE)}
 * (MS-08 HLS-2B3A2B).
 *
 * <p><strong>Dormancy Rule:</strong> Performs strictly database claim coordination.
 * Executes zero remote R2 operations and schedules no background sweepers.
 */
@Service
public class ClaimHlsPackagePurgeUseCase {

    public static final int DEFAULT_CANDIDATE_BATCH_SIZE = 10;

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimHlsPackagePurgeUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
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
        Objects.requireNonNull(transactionManager, "PlatformTransactionManager cannot be null.");
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<ClaimedHlsPackagePurgeLease> claimNext(Duration claimDuration) {
        return claimNext(claimDuration, DEFAULT_CANDIDATE_BATCH_SIZE);
    }

    public Optional<ClaimedHlsPackagePurgeLease> claimNext(Duration claimDuration, int candidateBatchSize) {
        MediaVideoHlsPackage.validatePurgeClaimDuration(claimDuration);
        if (candidateBatchSize <= 0 || candidateBatchSize > 100) {
            throw new IllegalArgumentException("candidateBatchSize must be between 1 and 100: " + candidateBatchSize);
        }

        Instant scanNow = clockPort.now();
        List<MediaVideoHlsPackagePurgeCandidate> candidates =
                hlsPackageRepositoryPort.findPurgeEligibleCandidates(scanNow, candidateBatchSize);

        for (MediaVideoHlsPackagePurgeCandidate candidate : candidates) {
            Optional<ClaimedHlsPackagePurgeLease> claimed = requiresNewTransactionTemplate.execute(
                    status -> tryClaimCandidateInTransaction(candidate, claimDuration)
            );
            if (claimed != null && claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    private Optional<ClaimedHlsPackagePurgeLease> tryClaimCandidateInTransaction(
            MediaVideoHlsPackagePurgeCandidate candidate,
            Duration claimDuration
    ) {
        // STEP 1: MediaAsset lock FIRST (consistent parent-first Global Lock Order)
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findByIdForUpdate(candidate.assetId());
        if (assetOpt.isEmpty()) {
            return Optional.empty();
        }
        MediaAsset asset = assetOpt.get();

        // STEP 2: HLS Package lock SECOND
        Optional<MediaVideoHlsPackage> pkgOpt = hlsPackageRepositoryPort.findByIdForUpdate(candidate.packageId());
        if (pkgOpt.isEmpty()) {
            return Optional.empty();
        }
        MediaVideoHlsPackage pkg = pkgOpt.get();

        if (!asset.getId().equals(pkg.getAssetId())) {
            throw new IllegalStateException(
                    "Purge candidate asset mismatch for package [" + pkg.getId()
                            + "]: candidateAsset=" + asset.getId() + ", packageAsset=" + pkg.getAssetId()
            );
        }

        // Capture authoritative time STRICTLY AFTER acquiring both DB row locks
        Instant lockedNow = clockPort.now();

        // Revalidate eligibility strictly under locks (never trust stale lock-free scan timestamp)
        if (pkg.getStatus() == MediaVideoHlsPackageStatus.READY
                || pkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING
                || pkg.getStatus() == MediaVideoHlsPackageStatus.PURGED
                || !pkg.isPurgeEligibleAt(lockedNow)) {
            return Optional.empty();
        }

        Instant claimExpiresAt = MediaVideoHlsPackage.computePurgeClaimExpiresAt(lockedNow, claimDuration);
        pkg.claimForPurge(claimExpiresAt, lockedNow);
        MediaVideoHlsPackage saved = hlsPackageRepositoryPort.save(pkg);

        Long postClaimVersion = saved.getPersistenceVersion();
        if (postClaimVersion == null) {
            throw new IllegalStateException(
                    "Persisted HLS package [" + saved.getId() + "] returned null persistenceVersion after purge claim."
            );
        }

        return Optional.of(new ClaimedHlsPackagePurgeLease(
                saved.getId(),
                saved.getAssetId(),
                saved.getSourceVersionId(),
                saved.getSourceVersionNumber(),
                saved.getPackageUuid(),
                saved.getStorageProviderId(),
                saved.getStorageBucketName(),
                saved.getBasePrefix(),
                postClaimVersion,
                saved.getPurgeAfter()
        ));
    }
}
