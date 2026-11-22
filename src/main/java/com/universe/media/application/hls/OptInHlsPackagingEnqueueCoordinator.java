package com.universe.media.application.hls;

import com.universe.media.domain.MediaVideoPackagingJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * Non-transactional coordinator that invokes opt-in HLS packaging job creation strictly
 * after the originating Media registration/restore transaction has committed.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Disabled-by-default short-circuit performs zero database queries and zero remote calls.</li>
 *   <li>Guards against ambient active transactions (never starts nested REQUIRES_NEW behind an outer lock).</li>
 *   <li>Isolates all runtime exceptions so an optional HLS enqueue failure never aborts committed MP4 availability.</li>
 * </ul>
 */
@Service
public class OptInHlsPackagingEnqueueCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OptInHlsPackagingEnqueueCoordinator.class);

    private final HlsAutoEnqueueProperties properties;
    private final CreateMediaVideoPackagingJobUseCase createMediaVideoPackagingJobUseCase;

    @Autowired
    public OptInHlsPackagingEnqueueCoordinator(
            HlsAutoEnqueueProperties properties,
            CreateMediaVideoPackagingJobUseCase createMediaVideoPackagingJobUseCase
    ) {
        this.properties = Objects.requireNonNull(properties, "HlsAutoEnqueueProperties cannot be null.");
        this.createMediaVideoPackagingJobUseCase = Objects.requireNonNull(
                createMediaVideoPackagingJobUseCase,
                "CreateMediaVideoPackagingJobUseCase cannot be null."
        );
    }

    /**
     * Safely evaluates and enqueues a PENDING packaging job for an uploaded or replaced VIDEO
     * source version when {@code media.hls.auto-enqueue.enabled=true} and no ambient transaction
     * is active.
     */
    public AutoPackagingEnqueueResult enqueueVideoVersionIfEnabledSafely(
            UUID assetId,
            int expectedVersionNumber,
            UUID expectedVersionId
    ) {
        if (!properties.isEnabled()) {
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_DISABLED);
        }
        if (assetId == null || expectedVersionNumber < 1) {
            log.warn("Skipping opt-in HLS auto-enqueue due to invalid version identity: assetId={}, expectedVersionNumber={}",
                    assetId, expectedVersionNumber);
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Skipping opt-in HLS auto-enqueue because an active database transaction is in progress: assetId={}, expectedVersionNumber={}",
                    assetId, expectedVersionNumber);
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_AMBIENT_TRANSACTION);
        }
        try {
            return createMediaVideoPackagingJobUseCase.executeIfEligibleForVersion(
                    assetId,
                    expectedVersionNumber,
                    expectedVersionId,
                    MediaVideoPackagingJob.DEFAULT_MAX_ATTEMPTS
            );
        } catch (RuntimeException ex) {
            log.warn("Opt-in HLS packaging auto-enqueue failed safely without rolling back Media commit: assetId={}, expectedVersionNumber={}, errorType={}",
                    assetId, expectedVersionNumber, ex.getClass().getSimpleName());
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_FAILED_ISOLATION);
        }
    }

    /**
     * Safely evaluates and enqueues a PENDING packaging job for the current authoritative version
     * of a restored VIDEO asset when both {@code media.hls.auto-enqueue.enabled=true} and
     * {@code media.hls.auto-enqueue.on-restore=true}.
     */
    public AutoPackagingEnqueueResult enqueueRestoredCurrentVersionIfEnabledSafely(UUID assetId) {
        if (!properties.isRestoreAutoEnqueueActive()) {
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_DISABLED);
        }
        if (assetId == null) {
            log.warn("Skipping opt-in HLS restore auto-enqueue due to null assetId.");
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_INELIGIBLE_ASSET);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("Skipping opt-in HLS restore auto-enqueue because an active database transaction is in progress: assetId={}",
                    assetId);
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_AMBIENT_TRANSACTION);
        }
        try {
            return createMediaVideoPackagingJobUseCase.executeIfEligibleForCurrentVersion(
                    assetId,
                    MediaVideoPackagingJob.DEFAULT_MAX_ATTEMPTS
            );
        } catch (RuntimeException ex) {
            log.warn("Opt-in HLS packaging restore auto-enqueue failed safely without rolling back Media restore: assetId={}, errorType={}",
                    assetId, ex.getClass().getSimpleName());
            return AutoPackagingEnqueueResult.skipped(AutoPackagingEnqueueStatus.SKIPPED_FAILED_ISOLATION);
        }
    }
}
