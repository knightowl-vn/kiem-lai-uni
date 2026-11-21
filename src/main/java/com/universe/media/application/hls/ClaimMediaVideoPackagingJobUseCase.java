package com.universe.media.application.hls;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobCandidate;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVideoHlsPackage;
import com.universe.media.domain.MediaVideoHlsPackageStatus;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
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
import java.util.UUID;

/**
 * Claims or reclaims one eligible HLS packaging job using a bounded lock-free candidate scan
 * followed by strict Global Lock Order per candidate:
 * MediaAsset (FOR UPDATE) -> MediaVideoPackagingJob (FOR UPDATE) -> MediaVideoHlsPackage.
 */
@Service
public class ClaimMediaVideoPackagingJobUseCase {

    public static final int DEFAULT_CANDIDATE_BATCH_SIZE = 10;
    public static final int DEFAULT_SEGMENT_DURATION_SECONDS = 6;

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimMediaVideoPackagingJobUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.packagingJobRepositoryPort = Objects.requireNonNull(packagingJobRepositoryPort, "MediaVideoPackagingJobRepositoryPort cannot be null.");
        this.hlsPackageRepositoryPort = Objects.requireNonNull(hlsPackageRepositoryPort, "MediaVideoHlsPackageRepositoryPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        Objects.requireNonNull(transactionManager, "PlatformTransactionManager cannot be null.");
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<ClaimedVideoPackagingLease> claimNext(
            String workerId,
            Duration leaseDuration,
            StorageProviderId outputProviderId,
            String outputBucketName
    ) {
        Objects.requireNonNull(workerId, "workerId cannot be null.");
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("workerId cannot be blank.");
        }
        Objects.requireNonNull(leaseDuration, "leaseDuration cannot be null.");
        Objects.requireNonNull(outputProviderId, "outputProviderId cannot be null.");
        if (outputBucketName == null || outputBucketName.isBlank()) {
            throw new IllegalArgumentException("outputBucketName cannot be blank.");
        }

        Instant scanNow = clockPort.now();
        List<MediaVideoPackagingJobCandidate> candidates =
                packagingJobRepositoryPort.findClaimableCandidates(scanNow, DEFAULT_CANDIDATE_BATCH_SIZE);

        for (MediaVideoPackagingJobCandidate candidate : candidates) {
            Optional<ClaimedVideoPackagingLease> claimed = requiresNewTransactionTemplate.execute(status ->
                    tryClaimCandidateInTransaction(
                            candidate,
                            workerId.trim(),
                            leaseDuration,
                            outputProviderId,
                            outputBucketName.trim()
                    )
            );
            if (claimed != null && claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    private Optional<ClaimedVideoPackagingLease> tryClaimCandidateInTransaction(
            MediaVideoPackagingJobCandidate candidate,
            String workerId,
            Duration leaseDuration,
            StorageProviderId outputProviderId,
            String outputBucketName
    ) {
        Instant now = clockPort.now();

        // STEP 1: MediaAsset lock FIRST (eliminates implicit InnoDB FK S-lock cycle on Package INSERT)
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findByIdForUpdate(candidate.assetId());
        if (assetOpt.isEmpty()) {
            return Optional.empty();
        }
        MediaAsset asset = assetOpt.get();

        // STEP 2: Job lock SECOND
        Optional<MediaVideoPackagingJob> jobOpt = packagingJobRepositoryPort.findByIdForUpdate(candidate.jobId());
        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }
        MediaVideoPackagingJob job = jobOpt.get();

        if (!job.isClaimableAt(now)) {
            return Optional.empty();
        }

        // Source validity gate
        if (asset.getStatus() != MediaAssetStatus.ACTIVE
                || asset.getCurrentVersionNumber() != job.getSourceVersionNumber()) {
            abandonPreviousAttemptPackageIfProcessing(job, "Asset inactive or source version superseded.", now);
            job.cancel("Asset inactive or source version superseded.", now);
            packagingJobRepositoryPort.save(job);
            return Optional.empty();
        }

        // Attempt exhaustion gate (expired lease on last allowed attempt)
        if (job.hasExhaustedAttempts()) {
            abandonPreviousAttemptPackageIfProcessing(job, "Expired lease on final allowed attempt.", now);
            job.markExhaustedFailure("Lease expired after maximum attempts (" + job.getMaxAttempts() + ").", now);
            packagingJobRepositoryPort.save(job);
            return Optional.empty();
        }

        // STEP 3: Package lock THIRD — abandon old PROCESSING attempt on lease reclaim, then insert isolated new attempt
        abandonPreviousAttemptPackageIfProcessing(job, "Reclaimed after lease expiry.", now);

        UUID attemptPackageId = UUID.randomUUID();
        UUID packageUuid = UUID.randomUUID();
        int nextAttemptNumber = job.getAttemptCount() + 1;
        String basePrefix = "objects/hls/" + job.getAssetId()
                + "/v" + job.getSourceVersionNumber()
                + "/" + packageUuid + "/";

        MediaVideoHlsPackage attemptPackage = MediaVideoHlsPackage.startProcessing(
                attemptPackageId,
                job.getAssetId(),
                job.getSourceVersionId(),
                job.getSourceVersionNumber(),
                packageUuid,
                nextAttemptNumber,
                outputProviderId,
                outputBucketName,
                StorageKey.of(basePrefix),
                DEFAULT_SEGMENT_DURATION_SECONDS,
                now
        );
        MediaVideoHlsPackage savedPackage = hlsPackageRepositoryPort.save(attemptPackage);

        long leaseGeneration = job.claimOrReclaimLease(workerId, attemptPackageId, leaseDuration, now);
        MediaVideoPackagingJob savedJob = packagingJobRepositoryPort.save(job);

        return Optional.of(new ClaimedVideoPackagingLease(
                savedJob.getId(),
                savedJob.getAssetId(),
                savedJob.getSourceVersionId(),
                savedJob.getSourceVersionNumber(),
                savedPackage.getId(),
                savedPackage.getPackageUuid(),
                leaseGeneration,
                savedJob.getAttemptCount(),
                savedPackage.getStorageProviderId(),
                savedPackage.getStorageBucketName(),
                savedPackage.getBasePrefix(),
                savedPackage.getMasterManifestKey(),
                savedJob.getLeaseExpiresAt()
        ));
    }

    private void abandonPreviousAttemptPackageIfProcessing(MediaVideoPackagingJob job, String reason, Instant now) {
        UUID previousPkgId = job.getCurrentPackageId();
        if (previousPkgId == null) {
            return;
        }
        hlsPackageRepositoryPort.findByIdForUpdate(previousPkgId).ifPresent(prevPkg -> {
            if (prevPkg.getStatus() == MediaVideoHlsPackageStatus.PROCESSING) {
                Instant purgeAfter = MediaVideoHlsPackage.computeAbandonedOnReclaimPurgeAfter(now);
                prevPkg.markAbandoned(reason, purgeAfter, now);
                hlsPackageRepositoryPort.save(prevPkg);
            }
        });
    }
}
