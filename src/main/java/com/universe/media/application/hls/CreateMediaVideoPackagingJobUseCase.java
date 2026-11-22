package com.universe.media.application.hls;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaVideoHlsPackageRepositoryPort;
import com.universe.media.application.ports.MediaVideoPackagingJobRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVideoPackagingJob;
import com.universe.media.domain.MediaVideoPackagingJobStatus;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enqueues a PENDING HLS packaging job for the authoritative current version of an ACTIVE VIDEO asset.
 * Enforces Global Lock Order: MediaAsset (FOR UPDATE) -> MediaVideoPackagingJob.
 */
@Service
public class CreateMediaVideoPackagingJobUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateMediaVideoPackagingJobUseCase.class);

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort;
    private final MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort;
    private final ClockPort clockPort;

    @Autowired
    public CreateMediaVideoPackagingJobUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            ClockPort clockPort
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                packagingJobRepositoryPort,
                Objects.requireNonNull(hlsPackageRepositoryPort, "MediaVideoHlsPackageRepositoryPort cannot be null."),
                clockPort,
                true
        );
    }

    public CreateMediaVideoPackagingJobUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            ClockPort clockPort
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                packagingJobRepositoryPort,
                null,
                clockPort,
                false
        );
    }

    private CreateMediaVideoPackagingJobUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaVideoPackagingJobRepositoryPort packagingJobRepositoryPort,
            MediaVideoHlsPackageRepositoryPort hlsPackageRepositoryPort,
            ClockPort clockPort,
            boolean strictPackageWiring
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null.");
        this.packagingJobRepositoryPort = Objects.requireNonNull(packagingJobRepositoryPort, "MediaVideoPackagingJobRepositoryPort cannot be null.");
        this.hlsPackageRepositoryPort = strictPackageWiring
                ? Objects.requireNonNull(hlsPackageRepositoryPort, "MediaVideoHlsPackageRepositoryPort cannot be null.")
                : hlsPackageRepositoryPort;
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
    }

    @Transactional
    public MediaVideoPackagingJob execute(UUID assetId, int maxAttempts) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }

        // STEP 1: MediaAsset lock FIRST
        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException("Cannot create HLS packaging job for non-ACTIVE asset [" + assetId + "]: " + asset.getStatus());
        }
        if (asset.getMediaType() != MediaType.VIDEO) {
            throw new IllegalArgumentException("HLS packaging is supported only for VIDEO assets, found: " + asset.getMediaType());
        }

        int currentVersionNumber = asset.getCurrentVersionNumber();
        MediaAssetVersion currentVersion = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, currentVersionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, currentVersionNumber));

        // STEP 2: Re-use existing active job idempotently if present
        Optional<MediaVideoPackagingJob> existingActive =
                packagingJobRepositoryPort.findActiveBySourceVersionIdForUpdate(currentVersion.getId());
        if (existingActive.isPresent()) {
            return existingActive.get();
        }

        Instant now = clockPort.now();
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(),
                assetId,
                currentVersion.getId(),
                currentVersionNumber,
                maxAttempts,
                now
        );

        return packagingJobRepositoryPort.save(job);
    }

    @Transactional
    public MediaVideoPackagingJob execute(UUID assetId) {
        return execute(assetId, MediaVideoPackagingJob.DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Version-fenced automatic enqueue entry point for post-commit Media VIDEO upload and
     * source-version replacement events. Never overwrites newer authoritative versions and skips
     * when READY packages or terminal jobs already exist for the target source version.
     */
    @Transactional
    public AutoPackagingEnqueueResult executeIfEligibleForVersion(
            UUID assetId,
            int expectedVersionNumber,
            int maxAttempts
    ) {
        return executeIfEligibleForVersion(assetId, expectedVersionNumber, null, maxAttempts);
    }

    /**
     * Version-fenced automatic enqueue entry point validating both authoritative version number
     * and optional version UUID before creating at most one PENDING packaging job.
     */
    @Transactional
    public AutoPackagingEnqueueResult executeIfEligibleForVersion(
            UUID assetId,
            int expectedVersionNumber,
            UUID expectedVersionId,
            int maxAttempts
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        if (expectedVersionNumber < 1) {
            throw new IllegalArgumentException("expectedVersionNumber must be >= 1: " + expectedVersionNumber);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        return executeAutoEnqueue(assetId, expectedVersionNumber, expectedVersionId, maxAttempts);
    }

    /**
     * Authoritative-current-version automatic enqueue entry point for restore events when
     * explicitly enabled. Applies the exact same READY, active-job, and terminal-job guards.
     */
    @Transactional
    public AutoPackagingEnqueueResult executeIfEligibleForCurrentVersion(
            UUID assetId,
            int maxAttempts
    ) {
        Objects.requireNonNull(assetId, "Asset ID cannot be null.");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        return executeAutoEnqueue(assetId, null, null, maxAttempts);
    }

    private AutoPackagingEnqueueResult executeAutoEnqueue(
            UUID assetId,
            Integer expectedVersionNumber,
            UUID expectedVersionId,
            int maxAttempts
    ) {
        if (hlsPackageRepositoryPort == null) {
            throw new IllegalStateException(
                    "Cannot evaluate automatic HLS packaging eligibility for asset [" + assetId
                            + "]: required MediaVideoHlsPackageRepositoryPort is unavailable (fail-closed)."
            );
        }

        // STEP 1: Acquire MediaAsset FOR UPDATE FIRST (Global Lock Order)
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findByIdForUpdate(assetId);
        if (assetOpt.isEmpty()) {
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
        }

        MediaAsset asset = assetOpt.get();
        if (asset.getStatus() != MediaAssetStatus.ACTIVE || asset.getMediaType() != MediaType.VIDEO) {
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
        }

        int currentVersionNumber = asset.getCurrentVersionNumber();
        if (expectedVersionNumber != null && currentVersionNumber != expectedVersionNumber) {
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_STALE_VERSION);
        }

        MediaAssetVersion currentVersion = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, currentVersionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, currentVersionNumber));

        if (expectedVersionId != null && !expectedVersionId.equals(currentVersion.getId())) {
            log.warn("Expected MediaAssetVersion UUID mismatch during HLS auto-enqueue: assetId={}, versionNumber={}, expectedVersionId={}, actualVersionId={}",
                    assetId, currentVersionNumber, expectedVersionId, currentVersion.getId());
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_STALE_VERSION);
        }

        // STEP 2: Deduplicate against READY package first (unique on source_version_id + active_ready_flag)
        if (hlsPackageRepositoryPort.findReadyBySourceVersionId(currentVersion.getId()).isPresent()) {
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_READY);
        }

        // STEP 3: Reuse active PENDING/LEASED job idempotently (locked on PackagingJob)
        Optional<MediaVideoPackagingJob> existingActive =
                packagingJobRepositoryPort.findActiveBySourceVersionIdForUpdate(currentVersion.getId());
        if (existingActive.isPresent()) {
            return AutoPackagingEnqueueResult.reusedActive(existingActive.get());
        }

        // STEP 4: Conservative terminal-job guard (SUCCEEDED / FAILED / CANCELLED)
        Optional<MediaVideoPackagingJobStatus> terminalStatus =
                packagingJobRepositoryPort.findHighestPriorityTerminalStatusBySourceVersionId(currentVersion.getId());
        if (terminalStatus.isPresent()) {
            if (terminalStatus.get() == MediaVideoPackagingJobStatus.SUCCEEDED) {
                log.warn("Inconsistent HLS state detected during auto-enqueue: SUCCEEDED job exists without READY package for assetId={}, sourceVersionId={}, versionNumber={}. Skipping automatic enqueue for reconciliation.",
                        assetId, currentVersion.getId(), currentVersionNumber);
            }
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_TERMINAL_JOB);
        }

        Instant now = clockPort.now();
        MediaVideoPackagingJob job = MediaVideoPackagingJob.createPending(
                UUID.randomUUID(),
                assetId,
                currentVersion.getId(),
                currentVersionNumber,
                maxAttempts,
                now
        );

        MediaVideoPackagingJob saved = packagingJobRepositoryPort.save(job);
        return AutoPackagingEnqueueResult.created(saved);
    }
}
