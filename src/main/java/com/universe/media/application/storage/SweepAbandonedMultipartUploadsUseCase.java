package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Application use case for discovering, transitioning, and sweeping abandoned multipart upload sessions (MS-08E2-L3-2).
 *
 * <p><strong>Lifecycle Safety &amp; Two-Phase Orchestration:</strong>
 * <ol>
 *     <li>Phase 1 (Safe Stale Transition): Identifies active sessions (ALLOCATED or INITIATED) whose
 *         {@code expiresAt} has elapsed, transitioning each atomically via {@link MediaUploadSessionService#expireSession(java.util.UUID)}.
 *         Sessions in COMPLETING, COMPLETED, or with completion evidence are strictly protected. Expiration failures
 *         are tracked per-session in {@code expirationFailedCount}.</li>
 *     <li>Phase 2 (Guarded Abort Discovery &amp; Execution): Queries candidates in approved terminal/non-active lifecycle
 *         statuses (ABORTED or EXPIRED) strictly with {@link UploadSessionCleanupStatus#PENDING} cleanup status.
 *         Re-validates local eligibility before invoking {@link MediaUploadSessionService#executeGuardedMultipartAbort(java.util.UUID)}.
 *         Invocations are tracked in {@code attemptedCount}, successful quarantines in {@code quarantinedCount},
 *         and abort failures in {@code failedCount}. {@code verifiedCleanedCount} remains explicitly 0 until S2D-3.</li>
 * </ol>
 *
 * <p><strong>Failure Isolation &amp; Safe Logging:</strong>
 * Failures on any single session are caught, categorized safely (without logging raw exception messages which could
 * leak sensitive credentials, signatures, or presigned URLs), and isolated so other sessions in the batch proceed.
 *
 * <p><strong>Transaction Boundary:</strong>
 * This orchestrator is intentionally <em>NOT</em> {@code @Transactional}. No database transaction is held
 * across candidate discovery or remote provider storage operations.
 */
@Service
public class SweepAbandonedMultipartUploadsUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(SweepAbandonedMultipartUploadsUseCase.class);

    private static final Set<MediaUploadSessionStatus> CANDIDATE_LIFECYCLE_STATUSES =
            MediaUploadSessionRepositoryPort.APPROVED_CLEANUP_CANDIDATE_STATUSES;

    private static final Set<UploadSessionCleanupStatus> SWEEPER_CLEANUP_STATUSES =
            Set.of(UploadSessionCleanupStatus.PENDING);

    private final MediaUploadSessionRepositoryPort sessionRepositoryPort;
    private final MediaUploadSessionService sessionService;
    private final ClockPort clockPort;

    public SweepAbandonedMultipartUploadsUseCase(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            MediaUploadSessionService sessionService,
            ClockPort clockPort
    ) {
        this.sessionRepositoryPort = Objects.requireNonNull(
                sessionRepositoryPort,
                "MediaUploadSessionRepositoryPort cannot be null."
        );
        this.sessionService = Objects.requireNonNull(
                sessionService,
                "MediaUploadSessionService cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
    }

    public SweepAbandonedMultipartUploadsResult execute(SweepAbandonedMultipartUploadsCommand command) {
        Objects.requireNonNull(command, "SweepAbandonedMultipartUploadsCommand cannot be null.");

        Instant now = clockPort.now();
        int scannedCount = 0;
        int attemptedCount = 0;
        int skippedCount = 0;
        int quarantinedCount = 0;
        int verifiedCleanedCount = 0; // Explicitly 0: guarded abort yields QUARANTINED; verified physical cleanup requires S2D-3 reconciliation
        int failedCount = 0;
        int expirationFailedCount = 0;

        // Phase 1: Transition stale active sessions (ALLOCATED/INITIATED) past expiresAt
        List<MediaUploadSession> expiredActiveSessions = sessionRepositoryPort.findExpiredSessions(
                Set.of(MediaUploadSessionStatus.ALLOCATED, MediaUploadSessionStatus.INITIATED),
                now,
                command.batchSize()
        );
        for (MediaUploadSession activeSession : expiredActiveSessions) {
            try {
                sessionService.expireSession(activeSession.getId());
            } catch (Exception ex) {
                expirationFailedCount++;
                String errorCategory = classifySafeErrorCategory(ex);
                LOGGER.warn("Failed to expire stale upload session [ID: {}] due to error category: {}",
                        activeSession.getId(), errorCategory);
            }
        }

        // Phase 2: Candidate discovery for guarded abort (strictly PENDING cleanup status)
        Instant maxLastAttemptAt = (command.retryBackoff() != null)
                ? now.minus(command.retryBackoff())
                : null;

        List<MediaUploadSession> candidates = sessionRepositoryPort.findSessionsEligibleForCleanup(
                CANDIDATE_LIFECYCLE_STATUSES,
                SWEEPER_CLEANUP_STATUSES,
                command.maxRetryCount(),
                maxLastAttemptAt,
                command.batchSize()
        );

        for (MediaUploadSession candidate : candidates) {
            scannedCount++;

            // Strict candidate eligibility re-validation before attempting claim/abort:
            // Must satisfy local domain eligibility and be strictly in PENDING cleanup status
            if (!candidate.isLocallyEligibleForCleanup(now) || candidate.getCleanupStatus() != UploadSessionCleanupStatus.PENDING) {
                skippedCount++;
                continue;
            }

            attemptedCount++;
            try {
                MediaUploadSession sessionOutcome = sessionService.executeGuardedMultipartAbort(candidate.getId());
                if (sessionOutcome != null && sessionOutcome.getCleanupStatus() == UploadSessionCleanupStatus.QUARANTINED) {
                    quarantinedCount++;
                }
            } catch (Exception ex) {
                failedCount++;
                String errorCategory = classifySafeErrorCategory(ex);
                LOGGER.warn("Guarded multipart abort failed for upload session [ID: {}] with error category: {}",
                        candidate.getId(), errorCategory);
            }
        }

        return new SweepAbandonedMultipartUploadsResult(
                scannedCount,
                attemptedCount,
                skippedCount,
                quarantinedCount,
                verifiedCleanedCount,
                failedCount,
                expirationFailedCount,
                now
        );
    }

    private String classifySafeErrorCategory(Exception ex) {
        if (ex instanceof OptimisticLockingFailureException) {
            return "CONCURRENT_CLAIM_CONFLICT";
        }
        if (ex instanceof IllegalStateException) {
            return "ELIGIBILITY_OR_LEASE_VIOLATION";
        }
        if (ex instanceof IllegalArgumentException) {
            return "INVALID_ARGUMENT";
        }
        return ex.getClass().getSimpleName();
    }
}
