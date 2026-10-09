package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.MultipartUploadedPart;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationStatus;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
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
    public static final String METADATA_SESSION_ID = "session-id";
    public static final String METADATA_ALLOCATION_ID = "allocation-id";

    private final MediaUploadSessionRepositoryPort sessionRepositoryPort;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final ClockPort clockPort;
    private final PlatformTransactionManager transactionManager;
    private final MultipartStoragePort multipartStoragePort;

    @Autowired
    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) MultipartStoragePort multipartStoragePort
    ) {
        this.sessionRepositoryPort = Objects.requireNonNull(sessionRepositoryPort, "MediaUploadSessionRepositoryPort cannot be null.");
        this.storageKeyAllocationService = Objects.requireNonNull(storageKeyAllocationService, "StorageKeyAllocationService cannot be null.");
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.transactionManager = transactionManager;
        this.multipartStoragePort = multipartStoragePort;
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, transactionManager, null);
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, null, null);
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
}
