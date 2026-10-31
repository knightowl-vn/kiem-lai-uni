package com.universe.media.application.storage;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.exceptions.StorageUploadNotFoundException;
import com.universe.media.application.exceptions.UploadSessionInitiationConflictException;
import com.universe.media.application.exceptions.UploadSessionUnconfirmedInitiationException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionPage;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.MultipartAbortResult;
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
import com.universe.media.domain.UploadSessionCleanupStatus;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import com.universe.media.application.exceptions.StorageAmbiguousCompletionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    public static final Duration DEFAULT_CLEANUP_LEASE_DURATION = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PART_URL_EXPIRY = Duration.ofMinutes(15);
    public static final Duration MIN_PART_URL_EXPIRY = Duration.ofSeconds(10);
    public static final Duration MAX_PART_URL_EXPIRY = Duration.ofHours(1);
    public static final String METADATA_SESSION_ID = "session-id";
    public static final String METADATA_ALLOCATION_ID = "allocation-id";

    private final MediaUploadSessionRepositoryPort sessionRepositoryPort;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final ClockPort clockPort;
    private final PlatformTransactionManager transactionManager;
    private final Map<StorageProviderId, MultipartStoragePort> multipartStoragePorts;
    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Autowired
    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            List<MultipartStoragePort> multipartStoragePorts,
            @Autowired(required = false) MediaAssetRepositoryPort mediaAssetRepositoryPort,
            @Autowired(required = false) MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this.sessionRepositoryPort = Objects.requireNonNull(sessionRepositoryPort, "MediaUploadSessionRepositoryPort cannot be null.");
        this.storageKeyAllocationService = Objects.requireNonNull(storageKeyAllocationService, "StorageKeyAllocationService cannot be null.");
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.transactionManager = transactionManager;
        this.mediaAssetRepositoryPort = mediaAssetRepositoryPort;
        this.mediaAssetVersionRepositoryPort = mediaAssetVersionRepositoryPort;

        Map<StorageProviderId, MultipartStoragePort> map = new HashMap<>();
        if (multipartStoragePorts != null) {
            for (MultipartStoragePort port : multipartStoragePorts) {
                if (port == null) {
                    continue;
                }
                StorageProviderId providerId = port.providerId();
                if (providerId == null || providerId.value() == null || providerId.value().isBlank()) {
                    throw new IllegalStateException("MultipartStoragePort bean returned null or blank providerId: " + port.getClass().getName());
                }
                if (map.containsKey(providerId)) {
                    throw new IllegalStateException(
                            "Duplicate MultipartStoragePort registration detected for provider ID: " + providerId.value()
                    );
                }
                map.put(providerId, port);
            }
        }
        this.multipartStoragePorts = Collections.unmodifiableMap(map);
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager,
            MultipartStoragePort singleMultipartStoragePort,
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort
    ) {
        this(
                sessionRepositoryPort,
                storageKeyAllocationService,
                mediaStorageRoutingService,
                clockPort,
                transactionManager,
                singleMultipartStoragePort != null ? List.of(singleMultipartStoragePort) : Collections.emptyList(),
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort
        );
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager,
            MultipartStoragePort singleMultipartStoragePort
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, transactionManager, singleMultipartStoragePort, null, null);
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort,
            PlatformTransactionManager transactionManager
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, transactionManager, Collections.emptyList(), null, null);
    }

    public MediaUploadSessionService(
            MediaUploadSessionRepositoryPort sessionRepositoryPort,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageRoutingService mediaStorageRoutingService,
            ClockPort clockPort
    ) {
        this(sessionRepositoryPort, storageKeyAllocationService, mediaStorageRoutingService, clockPort, null, Collections.emptyList(), null, null);
    }

    private MultipartStoragePort resolveMultipartStoragePort(StorageProviderId providerId) {
        if (providerId == null) {
            throw new IllegalStateException("Storage provider ID cannot be null for multipart port resolution.");
        }
        MultipartStoragePort port = multipartStoragePorts.get(providerId);
        if (port == null) {
            throw new IllegalStateException("MultipartStoragePort is not configured or available for provider: " + providerId.value());
        }
        if (port.providerId() == null || !port.providerId().equals(providerId)) {
            throw new IllegalStateException(
                    String.format("MultipartStoragePort provider '%s' does not match session storage provider '%s'",
                            port.providerId() != null ? port.providerId().value() : "null", providerId.value())
            );
        }
        return port;
    }

    private boolean hasMultipartStoragePort() {
        return !multipartStoragePorts.isEmpty();
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
        StorageProviderId providerId = mediaStorageRoutingService.resolveWriteProvider(
                MediaType.VIDEO,
                consumerContext,
                normalizedClientTag
        );
        if (providerId == null) {
            throw new IllegalStateException(
                    "MediaStorageRoutingService resolved null provider for MediaType.VIDEO and tag: " + normalizedClientTag
            );
        }
        // Verify destination multipart port is configured and available BEFORE reserving allocation
        resolveMultipartStoragePort(providerId);

        String bucketName = mediaStorageRoutingService.resolveBucketName(providerId);
        StorageKey storageKey = mediaStorageRoutingService.generateStorageKey(
                MediaType.VIDEO,
                normalizedClientTag,
                null,
                1
        );

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

    @Transactional(readOnly = true)
    public MediaUploadSessionPage findCompletedSessions(
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            String mediaType,
            UUID targetEntityId,
            int page,
            int size
    ) {
        Objects.requireNonNull(initiatorPrincipalId, "initiatorPrincipalId cannot be null.");
        Objects.requireNonNull(consumerContext, "consumerContext cannot be null.");
        Objects.requireNonNull(targetEntityType, "targetEntityType cannot be null.");
        return sessionRepositoryPort.findCompletedSessions(
                initiatorPrincipalId,
                consumerContext,
                targetEntityType,
                mediaType,
                targetEntityId,
                page,
                size
        );
    }

    @Transactional(readOnly = true)
    public MediaUploadSessionPage findCompletedSessions(
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            int page,
            int size
    ) {
        return findCompletedSessions(initiatorPrincipalId, consumerContext, targetEntityType, null, targetEntityId, page, size);
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

        if (!hasMultipartStoragePort()) {
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

        // If the session was already INITIATED (idempotent retry), return directly without second initiation
        if (claimedSession.getStatus() == MediaUploadSessionStatus.INITIATED) {
            return claimedSession;
        }

        // Defensive check: guarantee no ambient transaction is active before invoking remote provider
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Multipart upload cannot be invoked while a database transaction is active.");
        }

        // =========================================================================
        // Provider — Outside MySQL Transaction: Invoke CreateMultipartUpload with bound metadata
        // =========================================================================
        String providerUploadId;
        try {
            Map<String, String> metadata = Map.of(
                    METADATA_SESSION_ID, claimedSession.getId().toString(),
                    METADATA_ALLOCATION_ID, claimedSession.getStorageAllocationId().toString()
            );
            MultipartStoragePort port = resolveMultipartStoragePort(claimedSession.getStorageProviderId());
            providerUploadId = port.initiateMultipartUpload(
                    claimedSession.getStorageKey(),
                    MimeType.of(claimedSession.getDeclaredMimeType()),
                    metadata
            );
        } catch (StorageInitiationRejectedException rejectionEx) {
            log.warn("Multipart initiation definitively rejected by provider for session {}. Releasing initiation claim to permit retry. error={}",
                    sessionId, rejectionEx.getMessage());
            InitiationClaimCompensationOutcome outcome;
            try {
                outcome = txTemplate.execute(status -> releaseInitiationClaimOnDefinitiveRejection(
                        sessionId,
                        claimedSession.getLeaseOwner(),
                        rejectionEx.getMessage()
                ));
            } catch (RuntimeException compensationEx) {
                log.error("Failed to commit initiation claim compensation after definitive rejection for session {}. Claim preserved as unconfirmed.",
                        sessionId, compensationEx);
                UploadSessionUnconfirmedInitiationException unconfirmedEx = new UploadSessionUnconfirmedInitiationException(
                        sessionId,
                        claimedSession.getLeaseOwner(),
                        String.format("Provider rejected initiation for upload session %s, but claim compensation transaction failed (%s). Claim preserved as unconfirmed pending reconciliation.",
                                sessionId, compensationEx.getMessage()),
                        compensationEx
                );
                unconfirmedEx.addSuppressed(rejectionEx);
                throw unconfirmedEx;
            }

            if (outcome != InitiationClaimCompensationOutcome.COMPENSATED) {
                log.error("Initiation claim compensation rejected for session {} (outcome: {}). Claim preserved as unconfirmed.",
                        sessionId, outcome);
                UploadSessionUnconfirmedInitiationException unconfirmedEx = new UploadSessionUnconfirmedInitiationException(
                        sessionId,
                        claimedSession.getLeaseOwner(),
                        String.format("Provider rejected initiation for upload session %s, but claim compensation could not be applied (outcome: %s). Claim preserved as unconfirmed pending reconciliation.",
                                sessionId, outcome)
                );
                unconfirmedEx.addSuppressed(rejectionEx);
                throw unconfirmedEx;
            }

            log.info("Successfully compensated initiation claim for session {} following provider definitive rejection. Session is safely retryable.",
                    sessionId);
            throw rejectionEx;
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
            int uploadIdLength = providerUploadId != null ? providerUploadId.length() : 0;
            log.error("TX-B persistence failed after successful provider initiation for session {}. providerId={}, operation=INITIATE_TX_B, failureCategory=PERSISTENCE_FAILURE, uploadIdLength={}. Preserving state for reconciliation.",
                    sessionId, claimedSession.getStorageProviderId(), uploadIdLength, txBException);
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
            throw new UploadSessionInitiationConflictException(
                    sessionId,
                    session.getLeaseOwner(),
                    String.format("Concurrent initiation in progress for upload session %s (lease held by %s)",
                            sessionId, session.getLeaseOwner())
            );
        }
        if (session.hasUnconfirmedInitiationClaim(now)) {
            // "Distinguish expired lease from confirmed worker termination."
            // "Never assume remote initiation failed merely because providerUploadId was not persisted."
            // "Never automatically create a second multipart upload under the same reserved storage key while an earlier initiation may have succeeded."
            throw new UploadSessionUnconfirmedInitiationException(
                    sessionId,
                    session.getLeaseOwner(),
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

    public enum InitiationClaimCompensationOutcome {
        COMPENSATED,
        TOKEN_MISMATCH,
        INVALID_STATUS
    }

    private InitiationClaimCompensationOutcome releaseInitiationClaimOnDefinitiveRejection(UUID sessionId, String expectedClaimToken, String errorDetail) {
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Upload session not found for ID: " + sessionId));
        if (session.getStatus() != MediaUploadSessionStatus.ALLOCATED) {
            log.warn("Cannot release initiation claim on session {} because status is {}", sessionId, session.getStatus());
            return InitiationClaimCompensationOutcome.INVALID_STATUS;
        }
        if (session.getLeaseOwner() == null || !session.getLeaseOwner().equals(expectedClaimToken)) {
            log.warn("Cannot release initiation claim on session {} because lease owner changed or token mismatched",
                    sessionId);
            return InitiationClaimCompensationOutcome.TOKEN_MISMATCH;
        }
        Instant now = clockPort.now();
        session.releaseInitiationClaim(expectedClaimToken, now);
        sessionRepositoryPort.save(session);
        return InitiationClaimCompensationOutcome.COMPENSATED;
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
     * Issues a B2-compatible presigned upload URL for an individual part of an active upload session.
     *
     * <p>Enforces:
     * <ul>
     *     <li>Session existence, principal authorization, and eligible status ({@link MediaUploadSessionStatus#INITIATED});</li>
     *     <li>Session non-expiration and imminence guard (&ge; {@link #MIN_PART_URL_EXPIRY});</li>
     *     <li>Part number validation (1 .. expectedPartCount);</li>
     *     <li>Presigned URL expiration bounds (capped to session remaining TTL);</li>
     *     <li>Storage provider and reserved storage allocation binding verification;</li>
     *     <li>Idempotent refresh without mutating session state;</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public String generatePresignedPartUploadUrl(
            UUID sessionId,
            int partNumber,
            String requestingPrincipalId,
            Duration requestedExpiry
    ) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        requireNonBlank(requestingPrincipalId, "requestingPrincipalId");

        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        // 1. Authorize requesting principal
        if (!session.getInitiatorPrincipalId().equals(requestingPrincipalId)) {
            throw new IllegalStateException(
                    String.format("Principal '%s' is not authorized to generate presigned upload URLs for session '%s' owned by '%s'",
                            requestingPrincipalId, sessionId, session.getInitiatorPrincipalId())
            );
        }

        // 2. Validate eligible session status: only INITIATED allows uploading parts
        if (session.getStatus() != MediaUploadSessionStatus.INITIATED) {
            throw new IllegalStateException(
                    String.format("Cannot generate presigned part upload URL for session %s in status %s. Session must be in INITIATED status.",
                            sessionId, session.getStatus())
            );
        }

        Instant now = clockPort.now();
        Objects.requireNonNull(now, "Clock timestamp cannot be null.");

        // 3. Validate session expiry
        if (session.getExpiresAt().isBefore(now) || session.getExpiresAt().equals(now)) {
            throw new IllegalStateException("Upload session has expired: " + sessionId);
        }

        // 4. Validate part number bounds (1 .. expectedPartCount)
        if (partNumber < 1 || partNumber > session.getExpectedPartCount()) {
            throw new IllegalArgumentException(
                    String.format("Part number %d is out of bounds for session %s. Expected between 1 and %d.",
                            partNumber, sessionId, session.getExpectedPartCount())
            );
        }

        // 5. Validate provider upload ID is present
        if (session.getProviderUploadId() == null || session.getProviderUploadId().isBlank()) {
            throw new IllegalStateException("Upload session has missing or blank providerUploadId: " + sessionId);
        }

        // 6. Validate expiry bounds and cap to session remaining TTL
        Duration effectiveExpiry = (requestedExpiry != null) ? requestedExpiry : DEFAULT_PART_URL_EXPIRY;
        if (effectiveExpiry.compareTo(MIN_PART_URL_EXPIRY) < 0 || effectiveExpiry.compareTo(MAX_PART_URL_EXPIRY) > 0) {
            throw new IllegalArgumentException(
                    String.format("Presigned part upload URL expiry must be between %s and %s, got: %s",
                            MIN_PART_URL_EXPIRY, MAX_PART_URL_EXPIRY, effectiveExpiry)
            );
        }

        Duration remainingTtl = Duration.between(now, session.getExpiresAt());
        if (remainingTtl.compareTo(MIN_PART_URL_EXPIRY) < 0) {
            throw new IllegalStateException("Upload session is expiring imminently (< " + MIN_PART_URL_EXPIRY + "): " + sessionId);
        }
        if (remainingTtl.compareTo(effectiveExpiry) < 0) {
            effectiveExpiry = remainingTtl;
        }

        // 7. Validate reserved storage allocation binding
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));
        validateAllocationBinding(allocation, session);

        // 8. Validate storage provider
        if (!MediaUploadSession.SUPPORTED_VIDEO_PROVIDERS.contains(session.getStorageProviderId())) {
            throw new IllegalStateException("Storage provider mismatch for upload session " + sessionId + ": expected supported video provider, got " + session.getStorageProviderId().value());
        }

        // 9. Delegate to multipartStoragePort
        MultipartStoragePort port = resolveMultipartStoragePort(session.getStorageProviderId());

        try {
            String presignedUrl = port.generatePresignedPartUploadUrl(
                    session.getStorageKey(),
                    session.getProviderUploadId(),
                    partNumber,
                    effectiveExpiry
            );
            if (presignedUrl == null || presignedUrl.isBlank()) {
                throw new IllegalStateException("Storage provider returned empty or blank presigned part upload URL for session " + sessionId + ", part " + partNumber);
            }
            return presignedUrl;
        } catch (RuntimeException e) {
            log.warn("Failed to generate presigned part upload URL for session {}, part {}: provider presigning failed [{}]",
                    sessionId, partNumber, e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * Issues a B2-compatible presigned upload URL for an individual part using the default expiration duration.
     */
    @Transactional(readOnly = true)
    public String generatePresignedPartUploadUrl(
            UUID sessionId,
            int partNumber,
            String requestingPrincipalId
    ) {
        return generatePresignedPartUploadUrl(sessionId, partNumber, requestingPrincipalId, null);
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

    /**
     * Safely expires an active upload session (ALLOCATED or INITIATED) whose expiresAt has elapsed.
     * Transitions status to EXPIRED (setting cleanupStatus to PENDING if providerUploadId is present)
     * and tombstones the associated storage key allocation.
     *
     * <p>Fails safely (returns empty) if session is already completed, completing, or cleaned up,
     * or if completion intent or registered asset evidence is present.
     *
     * @param sessionId upload session to expire
     * @return updated session if expired, empty if session was not eligible for automated expiration
     */
    @Transactional
    public Optional<MediaUploadSession> expireSession(UUID sessionId) {
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        Instant now = clockPort.now();

        // 1. Must be currently active (ALLOCATED or INITIATED)
        if (session.getStatus() != MediaUploadSessionStatus.ALLOCATED
                && session.getStatus() != MediaUploadSessionStatus.INITIATED) {
            return Optional.empty();
        }

        // 2. Must be past expiresAt
        if (session.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        // 3. Must not hold active initiation lease
        if (session.getStatus() == MediaUploadSessionStatus.ALLOCATED && session.getLeaseOwner() != null) {
            if (session.getLeaseExpiresAt() != null && session.getLeaseExpiresAt().isAfter(now)) {
                return Optional.empty();
            }
        }

        // 4. Must not have active completion intent or registered asset
        if (session.getDeclaredContentHash() != null || session.getManifestHash() != null
                || session.getRegisteredAssetId() != null || session.getCompletedAt() != null) {
            log.warn("Upload session {} is past expiresAt but has completion evidence; skipping automated expiration.", sessionId);
            return Optional.empty();
        }

        // 5. Transition to EXPIRED
        session.expire(now);

        // 6. Save and tombstone allocation
        MediaUploadSession savedSession = sessionRepositoryPort.save(session);
        if (savedSession.getStorageAllocationId() != null) {
            storageKeyAllocationService.tombstoneAllocation(savedSession.getStorageAllocationId());
        }

        return Optional.of(savedSession);
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

        if (!hasMultipartStoragePort()) {
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

        // If the session was already COMPLETED (idempotent retry), return directly without second completion
        if (claimedSession.getStatus() == MediaUploadSessionStatus.COMPLETED) {
            return claimedSession;
        }

        // Defensive check: guarantee no ambient transaction is active before invoking remote provider
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Multipart completion operations cannot be invoked while a database transaction is active.");
        }

        // =========================================================================
        // Provider Verification & Complete — Outside MySQL Transaction
        // =========================================================================
        MultipartStoragePort port = resolveMultipartStoragePort(claimedSession.getStorageProviderId());
        List<MultipartUploadedPart> providerParts = null;
        MultipartCompletedObject completedObject = null;
        MultipartObjectMetadata headMetadata = null;
        boolean providerReportedNoSuchUpload = false;

        try {
            providerParts = port.listUploadedParts(
                    claimedSession.getStorageKey(),
                    claimedSession.getProviderUploadId()
            );
        } catch (StorageUploadNotFoundException noSuchUploadEx) {
            log.info("Provider reported NoSuchUpload for upload session {}. Entering guarded recovery check.", sessionId);
            providerReportedNoSuchUpload = true;
        } catch (StorageException genericListException) {
            if (isProviderConfirmedNoSuchUpload(genericListException)) {
                log.info("Provider reported NoSuchUpload for upload session {}. Entering guarded recovery check.", sessionId);
                providerReportedNoSuchUpload = true;
            } else {
                log.warn("listUploadedParts failed with storage error for session {} (error: {}). Preserving state for reconciliation.",
                        sessionId, genericListException.getClass().getSimpleName());
                throw genericListException;
            }
        }

        // SCENARIO A: Complete Succeeded, MySQL TX-B Failed or Interrupted
        // On retry, listUploadedParts returned provider-confirmed NoSuchUpload.
        // Perform a guarded HEAD inspection of the exact reserved object.
        if (providerReportedNoSuchUpload) {
            try {
                headMetadata = port.inspectObjectMetadata(claimedSession.getStorageKey());
            } catch (StorageObjectNotFoundException headNotFoundEx) {
                log.warn("NoSuchUpload on listParts and object missing on storage for session {}. Failing closed.", sessionId);
                throw headNotFoundEx;
            } catch (StorageException headEx) {
                log.warn("inspectObjectMetadata failed during Scenario A recovery for session {} (error: {}). Preserving state for reconciliation.",
                        sessionId, headEx.getClass().getSimpleName());
                throw headEx;
            }

            verifyCompletedObjectProvenance(
                    claimedSession,
                    headMetadata,
                    null,
                    null,
                    headMetadata.versionId(),
                    true
            );

            StorageLocation location = StorageLocation.of(claimedSession.getStorageProviderId(), claimedSession.getStorageKey());
            completedObject = new MultipartCompletedObject(location, headMetadata.eTag(), headMetadata.versionId());
            log.info("Successfully recovered completed object from storage for session {} via Scenario A (NoSuchUpload on listParts).",
                    sessionId);
        }

        // SCENARIO C (Normal Completion) or SCENARIO B (Ambiguous Remote Completion Probe)
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
                completedObject = port.completeMultipartUpload(
                        claimedSession.getStorageKey(),
                        claimedSession.getProviderUploadId(),
                        partsToComplete
                );
            } catch (StorageUploadNotFoundException | StorageAmbiguousCompletionException ambiguousEx) {
                // SCENARIO B: Ambiguous completion failure: upload already assembled or provider 5xx / timeout.
                log.warn("CompleteMultipartUpload threw ambiguous completion error {} for session {}: probing storage to determine if object was assembled.",
                        ambiguousEx.getClass().getSimpleName(), sessionId);
                try {
                    headMetadata = port.inspectObjectMetadata(claimedSession.getStorageKey());
                } catch (Exception probeException) {
                    log.warn("CompleteMultipartUpload probe failed for session {} (error: {}). Preserving state for reconciliation.",
                            sessionId, probeException.getClass().getSimpleName());
                    ambiguousEx.addSuppressed(probeException);
                    throw ambiguousEx;
                }

                // Object was found on storage. Verify provenance strictly before proceeding to registration.
                try {
                    verifyCompletedObjectProvenance(
                            claimedSession,
                            headMetadata,
                            null,
                            null,
                            headMetadata.versionId(),
                            true
                    );
                    StorageLocation location = StorageLocation.of(claimedSession.getStorageProviderId(), claimedSession.getStorageKey());
                    completedObject = new MultipartCompletedObject(location, headMetadata.eTag(), headMetadata.versionId());
                    log.info("Successfully recovered completed object from storage for session {} via Scenario B (probe after completion exception).",
                            sessionId);
                } catch (Exception provenanceException) {
                    log.warn("CompleteMultipartUpload recovered object failed provenance verification for session {} (error: {}). Failing closed.",
                            sessionId, provenanceException.getClass().getSimpleName());
                    ambiguousEx.addSuppressed(provenanceException);
                    throw ambiguousEx;
                }
            } catch (StorageException deterministicEx) {
                // Deterministic error (400, 403, etc.) or unknown storage exception: fail closed immediately without probing storage
                log.warn("CompleteMultipartUpload failed with deterministic storage error for session {} (error: {}). Failing closed without storage probe.",
                        sessionId, deterministicEx.getClass().getSimpleName());
                throw deterministicEx;
            }
        }

        // =========================================================================
        // Provenance Verification — Outside MySQL Transaction
        // =========================================================================
        if (headMetadata == null) {
            try {
                headMetadata = port.inspectObjectMetadata(claimedSession.getStorageKey());
            } catch (RuntimeException headException) {
                log.warn("inspectObjectMetadata failed after completion for session {} (error: {}). Preserving state for reconciliation.",
                        sessionId, headException.getClass().getSimpleName());
                throw headException;
            }

            verifyCompletedObjectProvenance(
                    claimedSession,
                    headMetadata,
                    completedObject.location(),
                    completedObject.eTag(),
                    completedObject.versionId(),
                    false
            );
        }

        // =========================================================================
        // TX-B — MySQL: Atomically register MediaAsset & MediaAssetVersion, commit allocation, mark COMPLETED
        // =========================================================================
        try {
            return txTemplate.execute(status -> executeTxBRegistration(sessionId, requestingPrincipalId, claimToken));
        } catch (RuntimeException txBException) {
            log.error("TX-B persistence failed after successful provider completion for session {} (error: {}). Preserving state for reconciliation.",
                    sessionId, txBException.getClass().getSimpleName());
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
            String expectedVersionId,
            boolean isRecovery
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

        // Provider identity check (where available from adapter; mandatory for recovery)
        if (isRecovery) {
            if (headMetadata.providerId() == null || headMetadata.providerId().value().isBlank()) {
                throw new IllegalStateException("Completed object providerId missing on storage metadata during recovery.");
            }
            if (!session.getStorageProviderId().equals(headMetadata.providerId())) {
                throw new IllegalStateException(
                        String.format("Completed object provider mismatch: expected %s, got %s",
                                session.getStorageProviderId(), headMetadata.providerId())
                );
            }
        } else {
            if (headMetadata.providerId() != null && !session.getStorageProviderId().equals(headMetadata.providerId())) {
                throw new IllegalStateException(
                        String.format("Completed object provider mismatch: expected %s, got %s",
                                session.getStorageProviderId(), headMetadata.providerId())
                );
            }
        }

        // Bucket name check (where available from adapter; mandatory for recovery)
        if (isRecovery) {
            if (headMetadata.bucketName() == null || headMetadata.bucketName().isBlank()) {
                throw new IllegalStateException("Completed object bucketName missing on storage metadata during recovery.");
            }
            if (!session.getStorageBucketName().equals(headMetadata.bucketName())) {
                throw new IllegalStateException(
                        String.format("Completed object bucket mismatch: expected %s, got %s",
                                session.getStorageBucketName(), headMetadata.bucketName())
                );
            }
        } else {
            if (headMetadata.bucketName() != null && !session.getStorageBucketName().equals(headMetadata.bucketName())) {
                throw new IllegalStateException(
                        String.format("Completed object bucket mismatch: expected %s, got %s",
                                session.getStorageBucketName(), headMetadata.bucketName())
                );
            }
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

        // ETag check: present and non-blank after normalization
        if (headMetadata.eTag() == null || headMetadata.eTag().trim().isBlank()) {
            throw new IllegalStateException("Completed object ETag is null or blank on storage metadata.");
        }
        String normHead;
        try {
            normHead = MultipartManifestCanonicalizer.normalizeETag(headMetadata.eTag());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Completed object ETag is invalid on storage metadata: " + e.getMessage(), e);
        }
        if (normHead == null || normHead.isBlank()) {
            throw new IllegalStateException("Completed object ETag is null or blank on storage metadata.");
        }

        // ETag check against provider completion result to protect against concurrent overwrite races
        if (expectedETag != null && !expectedETag.isBlank()) {
            String normExpected;
            try {
                normExpected = MultipartManifestCanonicalizer.normalizeETag(expectedETag);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Expected completion ETag is invalid: " + e.getMessage(), e);
            }
            if (!normHead.equals(normExpected)) {
                throw new IllegalStateException(
                        String.format("Completed object ETag mismatch (potential concurrent overwrite): expected %s, got %s",
                                expectedETag, headMetadata.eTag())
                );
            }
        }

        // Version ID check: when nonblank versionId is expected from provider completion result,
        // missing/blank HEAD versionId or versionId mismatch must fail closed.
        // Exception: Cloudflare R2 does not support S3 bucket object versioning and omits
        // versionId on HeadObject. For R2 providers, an absent HEAD versionId is verified provider
        // behavior and provenance is anchored by storage key, allocation metadata bindings,
        // content length, and ETag. If HEAD versionId is present on R2, it must still match.
        if (expectedVersionId != null && !expectedVersionId.isBlank()) {
            if (headMetadata.versionId() == null || headMetadata.versionId().isBlank()) {
                if (isProviderOmittingHeadVersionId(session.getStorageProviderId())) {
                    log.info("Completed object versionId is omitted on HEAD metadata for provider {} (expected: {}). " +
                                    "Provenance verified by storage key, allocation metadata bindings, content length, and ETag.",
                            session.getStorageProviderId(), expectedVersionId);
                } else {
                    throw new IllegalStateException(
                            String.format("Completed object versionId missing on HEAD metadata: expected %s, got null/blank",
                                    expectedVersionId)
                    );
                }
            } else if (!expectedVersionId.equals(headMetadata.versionId())) {
                throw new IllegalStateException(
                        String.format("Completed object versionId mismatch (potential concurrent overwrite): expected %s, got %s",
                                expectedVersionId, headMetadata.versionId())
                );
            }
        }
    }

    static boolean isProviderOmittingHeadVersionId(StorageProviderId providerId) {
        if (providerId == null || providerId.value() == null) {
            return false;
        }
        return "r2-movie".equalsIgnoreCase(providerId.value().trim());
    }

    private static boolean isProviderConfirmedNoSuchUpload(Throwable ex) {
        if (ex == null) {
            return false;
        }
        Throwable current = ex;
        while (current != null) {
            if (current instanceof StorageUploadNotFoundException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        MediaVisibility initialVisibility = resolveInitialVisibility(session);
        MediaAsset asset = MediaAsset.registerInitial(
                assetId,
                session.getMediaType(),
                initialVisibility,
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

    static boolean isValidScreenClientTag(String clientTag) {
        return MediaStorageRoutingService.isValidScreenClientTag(clientTag);
    }

    static MediaVisibility resolveInitialVisibility(MediaUploadSession session) {
        String consumerContext = session.getConsumerContext();
        String targetEntityType = session.getTargetEntityType();
        String clientTag = session.getClientTag();

        boolean isScreenContext = "screen".equalsIgnoreCase(consumerContext);
        boolean hasValidScreenTag = isValidScreenClientTag(clientTag);

        if (isScreenContext) {
            if (!"SCREEN_ENTRY".equalsIgnoreCase(targetEntityType)) {
                throw new IllegalStateException(
                        String.format("Unsupported or ambiguous Screen target entity type '%s' for session %s.",
                                targetEntityType, session.getId())
                );
            }
            if (session.getTargetEntityId() == null) {
                throw new IllegalStateException(
                        String.format("Screen upload session %s has null targetEntityId.", session.getId())
                );
            }
            if (!hasValidScreenTag) {
                throw new IllegalStateException(
                        String.format("Unsupported or malformed Screen client tag '%s' for session %s.",
                                clientTag, session.getId())
                );
            }
            if (session.getMediaType() != MediaType.VIDEO) {
                throw new IllegalStateException(
                        String.format("Unsupported Screen media type '%s' for session %s. Only VIDEO is permitted.",
                                session.getMediaType(), session.getId())
                );
            }
            return MediaVisibility.PRIVATE;
        }

        if (hasValidScreenTag || (clientTag != null && clientTag.toLowerCase().startsWith("screen-"))) {
            throw new IllegalStateException(
                    String.format("Ambiguous Screen client tag '%s' detected for non-screen consumer context '%s' on session %s.",
                            clientTag, consumerContext, session.getId())
            );
        }

        return MediaVisibility.PUBLIC;
    }

    // =============================================================================================
    // Guarded Single-Session Abort Orchestration (S2D-2B2A)
    // =============================================================================================

    /**
     * Executes a transactionally guarded multipart abort for a single upload session, adhering strictly to
     * TX-A (durable cleanup claim) -&gt; Provider abort (outside DB transaction) -&gt; TX-B (outcome quarantine) ordering.
     * <p>
     * <b>Important Safety Boundaries (MS-08E2 / S2D-2B2A):</b>
     * <ul>
     *     <li>Provider acknowledgment is not proof of physical cleanup. Both {@link MultipartAbortResult#ABORT_ACCEPTED}
     *         and {@link MultipartAbortResult#UPLOAD_ALREADY_ABSENT} (as well as unexpired provider errors/timeouts)
     *         transition the session to {@link UploadSessionCleanupStatus#QUARANTINED} for later physical reconciliation (S2D-3).</li>
     *     <li>Under no circumstance does this operation transition a session to {@link MediaUploadSessionStatus#CLEANED_UP}
     *         or {@link UploadSessionCleanupStatus#COMPLETED}.</li>
     *     <li>Under no circumstance does this operation mutate, release, or tombstone {@link StorageKeyAllocation}.</li>
     *     <li>If the cleanup lease expires during the remote provider call or tokens mismatch, the operation fails closed
     *         without mutating database state, preserving durable evidence for offline reconciliation (S2D-3).</li>
     * </ul>
     *
     * @param sessionId the upload session to abort
     * @return the quarantined upload session after outcome persistence
     * @throws IllegalStateException if ambient transaction active, eligibility prerequisites violated, or lease/fence expired
     * @throws IllegalArgumentException if session is not found
     * @throws NullPointerException if sessionId is null
     */
    @Transactional(propagation = Propagation.NEVER)
    public MediaUploadSession executeGuardedMultipartAbort(UUID sessionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("executeGuardedMultipartAbort cannot be invoked from an active ambient transaction.");
        }
        Objects.requireNonNull(sessionId, "Session ID cannot be null.");

        if (!hasMultipartStoragePort()) {
            throw new IllegalStateException("MultipartStoragePort is not configured or available.");
        }
        if (transactionManager == null) {
            throw new IllegalStateException("PlatformTransactionManager is required for guarded abort orchestration.");
        }

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String claimToken = MediaUploadSession.CLEANUP_CLAIM_PREFIX + UUID.randomUUID();

        // =========================================================================
        // TX-A — MySQL: Authorize, validate state & allocation, claim cleanup lease, commit
        // =========================================================================
        CleanupTargetContext context = txTemplate.execute(status -> executeTxACleanupClaim(sessionId, claimToken));
        if (context == null) {
            throw new IllegalStateException("TX-A cleanup claim returned null context for session: " + sessionId);
        }

        // Defensive check: guarantee no ambient transaction is active before invoking remote provider
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider abort operations cannot be invoked while a database transaction is active.");
        }

        // =========================================================================
        // Provider Phase — Outside MySQL Transaction
        // =========================================================================
        MultipartAbortResult abortResult = null;
        Exception providerException = null;

        try {
            MultipartStoragePort port = resolveMultipartStoragePort(context.storageProviderId());
            abortResult = port.abortMultipartUpload(context.storageKey(), context.providerUploadId());
        } catch (Exception ex) {
            providerException = ex;
        }

        // =========================================================================
        // Outcome Classification & Pre-TX-B Lease Check
        // =========================================================================
        Instant now = clockPort.now();
        if (!now.isBefore(context.leaseExpiresAt())) {
            log.warn("Cleanup lease expired during provider abort call for session {}: lease expired at {}, now is {}. Failing closed.",
                    sessionId, context.leaseExpiresAt(), now);
            throw new IllegalStateException(
                    String.format("Cleanup lease expired during provider abort call for session %s (lease expired at %s, now %s). Failing closed.",
                            sessionId, context.leaseExpiresAt(), now)
            );
        }

        String quarantineReason;
        if (providerException != null) {
            log.warn("Provider multipart abort failed for upload session {} with {}. Quarantining for reconciliation.",
                    sessionId, providerException.getClass().getSimpleName());
            quarantineReason = classifyProviderExceptionDiagnostic(providerException);
        } else if (abortResult == MultipartAbortResult.ABORT_ACCEPTED) {
            quarantineReason = "Provider abort acknowledged; physical cleanup verification pending (S2D-3)";
        } else if (abortResult == MultipartAbortResult.UPLOAD_ALREADY_ABSENT) {
            quarantineReason = "Provider reported upload already absent (NoSuchUpload); unresolved upload-history ambiguity pending reconciliation (S2D-3)";
        } else {
            throw new IllegalStateException("Provider abortMultipartUpload returned null or unrecognized result for session: " + sessionId);
        }

        // =========================================================================
        // TX-B — MySQL: Conservative outcome persistence (QUARANTINED)
        // =========================================================================
        try {
            final String finalReason = quarantineReason;
            return txTemplate.execute(status -> executeTxBOutcomeQuarantine(context, finalReason));
        } catch (RuntimeException txBException) {
            log.error("TX-B outcome persistence failed for upload session {}. Preserving state for reconciliation.",
                    sessionId, txBException);
            throw txBException;
        }
    }

    private CleanupTargetContext executeTxACleanupClaim(UUID sessionId, String claimToken) {
        MediaUploadSession session = sessionRepositoryPort.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found for ID: " + sessionId));

        Instant now = clockPort.now();

        // 1. Lifecycle eligibility: strictly ABORTED or EXPIRED
        if (session.getStatus() != MediaUploadSessionStatus.ABORTED && session.getStatus() != MediaUploadSessionStatus.EXPIRED) {
            throw new IllegalStateException(
                    String.format("Upload session %s is not eligible for cleanup abort: lifecycle status must be ABORTED or EXPIRED, found %s",
                            sessionId, session.getStatus())
            );
        }

        // 2. Cleanup status eligibility: strictly PENDING (reject FAILED, QUARANTINED, IN_PROGRESS, COMPLETED, NONE)
        if (session.getCleanupStatus() != UploadSessionCleanupStatus.PENDING) {
            throw new IllegalStateException(
                    String.format("Upload session %s is not eligible for cleanup abort: cleanup status must be PENDING, found %s",
                            sessionId, session.getCleanupStatus())
            );
        }

        // 3. Reject completion intent markers and registered asset evidence
        if (session.getDeclaredContentHash() != null) {
            throw new IllegalStateException("Upload session " + sessionId + " is not eligible for cleanup abort: declaredContentHash is present.");
        }
        if (session.getManifestHash() != null) {
            throw new IllegalStateException("Upload session " + sessionId + " is not eligible for cleanup abort: manifestHash is present.");
        }
        if (session.getRegisteredAssetId() != null) {
            throw new IllegalStateException("Upload session " + sessionId + " is not eligible for cleanup abort: registeredAssetId is present.");
        }
        if (session.getCompletedAt() != null) {
            throw new IllegalStateException("Upload session " + sessionId + " is not eligible for cleanup abort: completedAt is present.");
        }

        // 4. Validate nonblank persisted provider upload ID
        if (session.getProviderUploadId() == null || session.getProviderUploadId().isBlank()) {
            throw new IllegalStateException("Upload session " + sessionId + " is not eligible for cleanup abort: providerUploadId is missing or blank.");
        }

        // 5. Reject unresolved or active lease (even if expired in past)
        if (session.getLeaseOwner() != null || session.getLeaseExpiresAt() != null) {
            throw new IllegalStateException(
                    String.format("Upload session %s has unresolved lease (owner: %s, expiresAt: %s); cannot claim for cleanup abort.",
                            sessionId, session.getLeaseOwner(), session.getLeaseExpiresAt())
            );
        }

        // 6. Validate storage provider identity and authoritative configured bucket binding
        if (!MediaUploadSession.SUPPORTED_VIDEO_PROVIDERS.contains(session.getStorageProviderId())) {
            throw new IllegalStateException(
                    String.format("Storage provider mismatch for upload session %s: expected supported video provider, got '%s'",
                            sessionId, session.getStorageProviderId().value())
            );
        }
        MultipartStoragePort port = resolveMultipartStoragePort(session.getStorageProviderId());
        if (port.providerId() == null || !port.providerId().equals(session.getStorageProviderId())) {
            throw new IllegalStateException(
                    String.format("MultipartStoragePort provider '%s' does not match session storage provider '%s' for session %s",
                            port.providerId() != null ? port.providerId().value() : "null",
                            session.getStorageProviderId().value(), sessionId)
            );
        }
        if (session.getStorageBucketName() == null
                || session.getStorageBucketName().isBlank()
                || StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL.equals(session.getStorageBucketName())) {
            throw new IllegalStateException("Upload session " + sessionId + " must have authentic non-sentinel bucket name for cleanup abort.");
        }
        String configuredBucket = mediaStorageRoutingService.resolveBucketName(session.getStorageProviderId());
        if (configuredBucket == null || !configuredBucket.equals(session.getStorageBucketName())) {
            throw new IllegalStateException(
                    String.format("Upload session %s bucket '%s' does not match authoritative configured provider bucket '%s'",
                            sessionId, session.getStorageBucketName(), configuredBucket)
            );
        }
        String adapterBucket = port.configuredBucketName();
        if (adapterBucket == null || adapterBucket.isBlank()) {
            throw new IllegalStateException(
                    String.format("MultipartStoragePort provider '%s' has null or blank configured bucket name for session %s",
                            port.providerId().value(), sessionId)
            );
        }
        if (!adapterBucket.equals(configuredBucket)) {
            throw new IllegalStateException(
                    String.format("MultipartStoragePort configured bucket '%s' does not match authoritative routing bucket '%s' for session %s",
                            adapterBucket, configuredBucket, sessionId)
            );
        }
        if (!adapterBucket.equals(session.getStorageBucketName())) {
            throw new IllegalStateException(
                    String.format("MultipartStoragePort configured bucket '%s' does not match upload session bucket '%s' for session %s",
                            adapterBucket, session.getStorageBucketName(), sessionId)
            );
        }
        if (session.getStorageKey() == null) {
            throw new IllegalStateException("Upload session " + sessionId + " has null storage key.");
        }

        // 7. Validate storage allocation binding and ownership
        if (session.getStorageAllocationId() == null) {
            throw new IllegalStateException("Upload session " + sessionId + " has null storage allocation ID.");
        }
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found for session: " + sessionId));
        validateAllocationBindingForCleanup(allocation, session, configuredBucket);

        // 8. Claim cleanup lease and persist
        Instant leaseExpiresAt = now.plus(DEFAULT_CLEANUP_LEASE_DURATION);
        session.claimCleanup(claimToken, leaseExpiresAt, now);
        MediaUploadSession savedSession = sessionRepositoryPort.save(session);

        return new CleanupTargetContext(
                savedSession.getId(),
                savedSession.getStorageProviderId(),
                savedSession.getStorageBucketName(),
                savedSession.getStorageKey(),
                savedSession.getProviderUploadId(),
                claimToken,
                leaseExpiresAt,
                savedSession.getStorageAllocationId()
        );
    }

    private MediaUploadSession executeTxBOutcomeQuarantine(CleanupTargetContext context, String quarantineReason) {
        MediaUploadSession session = sessionRepositoryPort.findById(context.sessionId())
                .orElseThrow(() -> new IllegalStateException("Upload session not found in TX-B: " + context.sessionId()));

        Instant now = clockPort.now();

        // 1. Verify exact claim token
        if (session.getLeaseOwner() == null || !session.getLeaseOwner().equals(context.claimToken())) {
            throw new IllegalStateException(
                    String.format("TX-B abort claim token mismatch for session %s: expected '%s', found '%s'",
                            context.sessionId(), context.claimToken(), session.getLeaseOwner())
            );
        }

        // 2. Verify cleanup lease has not expired
        if (session.isCleanupLeaseExpired(now)) {
            throw new IllegalStateException(
                    String.format("TX-B abort cleanup lease expired for session %s (lease expires at %s, now %s). Failing closed.",
                            context.sessionId(), session.getLeaseExpiresAt(), now)
            );
        }

        // 3. Verify cleanup status is IN_PROGRESS
        if (session.getCleanupStatus() != UploadSessionCleanupStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    String.format("TX-B abort cleanup status is not IN_PROGRESS for session %s (found: %s)",
                            context.sessionId(), session.getCleanupStatus())
            );
        }

        // 4. Verify lifecycle status remains ABORTED or EXPIRED
        if (session.getStatus() != MediaUploadSessionStatus.ABORTED && session.getStatus() != MediaUploadSessionStatus.EXPIRED) {
            throw new IllegalStateException(
                    String.format("TX-B abort lifecycle status is not ABORTED or EXPIRED for session %s (found: %s)",
                            context.sessionId(), session.getStatus())
            );
        }

        // 5. Verify provider and bucket binding remain unchanged and match authoritative config
        if (!session.getStorageProviderId().equals(context.storageProviderId())) {
            throw new IllegalStateException(
                    String.format("TX-B abort storage provider changed for session %s: expected '%s', found '%s'",
                            context.sessionId(), context.storageProviderId().value(), session.getStorageProviderId().value())
            );
        }
        if (!session.getStorageBucketName().equals(context.storageBucketName())) {
            throw new IllegalStateException(
                    String.format("TX-B abort storage bucket changed for session %s: expected '%s', found '%s'",
                            context.sessionId(), context.storageBucketName(), session.getStorageBucketName())
            );
        }
        MultipartStoragePort port = resolveMultipartStoragePort(session.getStorageProviderId());
        if (port.providerId() == null || !port.providerId().equals(session.getStorageProviderId())) {
            throw new IllegalStateException(
                    String.format("TX-B abort MultipartStoragePort provider '%s' does not match session provider '%s' for session %s",
                            port.providerId() != null ? port.providerId().value() : "null", session.getStorageProviderId().value(), context.sessionId())
            );
        }
        String configuredBucket = mediaStorageRoutingService.resolveBucketName(session.getStorageProviderId());
        if (configuredBucket == null || !configuredBucket.equals(session.getStorageBucketName())) {
            throw new IllegalStateException(
                    String.format("TX-B abort session bucket '%s' does not match authoritative configured bucket '%s' for session %s",
                            session.getStorageBucketName(), configuredBucket, context.sessionId())
            );
        }
        String adapterBucket = port.configuredBucketName();
        if (adapterBucket == null || adapterBucket.isBlank()) {
            throw new IllegalStateException(
                    String.format("TX-B abort MultipartStoragePort provider '%s' has null or blank configured bucket name for session %s",
                            port.providerId().value(), context.sessionId())
            );
        }
        if (!adapterBucket.equals(configuredBucket)) {
            throw new IllegalStateException(
                    String.format("TX-B abort MultipartStoragePort configured bucket '%s' does not match authoritative routing bucket '%s' for session %s",
                            adapterBucket, configuredBucket, context.sessionId())
            );
        }
        if (!adapterBucket.equals(session.getStorageBucketName())) {
            throw new IllegalStateException(
                    String.format("TX-B abort MultipartStoragePort configured bucket '%s' does not match session bucket '%s' for session %s",
                            adapterBucket, session.getStorageBucketName(), context.sessionId())
            );
        }
        if (!adapterBucket.equals(context.storageBucketName())) {
            throw new IllegalStateException(
                    String.format("TX-B abort MultipartStoragePort configured bucket '%s' does not match claimed target bucket '%s' for session %s",
                            adapterBucket, context.storageBucketName(), context.sessionId())
            );
        }

        // 6. Verify completion evidence remains absent
        if (session.getDeclaredContentHash() != null) {
            throw new IllegalStateException("TX-B abort detected declaredContentHash on session " + context.sessionId());
        }
        if (session.getManifestHash() != null) {
            throw new IllegalStateException("TX-B abort detected manifestHash on session " + context.sessionId());
        }
        if (session.getRegisteredAssetId() != null) {
            throw new IllegalStateException("TX-B abort detected registeredAssetId on session " + context.sessionId());
        }
        if (session.getCompletedAt() != null) {
            throw new IllegalStateException("TX-B abort detected completedAt on session " + context.sessionId());
        }

        // 7. Verify provider upload ID remains unchanged
        if (!Objects.equals(session.getProviderUploadId(), context.providerUploadId())) {
            throw new IllegalStateException(
                    String.format("TX-B abort provider upload ID changed for session %s: expectedLength=%d, foundLength=%d",
                            context.sessionId(),
                            context.providerUploadId() != null ? context.providerUploadId().length() : 0,
                            session.getProviderUploadId() != null ? session.getProviderUploadId().length() : 0)
            );
        }

        // 8. Verify storage key remains unchanged
        if (!Objects.equals(session.getStorageKey(), context.storageKey())) {
            throw new IllegalStateException(
                    String.format("TX-B abort storage key changed for session %s: expected %s, found %s",
                            context.sessionId(), context.storageKey(), session.getStorageKey())
            );
        }

        // 9. Verify storage allocation binding remains unchanged
        if (!Objects.equals(session.getStorageAllocationId(), context.storageAllocationId())) {
            throw new IllegalStateException(
                    String.format("TX-B abort storage allocation ID changed for session %s: expected %s, found %s",
                            context.sessionId(), context.storageAllocationId(), session.getStorageAllocationId())
            );
        }
        StorageKeyAllocation allocation = storageKeyAllocationService.findById(session.getStorageAllocationId())
                .orElseThrow(() -> new IllegalStateException("Storage key allocation not found in TX-B for session: " + context.sessionId()));
        validateAllocationBindingForCleanup(allocation, session, context.storageBucketName());

        // 10. Record QUARANTINED outcome and persist atomically
        session.markCleanupQuarantined(context.claimToken(), quarantineReason, now);
        return sessionRepositoryPort.save(session);
    }

    private void validateAllocationBindingForCleanup(StorageKeyAllocation allocation, MediaUploadSession session, String expectedConfiguredBucket) {
        if (allocation.getStatus() != StorageKeyAllocationStatus.RESERVED
                && allocation.getStatus() != StorageKeyAllocationStatus.TOMBSTONE) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s status must be RESERVED or TOMBSTONE (found: %s)",
                            session.getStorageAllocationId(), allocation.getStatus())
            );
        }
        if (!allocation.getProviderId().equals(session.getStorageProviderId())) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s provider '%s' does not match session provider '%s'",
                            allocation.getId(), allocation.getProviderId().value(), session.getStorageProviderId().value())
            );
        }
        if (!allocation.getBucketName().equals(expectedConfiguredBucket)) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s bucket '%s' does not match authoritative configured bucket '%s'",
                            allocation.getId(), allocation.getBucketName(), expectedConfiguredBucket)
            );
        }
        if (!allocation.getBucketName().equals(session.getStorageBucketName())) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s bucket '%s' does not match session bucket '%s'",
                            allocation.getId(), allocation.getBucketName(), session.getStorageBucketName())
            );
        }
        if (!allocation.getStorageKey().equals(session.getStorageKey())
                || allocation.getAllocatedByType() != StorageKeyAllocationType.UPLOAD_SESSION
                || !allocation.getAllocatedById().equals(session.getId())) {
            throw new IllegalStateException(
                    String.format("Storage key allocation %s does not match upload session %s binding.",
                            session.getStorageAllocationId(), session.getId())
            );
        }
    }

    static String classifyProviderExceptionDiagnostic(Exception ex) {
        if (ex == null) {
            return "Provider multipart abort failed with unspecified error; physical cleanup outcome unresolved, pending reconciliation (S2D-3)";
        }

        String exceptionType = ex.getClass().getSimpleName();
        String category = categorizeProviderException(ex);

        return String.format(
                "Provider multipart abort failed with %s (%s); physical cleanup outcome unresolved, pending reconciliation (S2D-3)",
                exceptionType, category
        );
    }

    private static String categorizeProviderException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getSimpleName().toLowerCase();
            String msg = (current.getMessage() != null) ? current.getMessage().toLowerCase() : "";

            if (className.contains("timeout") || msg.contains("timed out") || msg.contains("timeout")) {
                return "TIMEOUT_UNCERTAINTY";
            }
            if (className.contains("connect") || msg.contains("connection refused") || msg.contains("connection reset")) {
                return "CONNECTION_FAILURE";
            }
            if (msg.contains("403") || msg.contains("forbidden") || msg.contains("accessdenied")) {
                return "PROVIDER_AUTHENTICATION_OR_ACCESS_DENIED";
            }
            if (msg.contains("400") || msg.contains("bad request")) {
                return "PROVIDER_BAD_REQUEST";
            }
            if (msg.contains("503") || msg.contains("service unavailable")) {
                return "PROVIDER_SERVICE_UNAVAILABLE";
            }
            if (msg.contains("500") || msg.contains("internal error")) {
                return "PROVIDER_INTERNAL_SERVER_ERROR";
            }
            current = current.getCause();
        }
        return "GENERAL_PROVIDER_ERROR";
    }

    private record CleanupTargetContext(
            UUID sessionId,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey storageKey,
            String providerUploadId,
            String claimToken,
            Instant leaseExpiresAt,
            UUID storageAllocationId
    ) {}
}
