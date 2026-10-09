package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for orchestrating durable media upload session lifecycle and state transitions.
 */
@Service
public class MediaUploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadSessionService.class);

    public static final Duration DEFAULT_SESSION_TTL = Duration.ofDays(1); // 24 hours

    private final MediaUploadSessionRepositoryPort sessionRepositoryPort;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final ClockPort clockPort;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
    ) {
        this.sessionRepositoryPort = Objects.requireNonNull(sessionRepositoryPort, "MediaUploadSessionRepositoryPort cannot be null.");
        this.storageKeyAllocationService = Objects.requireNonNull(storageKeyAllocationService, "StorageKeyAllocationService cannot be null.");
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.transactionManager = transactionManager;
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, null);
    }

    /**
     * Normalizes original filename by trimming whitespace and stripping directory traversal characters.
     */
    public static String normalizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.trim().isBlank()) {
            throw new IllegalArgumentException("originalFilename cannot be blank.");
        }
        String trimmed = originalFilename.trim();
        int lastUnix = trimmed.lastIndexOf('/');
        int lastWindows = trimmed.lastIndexOf('\\');
        int lastSeparator = Math.max(lastUnix, lastWindows);
        String basename = (lastSeparator >= 0) ? trimmed.substring(lastSeparator + 1).trim() : trimmed;
        if (basename.isBlank()) {
            throw new IllegalArgumentException("originalFilename cannot resolve to an empty basename: " + originalFilename);
        }
        return basename;
    }

    /**
     * Atomically allocates a new upload session or returns an existing one if the idempotency key matches.
     * Enforces request identity on idempotent retries and guarantees zero leaked reservations on race collisions.
     */
    public MediaUploadSession allocateSession(
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            String idempotencyKey,
            long declaredSizeBytes,
            String originalFilename,
            String clientTag
    ) {
        // Pre-allocation deterministic request input validation
        requireNonBlank(initiatorPrincipalId, "initiatorPrincipalId");
        requireNonBlank(consumerContext, "consumerContext");
        requireNonBlank(targetEntityType, "targetEntityType");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
        requireNonBlank(idempotencyKey, "idempotencyKey");

        if (declaredSizeBytes <= 0 || declaredSizeBytes > MediaUploadSession.MAX_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Upload session declaredSizeBytes must be between 1 and " + MediaUploadSession.MAX_VIDEO_SIZE_BYTES + ", got: " + declaredSizeBytes
            );
        }

        String normalizedFilename = normalizeFilename(originalFilename);
        String normalizedClientTag = (clientTag != null && !clientTag.isBlank()) ? clientTag.trim() : null;

        // 1. Idempotent retry probe
        Optional<MediaUploadSession> existingSession = sessionRepositoryPort.findByIdempotencyKey(
                initiatorPrincipalId,
                idempotencyKey
        );
        if (existingSession.isPresent()) {
            MediaUploadSession existing = existingSession.get();
            validateMatchingParameters(existing, consumerContext, targetEntityType, targetEntityId, declaredSizeBytes, normalizedFilename, normalizedClientTag, idempotencyKey);
            return existing;
        }

        // 2. Reserve unique storage key for this upload session
        StorageProviderId providerId = StorageProviderId.of("b2");
        StorageKey storageKey = mediaStorageRoutingService.generateStorageKey(
                MediaType.VIDEO,
                normalizedClientTag,
                null,
                1
        );
        String bucketName = mediaStorageRoutingService.resolveBucketName(providerId);

        UUID sessionId = UUID.randomUUID();
        Instant now = clockPort.now();
        Instant expiresAt = now.plus(DEFAULT_SESSION_TTL);

        StorageKeyAllocation allocation = storageKeyAllocationService.reserveKey(
                providerId,
                bucketName,
                storageKey,
                StorageKeyAllocationType.UPLOAD_SESSION,
                sessionId
        );

        // 3. Create session in ALLOCATED state bound to allocation
        MediaUploadSession session;
        try {
            session = MediaUploadSession.create(
                    sessionId,
                    initiatorPrincipalId,
                    consumerContext,
                    targetEntityType,
                    targetEntityId,
                    idempotencyKey,
                    declaredSizeBytes,
                    providerId,
                    bucketName,
                    storageKey,
                    allocation.getId(),
                    normalizedFilename,
                    normalizedClientTag,
                    expiresAt,
                    now
            );
        } catch (RuntimeException creationException) {
            // Session creation definitely failed before persistence: safely tombstone the unused reservation
            try {
                storageKeyAllocationService.tombstoneAllocationInNewTransaction(allocation.getId());
            } catch (RuntimeException tombstoneEx) {
                creationException.addSuppressed(tombstoneEx);
            }
            throw creationException;
        }

        // 4. Isolated transaction insert attempt
        try {
            if (transactionManager != null) {
                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return txTemplate.execute(status -> sessionRepositoryPort.save(session));
            } else {
                return sessionRepositoryPort.save(session);
            }
        } catch (DataIntegrityViolationException dive) {
            // A DataIntegrityViolationException indicates that the RDBMS engine actively rejected the SQL DML
            // statement before or at commit due to a constraint violation (unique, foreign key, or check constraint).
            // Under Spring and JPA transaction semantics, this exception conclusively proves that statement execution
            // failed and the transaction was rolled back. No session row was or could be committed.
            if (!isIdempotencyKeyCollision(dive)) {
                // Unrelated constraint violation (FK, CHECK, other UNIQUE): definitive rollback before persistence.
                try {
                    storageKeyAllocationService.tombstoneAllocationInNewTransaction(allocation.getId());
                } catch (RuntimeException tombstoneEx) {
                    dive.addSuppressed(tombstoneEx);
                }
                throw dive;
            }

            // Concurrent race collision on (initiatorPrincipalId, idempotencyKey):
            // The unique constraint blocked insertion and definitively rolled back the tentative transaction.
            // Compensate redundant reservation immediately in an isolated transaction to prevent any leaked storage key.
            try {
                storageKeyAllocationService.tombstoneAllocationInNewTransaction(allocation.getId());
            } catch (RuntimeException tombstoneEx) {
                dive.addSuppressed(tombstoneEx);
            }

            MediaUploadSession winningSession = sessionRepositoryPort.findByIdempotencyKey(initiatorPrincipalId, idempotencyKey)
                    .orElseThrow(() -> dive);
            validateMatchingParameters(winningSession, consumerContext, targetEntityType, targetEntityId, declaredSizeBytes, normalizedFilename, normalizedClientTag, idempotencyKey);
            return winningSession;
        } catch (RuntimeException saveException) {
            // Non-DataIntegrityViolationException (e.g. TransientDataAccessResourceException, CannotCreateTransactionException,
            // TransactionSystemException, socket timeout during commit acknowledgment): ambiguous transaction outcome.
            // A connection drop, network partition, or transaction coordinator timeout during the commit phase
            // means the commit may have become durable on the database server before the client lost connection.
            // Because transaction callback failure does not establish that an in-flight or acknowledgment-lost commit
            // was rolled back, the outcome must be reconciled without assuming rollback.
            return reconcileUncertainPersistenceOutcome(
                    allocation.getId(),
                    sessionId,
                    initiatorPrincipalId,
                    idempotencyKey,
                    consumerContext,
                    targetEntityType,
                    targetEntityId,
                    declaredSizeBytes,
                    normalizedFilename,
                    normalizedClientTag,
                    saveException
            );
        }
    }

    /**
     * Reconciles an ambiguous transaction outcome following a save failure.
     * <p>
     * Core Invariant: Never tombstone an allocation belonging to a transaction whose successful commit
     * has not been conclusively ruled out.
     * <p>
     * A negative observation (Optional.empty()) from SELECT queries does NOT prove transaction rollback,
     * as in-flight commits, replica lag, or network delays could result in eventual durability.
     * Therefore, if both probes return empty or if probes fail, the allocation is preserved as RESERVED
     * and non-reusable, pending deferred reconciliation.
     */
    private MediaUploadSession reconcileUncertainPersistenceOutcome(
            UUID allocationId,
            UUID tentativeSessionId,
            String initiatorPrincipalId,
            String idempotencyKey,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            long declaredSizeBytes,
            String normalizedFilename,
            String clientTag,
            RuntimeException saveException
    ) {
        Optional<MediaUploadSession> sessionById;
        Optional<MediaUploadSession> sessionByIdem;
        try {
            sessionById = sessionRepositoryPort.findById(tentativeSessionId);
            sessionByIdem = sessionRepositoryPort.findByIdempotencyKey(initiatorPrincipalId, idempotencyKey);
        } catch (RuntimeException probeException) {
            // Database is unreachable or probe query failed: outcome cannot be determined safely.
            // Preserve the allocation as RESERVED (guarantee key non-reuse) and report the error.
            log.warn("Upload session reconciliation probe queries failed. Preserving allocation as RESERVED for deferred reconciliation. allocationId={}, tentativeSessionId={}",
                    allocationId, tentativeSessionId, probeException);
            saveException.addSuppressed(probeException);
            throw saveException;
        }

        // Subcase 1: Tentative session positively identified as committed despite saveException (e.g. socket timeout after commit)
        if (sessionById.isPresent()) {
            MediaUploadSession committed = sessionById.get();
            // Do NOT tombstone the allocation; it belongs to the committed session!
            return committed;
        }
        if (sessionByIdem.isPresent() && sessionByIdem.get().getId().equals(tentativeSessionId)) {
            return sessionByIdem.get();
        }

        // Subcase 2: Legitimate duplicate-idempotency winner recovery:
        // A distinct winning session is established under the unique (initiatorPrincipalId, idempotencyKey) constraint.
        // Because MySQL guarantees that only one session can exist per (initiatorPrincipalId, idempotencyKey),
        // the existence of a distinct committed winner conclusively proves that our tentative session cannot commit.
        if (sessionByIdem.isPresent()) {
            MediaUploadSession winningSession = sessionByIdem.get();
            // Ensure winning session does not own our tentative allocationId, proving allocationId is redundant
            if (!allocationId.equals(winningSession.getStorageAllocationId())) {
                try {
                    storageKeyAllocationService.tombstoneAllocationInNewTransaction(allocationId);
                } catch (RuntimeException tombstoneEx) {
                    saveException.addSuppressed(tombstoneEx);
                }
            }
            validateMatchingParameters(winningSession, consumerContext, targetEntityType, targetEntityId, declaredSizeBytes, normalizedFilename, clientTag, idempotencyKey);
            return winningSession;
        }

        // Subcase 3: Ambiguous transaction outcome where both SELECT probes return empty.
        // A negative observation (Optional.empty()) does NOT establish that an in-flight or acknowledgment-lost
        // COMMIT can never become durable. We MUST NOT assume rollback or tombstone the allocation.
        // The allocation is preserved as RESERVED and non-reusable, and the original exception is propagated.
        // Diagnostic correlation is logged (without credentials or sensitive data) for deferred reconciliation.
        log.warn("Upload session persistence outcome is ambiguous after save failure. Both probes returned empty, but transaction rollback cannot be conclusively proven. Preserving allocation as RESERVED for deferred reconciliation. allocationId={}, tentativeSessionId={}, initiatorPrincipalId={}, consumerContext={}, targetEntityType={}, targetEntityId={}",
                allocationId, tentativeSessionId, initiatorPrincipalId, consumerContext, targetEntityType, targetEntityId, saveException);
        throw saveException;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException("Upload session field [" + fieldName + "] must not be blank.");
        }
        return value.trim();
    }

    private boolean isIdempotencyKeyCollision(DataIntegrityViolationException dive) {
        Throwable curr = dive;
        while (curr != null) {
            String msg = curr.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("uq_media_upload_sessions_idempotency")
                        || lower.contains("idx_media_upload_sessions_idem")) {
                    return true;
                }
            }
            curr = curr.getCause();
        }
        return false;
    }

    private void validateMatchingParameters(
            MediaUploadSession session,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            long declaredSizeBytes,
            String normalizedFilename,
            String clientTag,
            String idempotencyKey
    ) {
        if (!session.getConsumerContext().equals(consumerContext)
                || !session.getTargetEntityType().equals(targetEntityType)
                || !session.getTargetEntityId().equals(targetEntityId)
                || session.getDeclaredSizeBytes() != declaredSizeBytes
                || !session.getOriginalFilename().equals(normalizedFilename)
                || !Objects.equals(session.getClientTag(), clientTag)) {
            throw new IllegalArgumentException(
                    String.format("Idempotency conflict: existing upload session '%s' with key '%s' was created with different parameters.",
                            session.getId(), idempotencyKey)
            );
        }
    }

    /**
     * Retrieves an existing upload session by its ID.
     */
    @Transactional(readOnly = true)
    public Optional<MediaUploadSession> findById(UUID sessionId) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        return sessionRepositoryPort.findById(sessionId);
    }

    /**
     * Records provider multipart upload initiation on an ALLOCATED session with ownership check.
     */
    @Transactional
    public MediaUploadSession markInitiated(UUID sessionId, String providerUploadId, String requestingPrincipalId) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        Objects.requireNonNull(providerUploadId, "providerUploadId cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");

        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to mutate upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }

        Instant now = clockPort.now();
        session.markInitiated(providerUploadId, now);

        return sessionRepositoryPort.save(session);
    }

    /**
     * Aborts an upload session and marks its storage allocation as tombstoned with ownership check.
     */
    @Transactional
    public MediaUploadSession abortSession(UUID sessionId, String requestingPrincipalId) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        Objects.requireNonNull(requestingPrincipalId, "requestingPrincipalId cannot be null.");

        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to abort upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }

        Instant now = clockPort.now();
        session.abort(now);

        MediaUploadSession savedSession = sessionRepositoryPort.save(session);
        storageKeyAllocationService.tombstoneAllocation(session.getStorageAllocationId());

        return savedSession;
    }

    /**
     * Internal/system maintenance hook to abort an expired or abandoned session without caller principal context.
     * Must not be invoked by user-facing endpoints.
     */
    @Transactional
    public MediaUploadSession abortBySystem(UUID sessionId, String systemReason) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        Instant now = clockPort.now();
        session.abort(now);

        MediaUploadSession savedSession = sessionRepositoryPort.save(session);
        storageKeyAllocationService.tombstoneAllocation(session.getStorageAllocationId());

        return savedSession;
    }
}
