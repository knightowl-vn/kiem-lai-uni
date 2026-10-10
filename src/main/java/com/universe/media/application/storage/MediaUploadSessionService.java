package com.universe.media.application.storage;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartCompletedObject;
import com.universe.media.application.ports.storage.MultipartObjectMetadata;
import com.universe.media.application.ports.storage.MultipartPartETag;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    public static final Duration DEFAULT_INITIATION_LEASE_DURATION = Duration.ofMinutes(5);
    public static final Duration DEFAULT_COMPLETION_LEASE_DURATION = Duration.ofMinutes(5);
    public static final String METADATA_SESSION_ID = "session-id";
    public static final String METADATA_ALLOCATION_ID = "allocation-id";

    private final MediaUploadSessionRepositoryPort sessionRepositoryPort;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final ClockPort clockPort;
    private final PlatformTransactionManager transactionManager;
    private final MultipartStoragePort multipartStoragePort;
    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Autowired
    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) MultipartStoragePort multipartStoragePort,
            @Autowired(required = false) MediaAssetRepositoryPort mediaAssetRepositoryPort,
            @Autowired(required = false) MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this.sessionRepositoryPort = Objects.requireNonNull(sessionRepositoryPort, "MediaUploadSessionRepositoryPort cannot be null.");
        this.storageKeyAllocationService = Objects.requireNonNull(storageKeyAllocationService, "StorageKeyAllocationService cannot be null.");
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.transactionManager = transactionManager;
        this.multipartStoragePort = multipartStoragePort;
        this.mediaAssetRepositoryPort = mediaAssetRepositoryPort;
        this.mediaAssetVersionRepositoryPort = mediaAssetVersionRepositoryPort;
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager,
            MultipartStoragePort multipartStoragePort
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, transactionManager, multipartStoragePort, null, null);
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, transactionManager, null, null, null);
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, null, null, null, null);
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
     * Transaction-safe multipart upload initiation adhering strictly to TX-A -> B2 -> TX-B ordering.
     *
     * <ul>
     *     <li><b>TX-A (MySQL):</b> Authorizes request, validates session and reserved allocation, atomically claims initiation lease, commits.</li>
     *     <li><b>B2 (Outside DB transaction):</b> Invokes CreateMultipartUpload with session and allocation metadata.</li>
     *     <li><b>TX-B (MySQL):</b> Persists providerUploadId, transitions session to INITIATED, clears lease, commits.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.NEVER)
    public MediaUploadSession initiateMultipartUpload(UUID sessionId, String requestingPrincipalId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("initiateMultipartUpload cannot be invoked from an active ambient transaction.");
        }

        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        requireNonBlank(requestingPrincipalId, "requestingPrincipalId");

        if (multipartStoragePort == null) {
            throw new IllegalStateException("MultipartStoragePort is not configured or available.");
        }
        if (transactionManager == null) {
            throw new IllegalStateException("PlatformTransactionManager is required for multipart initiation.");
        }

        // =========================================================================
        // TX-A — MySQL: Authorize, validate state and allocation, atomically claim initiation, commit
        // =========================================================================
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MediaUploadSession claimedSession = txTemplate.execute(status -> executeTxAClaim(sessionId, requestingPrincipalId));

        // If the session was already INITIATED (idempotent retry), return directly without second B2 initiation
        if (claimedSession.getStatus() == MediaUploadSessionStatus.INITIATED) {
            return claimedSession;
        }

        // Defensive check: guarantee no ambient transaction is active before invoking remote provider
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("B2 multipart upload cannot be invoked while a database transaction is active.");
        }

        // =========================================================================
        // B2 — Outside MySQL Transaction: Invoke CreateMultipartUpload with bound metadata
        // =========================================================================
        String providerUploadId;
        try {
            Map<String, String> metadata = Map.of(
                    METADATA_SESSION_ID, claimedSession.getId().toString(),
                    METADATA_ALLOCATION_ID, claimedSession.getStorageAllocationId().toString()
            );
            providerUploadId = multipartStoragePort.initiateMultipartUpload(
                    claimedSession.getStorageKey(),
                    MimeType.of(claimedSession.getDeclaredMimeType()),
                    metadata
            );
        } catch (RuntimeException providerException) {
            // "Never assume remote initiation failed merely because providerUploadId was not persisted."
            // "Unknown outcomes must remain recoverable/reconcilable, without speculative provider deletion."
            log.warn("Multipart initiation call to provider failed or timed out for session {}. Preserving initiation claim for reconciliation without speculative deletion.",
                    sessionId, providerException);
            throw providerException;
        }

        // =========================================================================
        // TX-B — MySQL: Persist providerUploadId, transition session to INITIATED, clear lease, commit
        // =========================================================================
        try {
            return txTemplate.execute(status -> markInitiated(sessionId, providerUploadId, requestingPrincipalId, claimedSession.getLeaseOwner()));
        } catch (RuntimeException txBException) {
            // "Provider success followed by TX-B persistence failure."
            // Remote initiation succeeded, but MySQL persistence failed.
            // Preserves state in DB (status ALLOCATED, providerUploadId null, initiation claim recorded).
            // Do NOT delete from provider speculatively. Propagate exception.
            log.error("TX-B persistence failed after successful provider initiation for session {}. providerUploadId={}. Preserving state for reconciliation.",
                    sessionId, providerUploadId, txBException);
            throw txBException;
        }
    }

    private MediaUploadSession executeTxAClaim(UUID sessionId, String requestingPrincipalId) {
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        // 1. Authorize requesting principal
        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to initiate upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }

        Instant now = clockPort.now();

        // 2. Check session expiry
        if (session.getExpiresAt().isBefore(now)) {
            throw new IllegalStateException("Upload session has expired: " + sessionId);
        }

        // 3. Check session status
        if (session.getStatus() == MediaUploadSessionStatus.INITIATED) {
            // Already initiated: return idempotently without duplicate remote creation
            return session;
        }
        if (session.getStatus() != MediaUploadSessionStatus.ALLOCATED) {
            throw new IllegalStateException(
                    String.format("Cannot initiate upload session %s in status: %s", sessionId, session.getStatus())
            );
        }

        // 4. Validate initiation claims and guard against duplicate remote initiation
        if (session.hasActiveInitiationLease(now)) {
            throw new IllegalStateException(
                    String.format("Concurrent initiation in progress for upload session %s (lease held by %s)",
                            sessionId, session.getLeaseOwner())
            );
        }
        if (session.hasUnconfirmedInitiationClaim(now)) {
            // "Distinguish expired lease from confirmed worker termination."
            // "Never assume remote initiation failed merely because providerUploadId was not persisted."
            // "Never automatically create a second multipart upload under the same reserved storage key while an earlier initiation may have succeeded."
            throw new IllegalStateException(
                    String.format("Upload session %s has an unconfirmed earlier initiation claim with missing providerUploadId. Cannot initiate a second multipart upload without reconciliation.",
                            sessionId)
            );
        }

        // 5. Validate reserved allocation binding
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));
        validateAllocationBinding(allocation, session);

        // 6. Claim initiation atomically
        String claimToken = "INITIATION_CLAIM:" + UUID.randomUUID();
        Instant leaseExpiresAt = now.plus(DEFAULT_INITIATION_LEASE_DURATION);
        session.claimInitiation(claimToken, leaseExpiresAt, now);

        return sessionRepositoryPort.save(session);
    }

    /**
     * Atomically validates and claims completion intent for a multipart upload session.
     * Computes the deterministic canonical manifest fingerprint and persists it along with
     * transitioning the session to COMPLETING.
     */
    @Transactional
    public MediaUploadSession claimCompletionIntent(
            UUID sessionId,
            String requestingPrincipalId,
            String targetEntityType,
            UUID targetEntityId,
            String declaredContentHash,
            List<MultipartManifestPart> manifest
    ) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        requireNonBlank(requestingPrincipalId, "requestingPrincipalId");
        requireNonBlank(targetEntityType, "targetEntityType");
        Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
        requireNonBlank(declaredContentHash, "declaredContentHash");
        Objects.requireNonNull(manifest, "manifest cannot be null.");

        String normalizedDeclaredHash = declaredContentHash.trim().toLowerCase();
        if (!MediaUploadSession.SHA256_PATTERN.matcher(normalizedDeclaredHash).matches()) {
            throw new IllegalArgumentException("declaredContentHash must be a 64-character lowercase hex string.");
        }

        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        // 1. Validate session ownership and target
        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to claim completion on upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }
        if (!session.getTargetEntityType().equals(targetEntityType)
                || !session.getTargetEntityId().equals(targetEntityId)) {
            throw new IllegalArgumentException(
                    String.format("Target mismatch for upload session %s. Expected %s/%s, got %s/%s",
                            sessionId, session.getTargetEntityType(), session.getTargetEntityId(), targetEntityType, targetEntityId)
            );
        }

        // 2. Validate expected session state
        if (session.getStatus() == MediaUploadSessionStatus.COMPLETED
                || session.getStatus() == MediaUploadSessionStatus.ABORTED
                || session.getStatus() == MediaUploadSessionStatus.EXPIRED
                || session.getStatus() == MediaUploadSessionStatus.FAILED
                || session.getStatus() == MediaUploadSessionStatus.CLEANED_UP) {
            throw new IllegalStateException(
                    String.format("Cannot claim completion intent on upload session %s in status %s", sessionId, session.getStatus())
            );
        }
        if (session.getStatus() == MediaUploadSessionStatus.ALLOCATED) {
            throw new IllegalStateException(
                    String.format("Cannot claim completion intent on upload session %s before provider initiation.", sessionId)
            );
        }

        // 3. Validate allocation binding
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));
        validateAllocationBinding(allocation, session);

        // 4. Validate manifest and compute canonical fingerprint
        String manifestFingerprint = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest);

        // 5. Concurrency & retry handling
        if (session.getStatus() == MediaUploadSessionStatus.COMPLETING) {
            boolean manifestMatches = session.getManifestHash() != null
                    && session.getManifestHash().equalsIgnoreCase(manifestFingerprint);
            boolean declaredHashMatches = session.getDeclaredContentHash() != null
                    && session.getDeclaredContentHash().equalsIgnoreCase(normalizedDeclaredHash);

            if (manifestMatches && declaredHashMatches) {
                // Identical retry: return without mutating
                return session;
            } else if (!manifestMatches) {
                // Conflicting manifest on session with active completion claim: reject without mutating original intent
                throw new IllegalStateException(
                        String.format("Conflicting manifest for session %s: existing manifestHash is %s, submitted manifestHash is %s",
                                sessionId, session.getManifestHash(), manifestFingerprint)
                );
            } else {
                // Conflicting declaredContentHash on session with active completion claim: reject without mutating original intent
                throw new IllegalStateException(
                        String.format("Conflicting declared content hash for session %s: existing declaredContentHash is %s, submitted declaredContentHash is %s",
                                sessionId, session.getDeclaredContentHash(), normalizedDeclaredHash)
                );
            }
        }

        // 6. Transition from INITIATED to COMPLETING with recorded manifestHash
        Instant now = clockPort.now();
        session.recordManifestHash(manifestFingerprint);
        session.markCompleting(normalizedDeclaredHash, now);

        return sessionRepositoryPort.save(session);
    }

    /**
     * Convenience overload accepting uploaded parts from provider listing or upload result.
     */
    @Transactional
    public MediaUploadSession claimCompletionIntentFromUploadedParts(
            UUID sessionId,
            String requestingPrincipalId,
            String targetEntityType,
            UUID targetEntityId,
            String declaredContentHash,
            List<MultipartUploadedPart> uploadedParts
    ) {
        Objects.requireNonNull(uploadedParts, "uploadedParts cannot be null.");
        List<MultipartManifestPart> manifest = uploadedParts.stream()
                .map(MultipartManifestPart::from)
                .toList();
        return claimCompletionIntent(sessionId, requestingPrincipalId, targetEntityType, targetEntityId, declaredContentHash, manifest);
    }

    /**
     * Persists provider multipart upload initiation on an ALLOCATED session verified by an active initiation claim token.
     */
    @Transactional
    public MediaUploadSession markInitiated(UUID sessionId, String providerUploadId, String requestingPrincipalId, String claimToken) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        Objects.requireNonNull(providerUploadId, "providerUploadId cannot be null.");
        requireNonBlank(requestingPrincipalId, "requestingPrincipalId");
        requireNonBlank(claimToken, "claimToken");

        MediaUploadSession currentSession = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        if (!currentSession.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to transition upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, currentSession.getInitiatorPrincipalId())
            );
        }

        if (currentSession.getStatus() != MediaUploadSessionStatus.ALLOCATED) {
            throw new IllegalStateException(
                    String.format("Cannot persist providerUploadId on session %s in status %s", sessionId, currentSession.getStatus())
            );
        }

        Instant now = clockPort.now();
        if (!currentSession.hasActiveInitiationLease(now)) {
            throw new IllegalStateException(
                    String.format("Upload session %s does not have an active initiation lease.", sessionId)
            );
        }

        if (!Objects.equals(currentSession.getLeaseOwner(), claimToken)) {
            throw new IllegalStateException(
                    String.format("Initiation claim token mismatch for session %s. Expected %s, found %s",
                            sessionId, claimToken, currentSession.getLeaseOwner())
            );
        }

        StorageKeyAllocation allocation = storageKeyAllocationService.findById(currentSession.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));
        validateAllocationBinding(allocation, currentSession);

        currentSession.markInitiated(providerUploadId, now);

        return sessionRepositoryPort.save(currentSession);
    }

    /**
     * @deprecated Direct initiation without a verified initiation claim token is prohibited.
     * Knowledge of session ID, upload ID, and principal ID alone cannot bypass initiation checks.
     * Use {@link #initiateMultipartUpload(UUID, String)} or {@link #markInitiated(UUID, String, String, String)}.
     */
    @Deprecated
    public MediaUploadSession markInitiated(UUID sessionId, String providerUploadId, String requestingPrincipalId) {
        throw new UnsupportedOperationException(
                "Direct initiation without a verified initiation claim token is prohibited. Knowledge of session ID and principal ID alone cannot bypass initiation."
        );
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

        if (session.getStatus() == MediaUploadSessionStatus.ALLOCATED && session.getLeaseOwner() != null) {
            throw new IllegalStateException(
                    String.format("Cannot abort upload session %s with unresolved initiation claim (lease owner: %s)",
                            sessionId, session.getLeaseOwner())
            );
        }

        if (session.getStatus() == MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Cannot abort session with active completion claim: " + sessionId);
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

        if (session.getStatus() == MediaUploadSessionStatus.ALLOCATED && session.getLeaseOwner() != null) {
            throw new IllegalStateException(
                    String.format("Cannot abort upload session %s with unresolved initiation claim (lease owner: %s)",
                            sessionId, session.getLeaseOwner())
            );
        }

        if (session.getStatus() == MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Cannot abort session with active completion claim: " + sessionId);
        }

        Instant now = clockPort.now();
        session.abort(now);

        MediaUploadSession savedSession = sessionRepositoryPort.save(session);
        storageKeyAllocationService.tombstoneAllocation(session.getStorageAllocationId());

        return savedSession;
    }

    private void validateAllocationBinding(StorageKeyAllocation allocation, MediaUploadSession session) {
        if (allocation.getStatus() != StorageKeyAllocationStatus.RESERVED) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s is not in RESERVED status (found: %s)",
                            session.getStorageAllocationId(), allocation.getStatus())
            );
        }
        if (!allocation.getProviderId().equals(session.getStorageProviderId())
                || !allocation.getBucketName().equals(session.getStorageBucketName())
                || !allocation.getStorageKey().equals(session.getStorageKey())
                || allocation.getAllocatedByType() != StorageKeyAllocationType.UPLOAD_SESSION
                || !allocation.getAllocatedById().equals(session.getId())) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s does not match upload session %s binding.",
                            session.getStorageAllocationId(), session.getId())
            );
        }
    }

    /**
     * Transaction-safe multipart upload completion adhering strictly to TX-A -> Provider -> TX-B ordering.
     *
     * <ul>
     *     <li><b>TX-A (MySQL):</b> Authorizes principal, verifies COMPLETING state, verifies allocation binding,
     *         validates accepted completion manifest, claims completion lease, commits.</li>
     *     <li><b>Provider verification (Outside DB transaction):</b>
     *         Authoritatively queries provider ListParts with complete pagination, verifies exact part numbers,
     *         ordering, sizes, and opaque ETags against accepted manifest.</li>
     *     <li><b>Provider completion (Outside DB transaction):</b>
     *         Calls CompleteMultipartUpload on provider. Ambiguous remote outcomes (timeouts, NoSuchUpload)
     *         probe storage metadata rather than failing blindly or aborting.</li>
     *     <li><b>Provenance verification (Outside DB transaction):</b>
     *         Inspects completed object metadata (HeadObject), verifies size, MIME (video/mp4),
     *         and mandatory session-id and allocation-id metadata bindings. Mismatch fails closed.</li>
     *     <li><b>TX-B (MySQL):</b> Atomically revalidates lease/fencing and state, creates or idempotently locates MediaAsset
     *         and MediaAssetVersion, transfers allocation ownership to ASSET_VERSION, marks session COMPLETED, commits.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.NEVER)
    public MediaUploadSession completeMultipartUpload(
            UUID sessionId,
            String requestingPrincipalId,
            List<MultipartManifestPart> manifest
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("completeMultipartUpload cannot be invoked from an active ambient transaction.");
        }

        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        requireNonBlank(requestingPrincipalId, "requestingPrincipalId");

        if (multipartStoragePort == null) {
            throw new IllegalStateException("MultipartStoragePort is not configured or available.");
        }
        if (transactionManager == null) {
            throw new IllegalStateException("PlatformTransactionManager is required for multipart completion.");
        }
        if (mediaAssetRepositoryPort == null || mediaAssetVersionRepositoryPort == null) {
            throw new IllegalStateException("MediaAssetRepositoryPort and MediaAssetVersionRepositoryPort are required for multipart completion.");
        }

        // =========================================================================
        // TX-A — MySQL: Authorize, validate state & allocation, claim completion lease, commit
        // =========================================================================
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String claimToken = "COMPLETION_CLAIM:" + UUID.randomUUID();
        MediaUploadSession claimedSession = txTemplate.execute(status ->
                executeTxACompletionClaim(sessionId, requestingPrincipalId, manifest, claimToken)
        );

        // If the session was already COMPLETED (idempotent retry), return directly without second B2 completion
        if (claimedSession.getStatus() == MediaUploadSessionStatus.COMPLETED) {
            return claimedSession;
        }

        // Defensive check: guarantee no ambient transaction is active before invoking remote provider
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("B2 multipart completion operations cannot be invoked while a database transaction is active.");
        }

        // =========================================================================
        // B2 Provider Verification & Complete — Outside MySQL Transaction
        // =========================================================================
        List<MultipartUploadedPart> providerParts = null;
        MultipartCompletedObject completedObject = null;

        try {
            providerParts = multipartStoragePort.listUploadedParts(
                    claimedSession.getStorageKey(),
                    claimedSession.getProviderUploadId()
            );
        } catch (StorageObjectNotFoundException listNotFoundEx) {
            // NoSuchUpload on listParts: the multipart upload ID no longer exists on provider.
            // On B2/S3, HEAD object metadata only contains session-id, allocation-id, size, and MIME;
            // it does NOT contain providerUploadId, manifest_hash, or cryptographic payload verification.
            // Under the existing contract, HEAD metadata agreement cannot independently prove that
            // the object was assembled from the accepted multipart manifest or providerUploadId.
            // Therefore, we must NOT prematurely register an asset. We preserve the session as
            // ambiguous for later reconciliation (S2D) by rethrowing the exception.
            try {
                MultipartObjectMetadata probe = multipartStoragePort.inspectObjectMetadata(claimedSession.getStorageKey());
                log.warn("NoSuchUpload on listParts for session {}: object exists on storage (size={}, eTag={}), but assembly from providerUploadId {} / manifest {} cannot be proven. Preserving ambiguous state.",
                        sessionId, probe.contentLength(), probe.eTag(), claimedSession.getProviderUploadId(), claimedSession.getManifestHash());
            } catch (Exception probeEx) {
                log.warn("NoSuchUpload on listParts for session {} and probe failed. Preserving ambiguous state.", sessionId, probeEx);
                listNotFoundEx.addSuppressed(probeEx);
            }
            throw listNotFoundEx;
        } catch (StorageException genericListException) {
            // Generic network or storage failure listing parts without conclusive completion evidence.
            // Preserve ambiguous state for S2D reconciliation.
            log.warn("Failed to list uploaded parts for upload session {} due to storage failure. Preserving state for reconciliation.",
                    sessionId, genericListException);
            throw genericListException;
        }

        // If completedObject was not already recovered via probe, proceed with standard validation and CompleteMultipartUpload
        if (completedObject == null) {
            if (providerParts == null || providerParts.isEmpty()) {
                throw new IllegalStateException("Provider returned no parts for upload session: " + sessionId);
            }

            // Validate provider parts against accepted manifest
            List<MultipartManifestPart> effectiveManifest = (manifest != null)
                    ? manifest
                    : providerParts.stream().map(MultipartManifestPart::from).toList();

            String providerManifestHash = MultipartManifestCanonicalizer.validateAndCanonicalize(claimedSession, effectiveManifest);
            if (claimedSession.getManifestHash() == null || !claimedSession.getManifestHash().equalsIgnoreCase(providerManifestHash)) {
                throw new IllegalStateException(
                        String.format("Provider uploaded parts manifest hash '%s' does not match session accepted manifest hash '%s'",
                                providerManifestHash, claimedSession.getManifestHash())
                );
            }

            verifyPartsMatchManifest(providerParts, effectiveManifest);

            List<MultipartUploadedPart> sortedProviderParts = providerParts.stream()
                    .sorted(Comparator.comparingInt(MultipartUploadedPart::partNumber))
                    .toList();

            List<MultipartPartETag> partsToComplete = sortedProviderParts.stream()
                    .map(p -> new MultipartPartETag(p.partNumber(), p.eTag()))
                    .toList();

            // Complete remote multipart upload on provider
            try {
                completedObject = multipartStoragePort.completeMultipartUpload(
                        claimedSession.getStorageKey(),
                        claimedSession.getProviderUploadId(),
                        partsToComplete
                );
            } catch (StorageException remoteCompleteException) {
                // Ambiguous completion failure: socket timeout, 5xx, or NoSuchUpload.
                // Under the currently approved contract, HEAD-only evidence does not prove that
                // the object resulted from the accepted providerUploadId and canonical manifest.
                // Therefore, do NOT automatically register based solely on HEAD metadata.
                // Preserve COMPLETING and allocation evidence for later reconciliation (S2D).
                // Perform an optional diagnostic HEAD probe for logging/evidence preservation,
                // but never authorize TX-B registration without an acknowledged completion response.
                try {
                    MultipartObjectMetadata probe = multipartStoragePort.inspectObjectMetadata(claimedSession.getStorageKey());
                    log.warn("CompleteMultipartUpload threw {} for session {}: diagnostic probe detected object on storage (size={}, eTag={}, versionId={}), but assembly cannot be definitively proven without acknowledged completion response. Preserving COMPLETING state for reconciliation.",
                            remoteCompleteException.getClass().getSimpleName(), sessionId, probe.contentLength(), probe.eTag(), probe.versionId());
                } catch (Exception probeException) {
                    log.warn("CompleteMultipartUpload failed and diagnostic probe also failed for session {}. Preserving state for reconciliation.",
                            sessionId, probeException);
                    remoteCompleteException.addSuppressed(probeException);
                }
                throw remoteCompleteException;
            }
        }

        // =========================================================================
        // Provenance Verification — Outside MySQL Transaction
        // =========================================================================
        MultipartObjectMetadata headMetadata;
        try {
            headMetadata = multipartStoragePort.inspectObjectMetadata(claimedSession.getStorageKey());
        } catch (RuntimeException headException) {
            log.warn("inspectObjectMetadata failed after completion for session {}. Preserving state for reconciliation.",
                    sessionId, headException);
            throw headException;
        }

        verifyCompletedObjectProvenance(
                claimedSession,
                headMetadata,
                completedObject.location(),
                completedObject.eTag(),
                completedObject.versionId()
        );

        // =========================================================================
        // TX-B — MySQL: Atomically register MediaAsset & MediaAssetVersion, commit allocation, mark COMPLETED
        // =========================================================================
        try {
            return txTemplate.execute(status -> executeTxBRegistration(sessionId, requestingPrincipalId, claimToken));
        } catch (RuntimeException txBException) {
            log.error("TX-B persistence failed after successful provider completion for session {}. Preserving state for reconciliation.",
                    sessionId, txBException);
            throw txBException;
        }
    }

    /**
     * Convenience overload for completeMultipartUpload querying parts authoritatively from provider.
     */
    @Transactional(propagation = Propagation.NEVER)
    public MediaUploadSession completeMultipartUpload(
            UUID sessionId,
            String requestingPrincipalId
    ) {
        return completeMultipartUpload(sessionId, requestingPrincipalId, null);
    }

    private MediaUploadSession executeTxACompletionClaim(
            UUID sessionId,
            String requestingPrincipalId,
            List<MultipartManifestPart> manifest,
            String claimToken
    ) {
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        // 1. Authorize requesting principal
        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to complete upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }

        // 2. Check session status
        if (session.getStatus() == MediaUploadSessionStatus.COMPLETED) {
            // Already completed: validate manifest if provided, verify full persisted relationship, and return idempotently
            if (manifest != null) {
                String manifestHash = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest);
                if (session.getManifestHash() != null && !session.getManifestHash().equalsIgnoreCase(manifestHash)) {
                    throw new IllegalStateException("Submitted manifest does not match completed session manifestHash");
                }
            }
            return verifyAndReturnIdempotentSession(session);
        }

        if (session.getStatus() != MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException(
                    String.format("Cannot complete upload session %s in status %s. Durable completion intent must be claimed first.",
                            sessionId, session.getStatus())
            );
        }

        Instant now = clockPort.now();

        // 3. Check session expiry
        if (session.getExpiresAt().isBefore(now)) {
            throw new IllegalStateException("Upload session has expired: " + sessionId);
        }

        // 4. Validate accepted manifest fingerprint if manifest provided
        if (manifest != null) {
            String submittedHash = MultipartManifestCanonicalizer.validateAndCanonicalize(session, manifest);
            if (session.getManifestHash() == null || !session.getManifestHash().equalsIgnoreCase(submittedHash)) {
                throw new IllegalStateException(
                        String.format("Submitted manifest hash '%s' does not match accepted completion intent '%s' for session %s",
                                submittedHash, session.getManifestHash(), sessionId)
                );
            }
        }

        // 5. Validate completion lease/fencing
        if (session.hasActiveCompletionLease(now)) {
            throw new IllegalStateException(
                    String.format("Concurrent completion in progress for upload session %s (lease held by %s)",
                            sessionId, session.getLeaseOwner())
            );
        }

        // 6. Validate allocation binding
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));
        validateAllocationBinding(allocation, session);

        // 7. Claim completion lease
        Instant leaseExpiresAt = now.plus(DEFAULT_COMPLETION_LEASE_DURATION);
        session.claimCompletion(claimToken, leaseExpiresAt, now);

        return sessionRepositoryPort.save(session);
    }

    private void verifyPartsMatchManifest(List<MultipartUploadedPart> providerParts, List<MultipartManifestPart> manifest) {
        if (providerParts.size() != manifest.size()) {
            throw new IllegalStateException(
                    String.format("Provider uploaded part count (%d) does not match manifest part count (%d)",
                            providerParts.size(), manifest.size())
            );
        }

        List<MultipartManifestPart> sortedManifest = manifest.stream()
                .sorted(Comparator.comparingInt(MultipartManifestPart::partNumber))
                .toList();

        List<MultipartUploadedPart> sortedProviderParts = providerParts.stream()
                .sorted(Comparator.comparingInt(MultipartUploadedPart::partNumber))
                .toList();

        for (int i = 0; i < sortedManifest.size(); i++) {
            MultipartManifestPart m = sortedManifest.get(i);
            MultipartUploadedPart p = sortedProviderParts.get(i);

            if (p.partNumber() != m.partNumber()) {
                throw new IllegalStateException(
                        String.format("Part number mismatch at index %d: manifest part %d, provider part %d",
                                i, m.partNumber(), p.partNumber())
                );
            }

            if (p.sizeBytes() != m.sizeBytes()) {
                throw new IllegalStateException(
                        String.format("Part size mismatch for part %d: manifest size %d, provider size %d",
                                m.partNumber(), m.sizeBytes(), p.sizeBytes())
                );
            }

            String normManifestETag = MultipartManifestCanonicalizer.normalizeETag(m.eTag());
            String normProviderETag = MultipartManifestCanonicalizer.normalizeETag(p.eTag());
            if (!normManifestETag.equals(normProviderETag)) {
                throw new IllegalStateException(
                        String.format("Part ETag mismatch for part %d: manifest ETag %s, provider ETag %s",
                                m.partNumber(), m.eTag(), p.eTag())
                );
            }
        }
    }

    private void verifyCompletedObjectProvenance(
            MediaUploadSession session,
            MultipartObjectMetadata headMetadata,
            StorageLocation completedLocation,
            String expectedETag,
            String expectedVersionId
    ) {
        Objects.requireNonNull(headMetadata, "MultipartObjectMetadata cannot be null.");

        // Provider completion location check (where available from provider completion result)
        if (completedLocation != null) {
            if (!session.getStorageProviderId().equals(completedLocation.providerId())) {
                throw new IllegalStateException(
                        String.format("Provider completed object location provider mismatch: expected %s, got %s",
                                session.getStorageProviderId(), completedLocation.providerId())
                );
            }
            if (!session.getStorageKey().equals(completedLocation.key())) {
                throw new IllegalStateException(
                        String.format("Provider completed object location key mismatch: expected %s, got %s",
                                session.getStorageKey(), completedLocation.key())
                );
            }
        }

        // Provider identity check (where available from adapter)
        if (headMetadata.providerId() != null && !session.getStorageProviderId().equals(headMetadata.providerId())) {
            throw new IllegalStateException(
                    String.format("Completed object provider mismatch: expected %s, got %s",
                            session.getStorageProviderId(), headMetadata.providerId())
            );
        }

        // Bucket name check (where available from adapter)
        if (headMetadata.bucketName() != null && !session.getStorageBucketName().equals(headMetadata.bucketName())) {
            throw new IllegalStateException(
                    String.format("Completed object bucket mismatch: expected %s, got %s",
                            session.getStorageBucketName(), headMetadata.bucketName())
            );
        }

        // Exact storage key check
        if (!session.getStorageKey().equals(headMetadata.key())) {
            throw new IllegalStateException(
                    String.format("Completed object storage key mismatch: expected %s, got %s",
                            session.getStorageKey(), headMetadata.key())
            );
        }

        // Exact content length check
        if (headMetadata.contentLength() != session.getDeclaredSizeBytes()) {
            throw new IllegalStateException(
                    String.format("Completed object size mismatch: declared %d bytes, remote object is %d bytes",
                            session.getDeclaredSizeBytes(), headMetadata.contentLength())
            );
        }

        // Content type check (video/mp4)
        if (headMetadata.contentType() == null || !MediaUploadSession.REQUIRED_MIME_TYPE.equalsIgnoreCase(headMetadata.contentType().trim())) {
            throw new IllegalStateException(
                    String.format("Completed object content type mismatch: expected %s, got %s",
                            MediaUploadSession.REQUIRED_MIME_TYPE, headMetadata.contentType())
            );
        }

        // Mandatory initiation metadata bindings check: session-id and allocation-id
        String metaSessionId = getMetadataValue(headMetadata.userMetadata(), METADATA_SESSION_ID);
        if (metaSessionId == null || !metaSessionId.equalsIgnoreCase(session.getId().toString())) {
            throw new IllegalStateException(
                    String.format("Completed object provenance metadata [%s] mismatch or missing: expected %s, got %s",
                            METADATA_SESSION_ID, session.getId(), metaSessionId)
            );
        }

        String metaAllocId = getMetadataValue(headMetadata.userMetadata(), METADATA_ALLOCATION_ID);
        if (metaAllocId == null || !metaAllocId.equalsIgnoreCase(session.getStorageAllocationId().toString())) {
            throw new IllegalStateException(
                    String.format("Completed object provenance metadata [%s] mismatch or missing: expected %s, got %s",
                            METADATA_ALLOCATION_ID, session.getStorageAllocationId(), metaAllocId)
            );
        }

        // ETag check against provider completion result to protect against concurrent overwrite races
        if (expectedETag != null && !expectedETag.isBlank()) {
            String normHead = MultipartManifestCanonicalizer.normalizeETag(headMetadata.eTag());
            String normExpected = MultipartManifestCanonicalizer.normalizeETag(expectedETag);
            if (!normHead.equals(normExpected)) {
                throw new IllegalStateException(
                        String.format("Completed object ETag mismatch (potential concurrent overwrite): expected %s, got %s",
                                expectedETag, headMetadata.eTag())
                );
            }
        }

        // Version ID check: when nonblank versionId is expected from provider completion result,
        // missing/blank HEAD versionId or versionId mismatch must fail closed.
        if (expectedVersionId != null && !expectedVersionId.isBlank()) {
            if (headMetadata.versionId() == null || headMetadata.versionId().isBlank()) {
                throw new IllegalStateException(
                        String.format("Completed object versionId missing on HEAD metadata: expected %s, got null/blank",
                                expectedVersionId)
                );
            }
            if (!expectedVersionId.equals(headMetadata.versionId())) {
                throw new IllegalStateException(
                        String.format("Completed object versionId mismatch (potential concurrent overwrite): expected %s, got %s",
                                expectedVersionId, headMetadata.versionId())
                );
            }
        }
    }

    private static String getMetadataValue(Map<String, String> metadata, String keyName) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        String resolvedValue = null;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String k = entry.getKey();
            if (k != null) {
                if (k.equalsIgnoreCase(keyName) || k.equalsIgnoreCase("x-amz-meta-" + keyName)) {
                    String v = entry.getValue();
                    if (resolvedValue != null && !resolvedValue.equalsIgnoreCase(v)) {
                        throw new IllegalStateException(
                                String.format("Conflicting metadata values found for key [%s]: '%s' vs '%s'",
                                        keyName, resolvedValue, v)
                        );
                    }
                    resolvedValue = v;
                }
            }
        }
        return resolvedValue;
    }

    private MediaUploadSession executeTxBRegistration(UUID sessionId, String requestingPrincipalId, String claimToken) {
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        Instant now = clockPort.now();

        // 1. Check for already completed session (idempotent path)
        if (session.getStatus() == MediaUploadSessionStatus.COMPLETED) {
            return verifyAndReturnIdempotentSession(session);
        }

        // 2. Validate session status
        if (session.getStatus() != MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Upload session in invalid status for registration: " + session.getStatus());
        }

        // 3. Validate requesting principal
        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to register upload session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }

        // 4. Validate completion lease ownership AND current lease expiration
        if (claimToken != null) {
            if (!Objects.equals(session.getLeaseOwner(), claimToken)) {
                throw new IllegalStateException(
                        String.format("Completion claim token mismatch for session %s. Expected %s, found %s",
                                sessionId, claimToken, session.getLeaseOwner())
                );
            }
            if (session.isCompletionLeaseExpired(now)) {
                throw new IllegalStateException(
                        String.format("Completion lease held by %s for session %s expired at %s (current time: %s). Stale worker cannot commit registration.",
                                claimToken, sessionId, session.getLeaseExpiresAt(), now)
                );
            }
        }

        // 5. Validate storage allocation binding
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));

        // Fail-closed on inconsistent state: session is COMPLETING but allocation is already COMMITTED
        if (allocation.getStatus() == StorageKeyAllocationStatus.COMMITTED) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s is already COMMITTED but upload session %s is still in status %s. Inconsistent state.",
                            allocation.getId(), session.getId(), session.getStatus())
            );
        }

        if (allocation.getStatus() != StorageKeyAllocationStatus.RESERVED) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s is not in RESERVED status (found: %s)",
                            allocation.getId(), allocation.getStatus())
            );
        }
        if (!allocation.getProviderId().equals(session.getStorageProviderId())
                || !allocation.getBucketName().equals(session.getStorageBucketName())
                || !allocation.getStorageKey().equals(session.getStorageKey())
                || allocation.getAllocatedByType() != StorageKeyAllocationType.UPLOAD_SESSION
                || !allocation.getAllocatedById().equals(session.getId())) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s does not match upload session %s binding.",
                            allocation.getId(), session.getId())
            );
        }

        // 6. Enforce atomic registration invariant: a session in COMPLETING status cannot have a pre-existing registeredAssetId.
        // Under an atomic TX-B contract, asset, version, session, and allocation mutations commit or roll back together.
        if (session.getRegisteredAssetId() != null) {
            throw new IllegalStateException(
                    String.format("Upload session %s in COMPLETING status already references registeredAssetId %s. Inconsistent partial registration state.",
                            session.getId(), session.getRegisteredAssetId())
            );
        }

        UUID assetId = UUID.randomUUID();
        MediaAsset asset = MediaAsset.registerInitial(
                assetId,
                session.getMediaType(),
                MediaVisibility.PUBLIC,
                session.getClientTag(),
                now
        );
        asset = mediaAssetRepositoryPort.save(asset);

        // 7. Create MediaAssetVersion
        StorageLocation storageLocation = StorageLocation.of(session.getStorageProviderId(), session.getStorageKey());
        if (mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(asset.getId(), 1).isPresent()) {
            throw new IllegalStateException(
                    String.format("Unexpected existing MediaAssetVersion 1 found for newly created asset %s. Inconsistent state.",
                            asset.getId())
            );
        }
        if (mediaAssetVersionRepositoryPort.existsByStorageLocation(storageLocation)) {
            throw new DuplicateStorageLocationException(storageLocation);
        }
        UUID versionId = UUID.randomUUID();
        ContentHash contentHash = ContentHash.of(session.getDeclaredContentHash());
        MimeType mimeType = MimeType.of(session.getDeclaredMimeType());
        MediaAssetVersion version = MediaAssetVersion.create(
                versionId,
                asset.getId(),
                1,
                storageLocation,
                null,
                contentHash,
                ContentHashStatus.CLIENT_DECLARED_UNVERIFIED,
                mimeType,
                session.getDeclaredSizeBytes(),
                session.getOriginalFilename(),
                now
        );
        version = mediaAssetVersionRepositoryPort.save(version);

        // 8. Transfer allocation ownership from UPLOAD_SESSION to ASSET_VERSION
        storageKeyAllocationService.commitAllocation(
                allocation.getId(),
                StorageKeyAllocationType.ASSET_VERSION,
                version.getId()
        );

        // 9. Mark session COMPLETED and save
        session.markCompleted(asset.getId(), now);
        return sessionRepositoryPort.save(session);
    }

    private MediaUploadSession verifyAndReturnIdempotentSession(MediaUploadSession session) {
        if (session.getRegisteredAssetId() == null) {
            throw new IllegalStateException("Upload session is COMPLETED but registeredAssetId is null: " + session.getId());
        }

        MediaAsset asset = mediaAssetRepositoryPort.findById(session.getRegisteredAssetId())
                .orElseThrow(() -> new IllegalStateException("Registered MediaAsset not found for completed session: " + session.getRegisteredAssetId()));

        if (asset.getMediaType() != session.getMediaType()) {
            throw new IllegalStateException(
                    String.format("Registered MediaAsset %s mediaType %s does not match session mediaType %s",
                            asset.getId(), asset.getMediaType(), session.getMediaType())
            );
        }

        MediaAssetVersion version = mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(asset.getId(), 1)
                .orElseThrow(() -> new IllegalStateException("MediaAssetVersion 1 not found for completed asset: " + asset.getId()));

        if (version.getVersionNumber() != 1) {
            throw new IllegalStateException("MediaAssetVersion number is not 1: " + version.getVersionNumber());
        }

        if (!version.getStorageLocation().providerId().equals(session.getStorageProviderId())
                || !version.getStorageLocation().key().equals(session.getStorageKey())) {
            throw new IllegalStateException(
                    String.format("MediaAssetVersion location %s does not match completed session storage %s/%s",
                            version.getStorageLocation(), session.getStorageProviderId(), session.getStorageKey())
            );
        }

        if (version.getSizeBytes() != session.getDeclaredSizeBytes()) {
            throw new IllegalStateException(
                    String.format("MediaAssetVersion size %d does not match session declared size %d",
                            version.getSizeBytes(), session.getDeclaredSizeBytes())
            );
        }

        if (session.getDeclaredContentHash() != null
                && (version.getContentHash() == null || !session.getDeclaredContentHash().equalsIgnoreCase(version.getContentHash().value()))) {
            throw new IllegalStateException(
                    String.format("MediaAssetVersion content hash does not match session declared hash %s",
                            session.getDeclaredContentHash())
            );
        }

        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + session.getId()));

        if (allocation.getStatus() != StorageKeyAllocationStatus.COMMITTED
                || allocation.getAllocatedByType() != StorageKeyAllocationType.ASSET_VERSION
                || !allocation.getAllocatedById().equals(version.getId())) {
            throw new IllegalStateException(
                    String.format("Storage allocation %s state is inconsistent with completed session: status=%s, owner=%s/%s",
                            allocation.getId(), allocation.getStatus(), allocation.getAllocatedByType(), allocation.getAllocatedById())
            );
        }

        if (!allocation.getProviderId().equals(session.getStorageProviderId())
                || !allocation.getBucketName().equals(session.getStorageBucketName())
                || !allocation.getStorageKey().equals(session.getStorageKey())) {
            throw new IllegalStateException(
                    String.format("Storage allocation %s provider/bucket/key (%s/%s/%s) does not match session (%s/%s/%s)",
                            allocation.getId(),
                            allocation.getProviderId(), allocation.getBucketName(), allocation.getStorageKey(),
                            session.getStorageProviderId(), session.getStorageBucketName(), session.getStorageKey())
            );
        }

        return session;
    }
}
