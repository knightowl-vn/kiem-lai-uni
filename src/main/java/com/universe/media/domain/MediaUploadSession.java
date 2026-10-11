package com.universe.media.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Domain entity representing a durable multipart upload session.
 *
 * <p>Enforces all MySQL V89 schema invariants:
 * <ul>
 *   <li>Media type must be VIDEO;</li>
 *   <li>Declared MIME type must be video/mp4;</li>
 *   <li>Size between 1 byte and 50 GiB (53,687,091,200 bytes);</li>
 *   <li>Part size must be exactly 25 MiB (26,214,400 bytes);</li>
 *   <li>Expected part count between 1 and 2048, matching CEIL(declaredSizeBytes / partSizeBytes);</li>
 *   <li>Storage provider must be 'b2';</li>
 *   <li>Storage bucket must be authentic (non-blank, non-'NONE');</li>
 *   <li>Bound to a reserved storage allocation ID;</li>
 *   <li>Provider upload ID must be present in INITIATED, COMPLETING, and COMPLETED states;</li>
 *   <li>Completion requires declared_content_hash, registered_asset_id, and completed_at;</li>
 * </ul>
 */
public final class MediaUploadSession {

    public static final long FIXED_PART_SIZE_BYTES = 26214400L; // 25 MiB
    public static final long MAX_VIDEO_SIZE_BYTES = 53687091200L; // 50 GiB
    public static final String REQUIRED_MIME_TYPE = "video/mp4";
    public static final StorageProviderId REQUIRED_PROVIDER = StorageProviderId.of("b2");
    public static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    public static final String CLEANUP_CLAIM_PREFIX = "CLEANUP_CLAIM:";
    public static final Pattern CLEANUP_CLAIM_PATTERN = Pattern.compile(
            "^CLEANUP_CLAIM:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );
    public static final Duration MAX_CLEANUP_LEASE_DURATION = Duration.ofMinutes(5);

    private final UUID id;
    private final String initiatorPrincipalId;
    private final String consumerContext;
    private final String targetEntityType;
    private final UUID targetEntityId;
    private final String idempotencyKey;
    private final MediaType mediaType;
    private final String declaredMimeType;
    private final long declaredSizeBytes;
    private final StorageProviderId storageProviderId;
    private final String storageBucketName;
    private final StorageKey storageKey;
    private final UUID storageAllocationId;
    private String providerUploadId;
    private final long partSizeBytes;
    private final int expectedPartCount;
    private String declaredContentHash;
    private final String originalFilename;
    private final String clientTag;
    private MediaUploadSessionStatus status;
    private final Instant expiresAt;
    private String leaseOwner;
    private Instant leaseExpiresAt;
    private UUID registeredAssetId;
    private UploadSessionCleanupStatus cleanupStatus;
    private int cleanupRetryCount;
    private Instant cleanupLastAttemptAt;
    private String cleanupErrorDetail;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private final Long persistenceVersion;
    private String manifestHash;

    private MediaUploadSession(
            UUID id,
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            String idempotencyKey,
            MediaType mediaType,
            String declaredMimeType,
            long declaredSizeBytes,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey storageKey,
            UUID storageAllocationId,
            String providerUploadId,
            long partSizeBytes,
            int expectedPartCount,
            String declaredContentHash,
            String originalFilename,
            String clientTag,
            MediaUploadSessionStatus status,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            UUID registeredAssetId,
            UploadSessionCleanupStatus cleanupStatus,
            int cleanupRetryCount,
            Instant cleanupLastAttemptAt,
            String cleanupErrorDetail,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Long persistenceVersion,
            String manifestHash
    ) {
        this.id = Objects.requireNonNull(id, "Session ID cannot be null.");
        this.initiatorPrincipalId = requireNonBlank(initiatorPrincipalId, "initiatorPrincipalId");
        this.consumerContext = requireNonBlank(consumerContext, "consumerContext");
        this.targetEntityType = requireNonBlank(targetEntityType, "targetEntityType");
        this.targetEntityId = Objects.requireNonNull(targetEntityId, "targetEntityId cannot be null.");
        this.idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType cannot be null.");
        this.declaredMimeType = requireNonBlank(declaredMimeType, "declaredMimeType");
        this.declaredSizeBytes = declaredSizeBytes;
        this.storageProviderId = Objects.requireNonNull(storageProviderId, "storageProviderId cannot be null.");
        this.storageBucketName = requireNonBlank(storageBucketName, "storageBucketName");
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey cannot be null.");
        this.storageAllocationId = Objects.requireNonNull(storageAllocationId, "storageAllocationId cannot be null.");
        this.providerUploadId = providerUploadId != null && !providerUploadId.isBlank() ? providerUploadId.trim() : null;
        this.partSizeBytes = partSizeBytes;
        this.expectedPartCount = expectedPartCount;
        this.declaredContentHash = declaredContentHash != null && !declaredContentHash.isBlank() ? declaredContentHash.trim().toLowerCase() : null;
        this.originalFilename = requireNonBlank(originalFilename, "originalFilename");
        this.clientTag = clientTag != null && !clientTag.isBlank() ? clientTag.trim() : null;
        this.status = Objects.requireNonNull(status, "status cannot be null.");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt cannot be null.");
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.registeredAssetId = registeredAssetId;
        this.cleanupStatus = Objects.requireNonNull(cleanupStatus, "cleanupStatus cannot be null.");
        this.cleanupRetryCount = cleanupRetryCount;
        this.cleanupLastAttemptAt = cleanupLastAttemptAt;
        this.cleanupErrorDetail = cleanupErrorDetail;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null.");
        this.completedAt = completedAt;
        this.persistenceVersion = persistenceVersion;
        this.manifestHash = manifestHash != null && !manifestHash.isBlank() ? manifestHash.trim().toLowerCase() : null;

        validateInvariants();
    }

    private void validateInvariants() {
        if (mediaType != MediaType.VIDEO) {
            throw new IllegalArgumentException("Upload session mediaType must be VIDEO, got: " + mediaType);
        }
        if (!REQUIRED_MIME_TYPE.equalsIgnoreCase(declaredMimeType)) {
            throw new IllegalArgumentException("Upload session declaredMimeType must be video/mp4, got: " + declaredMimeType);
        }
        if (declaredSizeBytes <= 0 || declaredSizeBytes > MAX_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException("Upload session declaredSizeBytes must be between 1 and " + MAX_VIDEO_SIZE_BYTES + ", got: " + declaredSizeBytes);
        }
        if (partSizeBytes != FIXED_PART_SIZE_BYTES) {
            throw new IllegalArgumentException("Upload session partSizeBytes must be exactly " + FIXED_PART_SIZE_BYTES + ", got: " + partSizeBytes);
        }
        int calculatedParts = (int) Math.ceil((double) declaredSizeBytes / FIXED_PART_SIZE_BYTES);
        if (expectedPartCount != calculatedParts || expectedPartCount < 1 || expectedPartCount > 2048) {
            throw new IllegalArgumentException("Upload session expectedPartCount mismatch. Expected " + calculatedParts + ", got: " + expectedPartCount);
        }
        if (!REQUIRED_PROVIDER.equals(storageProviderId)) {
            throw new IllegalArgumentException("Upload session storageProviderId must be 'b2', got: " + storageProviderId.value());
        }
        if (StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(storageBucketName) || storageBucketName.isBlank()) {
            throw new IllegalArgumentException("B2 upload session requires an authentic non-sentinel bucket name.");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt.");
        }
        if (declaredContentHash != null && !SHA256_PATTERN.matcher(declaredContentHash).matches()) {
            throw new IllegalArgumentException("declaredContentHash must be a 64-character lowercase hex string.");
        }
        if (manifestHash != null && !SHA256_PATTERN.matcher(manifestHash).matches()) {
            throw new IllegalArgumentException("manifestHash must be a 64-character lowercase hex string.");
        }
        if (status == MediaUploadSessionStatus.INITIATED
                || status == MediaUploadSessionStatus.COMPLETING
                || status == MediaUploadSessionStatus.COMPLETED) {
            if (providerUploadId == null || providerUploadId.isBlank()) {
                throw new IllegalStateException("providerUploadId must be present for status: " + status);
            }
        }
        if (status == MediaUploadSessionStatus.COMPLETED) {
            if (declaredContentHash == null) {
                throw new IllegalStateException("declaredContentHash must be present for COMPLETED session.");
            }
            if (registeredAssetId == null) {
                throw new IllegalStateException("registeredAssetId must be present for COMPLETED session.");
            }
            if (completedAt == null) {
                throw new IllegalStateException("completedAt must be present for COMPLETED session.");
            }
        }
        if (cleanupRetryCount < 0) {
            throw new IllegalArgumentException("cleanupRetryCount cannot be negative: " + cleanupRetryCount);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException("Upload session field [" + fieldName + "] must not be blank.");
        }
        return value.trim();
    }

    /**
     * Factory method for creating a brand new upload session in ALLOCATED state.
     */
    public static MediaUploadSession create(
            UUID id,
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            String idempotencyKey,
            long declaredSizeBytes,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey storageKey,
            UUID storageAllocationId,
            String originalFilename,
            String clientTag,
            Instant expiresAt,
            Instant createdAt
    ) {
        int expectedParts = (int) Math.ceil((double) declaredSizeBytes / FIXED_PART_SIZE_BYTES);
        return new MediaUploadSession(
                id,
                initiatorPrincipalId,
                consumerContext,
                targetEntityType,
                targetEntityId,
                idempotencyKey,
                MediaType.VIDEO,
                REQUIRED_MIME_TYPE,
                declaredSizeBytes,
                storageProviderId,
                storageBucketName,
                storageKey,
                storageAllocationId,
                null,
                FIXED_PART_SIZE_BYTES,
                expectedParts,
                null,
                originalFilename,
                clientTag,
                MediaUploadSessionStatus.ALLOCATED,
                expiresAt,
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                createdAt,
                createdAt,
                null,
                null,
                null
        );
    }

    /**
     * Factory method for rehydrating an existing upload session from persistence (backward-compatible overload with null manifestHash).
     */
    public static MediaUploadSession rehydrate(
            UUID id,
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            String idempotencyKey,
            MediaType mediaType,
            String declaredMimeType,
            long declaredSizeBytes,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey storageKey,
            UUID storageAllocationId,
            String providerUploadId,
            long partSizeBytes,
            int expectedPartCount,
            String declaredContentHash,
            String originalFilename,
            String clientTag,
            MediaUploadSessionStatus status,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            UUID registeredAssetId,
            UploadSessionCleanupStatus cleanupStatus,
            int cleanupRetryCount,
            Instant cleanupLastAttemptAt,
            String cleanupErrorDetail,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Long persistenceVersion
    ) {
        return rehydrate(
                id,
                initiatorPrincipalId,
                consumerContext,
                targetEntityType,
                targetEntityId,
                idempotencyKey,
                mediaType,
                declaredMimeType,
                declaredSizeBytes,
                storageProviderId,
                storageBucketName,
                storageKey,
                storageAllocationId,
                providerUploadId,
                partSizeBytes,
                expectedPartCount,
                declaredContentHash,
                originalFilename,
                clientTag,
                status,
                expiresAt,
                leaseOwner,
                leaseExpiresAt,
                registeredAssetId,
                cleanupStatus,
                cleanupRetryCount,
                cleanupLastAttemptAt,
                cleanupErrorDetail,
                createdAt,
                updatedAt,
                completedAt,
                persistenceVersion,
                null
        );
    }

    /**
     * Factory method for rehydrating an existing upload session from persistence with manifestHash.
     */
    public static MediaUploadSession rehydrate(
            UUID id,
            String initiatorPrincipalId,
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            String idempotencyKey,
            MediaType mediaType,
            String declaredMimeType,
            long declaredSizeBytes,
            StorageProviderId storageProviderId,
            String storageBucketName,
            StorageKey storageKey,
            UUID storageAllocationId,
            String providerUploadId,
            long partSizeBytes,
            int expectedPartCount,
            String declaredContentHash,
            String originalFilename,
            String clientTag,
            MediaUploadSessionStatus status,
            Instant expiresAt,
            String leaseOwner,
            Instant leaseExpiresAt,
            UUID registeredAssetId,
            UploadSessionCleanupStatus cleanupStatus,
            int cleanupRetryCount,
            Instant cleanupLastAttemptAt,
            String cleanupErrorDetail,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Long persistenceVersion,
            String manifestHash
    ) {
        return new MediaUploadSession(
                id,
                initiatorPrincipalId,
                consumerContext,
                targetEntityType,
                targetEntityId,
                idempotencyKey,
                mediaType,
                declaredMimeType,
                declaredSizeBytes,
                storageProviderId,
                storageBucketName,
                storageKey,
                storageAllocationId,
                providerUploadId,
                partSizeBytes,
                expectedPartCount,
                declaredContentHash,
                originalFilename,
                clientTag,
                status,
                expiresAt,
                leaseOwner,
                leaseExpiresAt,
                registeredAssetId,
                cleanupStatus,
                cleanupRetryCount,
                cleanupLastAttemptAt,
                cleanupErrorDetail,
                createdAt,
                updatedAt,
                completedAt,
                persistenceVersion,
                manifestHash
        );
    }

    // ---------------------------------------------------------------------------------------------
    // State Transitions
    // ---------------------------------------------------------------------------------------------

    public void claimInitiation(String leaseOwner, Instant leaseExpiresAt, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.ALLOCATED) {
            throw new IllegalStateException("Cannot claim initiation on session with status: " + this.status);
        }
        if (this.leaseOwner != null) {
            if (this.leaseExpiresAt != null && this.leaseExpiresAt.isAfter(now)) {
                throw new IllegalStateException("Active initiation lease already held by: " + this.leaseOwner);
            }
            throw new IllegalStateException("Cannot overwrite unresolved initiation claim held by: " + this.leaseOwner);
        }
        this.leaseOwner = requireNonBlank(leaseOwner, "leaseOwner");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt cannot be null.");
        this.updatedAt = now;
        validateInvariants();
    }

    /**
     * Determines whether the supplied token strictly conforms to the cleanup claim format {@code CLEANUP_CLAIM:<UUID>}.
     */
    public static boolean isValidCleanupClaimToken(String token) {
        return token != null && CLEANUP_CLAIM_PATTERN.matcher(token).matches();
    }

    private static String requireValidCleanupClaimToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("claimToken must not be blank.");
        }
        if (!token.startsWith(CLEANUP_CLAIM_PREFIX)) {
            throw new IllegalArgumentException("claimToken must belong to cleanup namespace with prefix '"
                    + CLEANUP_CLAIM_PREFIX + "', got: " + token);
        }
        if (!CLEANUP_CLAIM_PATTERN.matcher(token).matches()) {
            throw new IllegalArgumentException("claimToken must match format CLEANUP_CLAIM:<UUID>, got: " + token);
        }
        return token;
    }

    /**
     * Helper to determine if this session currently has an outstanding cleanup-owned lease (active or expired).
     */
    public boolean isCleanupLeaseHeld() {
        return this.leaseOwner != null
                && (isValidCleanupClaimToken(this.leaseOwner) || this.cleanupStatus == UploadSessionCleanupStatus.IN_PROGRESS);
    }

    public void clearLease(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (isCleanupLeaseHeld()) {
            throw new IllegalStateException("Cannot clear cleanup lease using generic clearLease(); cleanup leases must be resolved via claim-token outcome methods: " + this.leaseOwner);
        }
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        validateInvariants();
    }

    public boolean hasActiveInitiationLease(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.status == MediaUploadSessionStatus.ALLOCATED
                && this.leaseOwner != null
                && this.leaseExpiresAt != null
                && this.leaseExpiresAt.isAfter(now);
    }

    public boolean hasUnconfirmedInitiationClaim(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.status == MediaUploadSessionStatus.ALLOCATED
                && this.leaseOwner != null
                && this.providerUploadId == null;
    }

    public void markInitiated(String providerUploadId, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.ALLOCATED) {
            throw new IllegalStateException("Cannot initiate upload session with status: " + this.status);
        }
        this.providerUploadId = requireNonBlank(providerUploadId, "providerUploadId");
        this.status = MediaUploadSessionStatus.INITIATED;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        validateInvariants();
    }

    public void markCompleting(String declaredContentHash, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.INITIATED) {
            throw new IllegalStateException("Cannot mark completing from status: " + this.status);
        }
        this.declaredContentHash = requireNonBlank(declaredContentHash, "declaredContentHash").toLowerCase();
        this.status = MediaUploadSessionStatus.COMPLETING;
        this.updatedAt = now;
        validateInvariants();
    }

    public void claimCompletion(String leaseOwner, Instant leaseExpiresAt, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Cannot claim completion on session with status: " + this.status);
        }
        if (this.leaseOwner != null && !this.leaseOwner.equals(leaseOwner)) {
            if (this.leaseExpiresAt != null && this.leaseExpiresAt.isAfter(now)) {
                throw new IllegalStateException("Active completion lease already held by: " + this.leaseOwner);
            }
        }
        this.leaseOwner = requireNonBlank(leaseOwner, "leaseOwner");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt cannot be null.");
        this.updatedAt = now;
        validateInvariants();
    }

    public boolean hasActiveCompletionLease(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.status == MediaUploadSessionStatus.COMPLETING
                && this.leaseOwner != null
                && this.leaseExpiresAt != null
                && this.leaseExpiresAt.isAfter(now);
    }

    public boolean isCompletionLeaseExpired(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.leaseExpiresAt == null || !this.leaseExpiresAt.isAfter(now);
    }

    public void markCompleted(UUID registeredAssetId, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Cannot complete upload session from status: " + this.status);
        }
        this.registeredAssetId = Objects.requireNonNull(registeredAssetId, "registeredAssetId cannot be null.");
        this.status = MediaUploadSessionStatus.COMPLETED;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.completedAt = now;
        this.updatedAt = now;
        validateInvariants();
    }

    public void abort(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (isCleanupLeaseHeld()) {
            throw new IllegalStateException("Cannot abort session while cleanup lease is held: " + this.leaseOwner);
        }
        if (this.status == MediaUploadSessionStatus.ALLOCATED && this.leaseOwner != null) {
            throw new IllegalStateException("Cannot abort session with unresolved initiation claim: " + this.id + " (lease owner: " + this.leaseOwner + ")");
        }
        if (this.status == MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Cannot abort session with active completion claim: " + this.id);
        }
        if (this.status == MediaUploadSessionStatus.COMPLETED || this.status == MediaUploadSessionStatus.CLEANED_UP) {
            throw new IllegalStateException("Cannot abort session in terminal state: " + this.status);
        }
        this.status = MediaUploadSessionStatus.ABORTED;
        this.updatedAt = now;
        if (this.providerUploadId != null && this.cleanupStatus == UploadSessionCleanupStatus.NONE) {
            this.cleanupStatus = UploadSessionCleanupStatus.PENDING;
        }
        validateInvariants();
    }

    public void fail(String errorDetail, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (isCleanupLeaseHeld()) {
            throw new IllegalStateException("Cannot fail session while cleanup lease is held: " + this.leaseOwner);
        }
        if (this.status == MediaUploadSessionStatus.COMPLETED || this.status == MediaUploadSessionStatus.CLEANED_UP) {
            throw new IllegalStateException("Cannot fail session in terminal state: " + this.status);
        }
        this.status = MediaUploadSessionStatus.FAILED;
        this.cleanupErrorDetail = errorDetail;
        this.updatedAt = now;
        if (this.providerUploadId != null && this.cleanupStatus == UploadSessionCleanupStatus.NONE) {
            this.cleanupStatus = UploadSessionCleanupStatus.PENDING;
        }
        validateInvariants();
    }

    public void expire(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (isCleanupLeaseHeld()) {
            throw new IllegalStateException("Cannot expire session while cleanup lease is held: " + this.leaseOwner);
        }
        if (this.status == MediaUploadSessionStatus.COMPLETED || this.status == MediaUploadSessionStatus.CLEANED_UP) {
            throw new IllegalStateException("Cannot expire session in terminal state: " + this.status);
        }
        this.status = MediaUploadSessionStatus.EXPIRED;
        this.updatedAt = now;
        if (this.providerUploadId != null && this.cleanupStatus == UploadSessionCleanupStatus.NONE) {
            this.cleanupStatus = UploadSessionCleanupStatus.PENDING;
        }
        validateInvariants();
    }

    // ---------------------------------------------------------------------------------------------
    // Cleanup Eligibility, Claim & Fencing (S2D-2A)
    // ---------------------------------------------------------------------------------------------

    /**
     * Determines whether this session is locally eligible to be claimed for cleanup.
     * <p>
     * <b>Important Safety Boundary:</b> Local eligibility indicates ONLY that local session state
     * meets candidate criteria (ABORTED/EXPIRED, PENDING/FAILED, no completion evidence, unleased,
     * and valid provider upload ID). It is NOT authorization to execute remote destructive provider
     * actions or release durable storage allocations.
     *
     * @param now current evaluation timestamp
     * @return true if locally eligible for cleanup claim, false otherwise
     */
    public boolean isLocallyEligibleForCleanup(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return (this.status == MediaUploadSessionStatus.ABORTED || this.status == MediaUploadSessionStatus.EXPIRED)
                && (this.cleanupStatus == UploadSessionCleanupStatus.PENDING || this.cleanupStatus == UploadSessionCleanupStatus.FAILED)
                && this.declaredContentHash == null
                && this.manifestHash == null
                && this.registeredAssetId == null
                && this.providerUploadId != null && !this.providerUploadId.isBlank()
                && this.leaseOwner == null;
    }

    /**
     * Validates that this session satisfies local cleanup eligibility requirements, throwing an informative
     * exception if any requirement fails.
     * <p>
     * <b>Important Safety Boundary:</b> Local eligibility checks purely local domain state and does NOT
     * authorize destructive remote operations.
     *
     * @param now current evaluation timestamp
     * @throws IllegalStateException if any local eligibility prerequisite is violated
     * @throws NullPointerException if now is null
     */
    public void validateLocalCleanupEligibility(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.ABORTED && this.status != MediaUploadSessionStatus.EXPIRED) {
            throw new IllegalStateException("Upload session is not locally eligible for cleanup due to lifecycle status: " + this.status);
        }
        if (this.cleanupStatus != UploadSessionCleanupStatus.PENDING && this.cleanupStatus != UploadSessionCleanupStatus.FAILED) {
            throw new IllegalStateException("Upload session is not locally eligible for cleanup due to cleanup status: " + this.cleanupStatus);
        }
        if (this.declaredContentHash != null) {
            throw new IllegalStateException("Upload session is not eligible for cleanup because declaredContentHash is present on session " + this.id);
        }
        if (this.manifestHash != null) {
            throw new IllegalStateException("Upload session is not eligible for cleanup because manifestHash is present on session " + this.id);
        }
        if (this.registeredAssetId != null) {
            throw new IllegalStateException("Upload session is not eligible for cleanup because registeredAssetId is present on session " + this.id);
        }
        if (this.providerUploadId == null || this.providerUploadId.isBlank()) {
            throw new IllegalStateException("Upload session is not eligible for cleanup because providerUploadId is missing or blank on session " + this.id);
        }
        if (this.leaseOwner != null) {
            throw new IllegalStateException("Upload session is not locally eligible for cleanup because lease is held by: " + this.leaseOwner);
        }
    }

    /**
     * Claims this upload session for cleanup processing with a bounded lease.
     *
     * @param claimToken unique claim token identifying the cleanup leaseholder (must match {@code CLEANUP_CLAIM:<UUID>})
     * @param leaseExpiresAt bounded timestamp when this cleanup lease expires (must be strictly in future, up to 5 min)
     * @param now current claim timestamp
     * @throws IllegalStateException if session is not locally eligible for cleanup or already leased
     * @throws IllegalArgumentException if claimToken is invalid/malformed or lease duration is non-positive or exceeds 5 min
     * @throws NullPointerException if any argument is null
     */
    public void claimCleanup(String claimToken, Instant leaseExpiresAt, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        String token = requireValidCleanupClaimToken(claimToken);
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt cannot be null.");
        Duration leaseDuration = Duration.between(now, leaseExpiresAt);
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseExpiresAt must be strictly after now: " + leaseExpiresAt + " vs " + now);
        }
        if (leaseDuration.compareTo(MAX_CLEANUP_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException("leaseExpiresAt duration (" + leaseDuration.toSeconds()
                    + "s) exceeds maximum allowed cleanup lease duration (" + MAX_CLEANUP_LEASE_DURATION.toMinutes() + " minutes).");
        }
        validateLocalCleanupEligibility(now);

        this.leaseOwner = token;
        this.leaseExpiresAt = leaseExpiresAt;
        this.cleanupStatus = UploadSessionCleanupStatus.IN_PROGRESS;
        this.cleanupLastAttemptAt = now;
        this.updatedAt = now;
        validateInvariants();
    }

    /**
     * Verifies that this session holds an active cleanup lease owned by the specified claim token.
     *
     * @param claimToken expected claim token
     * @param now verification timestamp
     * @return true if an unexpired cleanup lease matching claimToken is active
     */
    public boolean hasActiveCleanupLease(String claimToken, Instant now) {
        Objects.requireNonNull(claimToken, "claimToken cannot be null.");
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return isValidCleanupClaimToken(claimToken)
                && (this.status == MediaUploadSessionStatus.ABORTED || this.status == MediaUploadSessionStatus.EXPIRED)
                && this.cleanupStatus == UploadSessionCleanupStatus.IN_PROGRESS
                && claimToken.equals(this.leaseOwner)
                && this.leaseExpiresAt != null
                && this.leaseExpiresAt.isAfter(now)
                && this.declaredContentHash == null
                && this.manifestHash == null
                && this.registeredAssetId == null;
    }

    /**
     * Checks if this session has any active, unexpired cleanup lease.
     *
     * @param now verification timestamp
     * @return true if an unexpired cleanup lease is active
     */
    public boolean hasActiveCleanupLease(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return isValidCleanupClaimToken(this.leaseOwner)
                && (this.status == MediaUploadSessionStatus.ABORTED || this.status == MediaUploadSessionStatus.EXPIRED)
                && this.cleanupStatus == UploadSessionCleanupStatus.IN_PROGRESS
                && this.leaseExpiresAt != null
                && this.leaseExpiresAt.isAfter(now);
    }

    /**
     * Checks whether the cleanup lease has expired relative to the specified timestamp.
     *
     * @param now evaluation timestamp
     * @return true if no lease exists or the lease expiration timestamp is not strictly after now
     */
    public boolean isCleanupLeaseExpired(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        return this.leaseExpiresAt == null || !this.leaseExpiresAt.isAfter(now);
    }

    private void validateCleanupClaimForOutcome(String claimToken, Instant now, boolean isCompletion) {
        String token = requireValidCleanupClaimToken(claimToken);
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.leaseOwner == null || !this.leaseOwner.equals(token)) {
            throw new IllegalStateException("Cannot " + (isCompletion ? "complete" : "record")
                    + " cleanup outcome without holding active claim token; held by: " + this.leaseOwner);
        }
        if (isCleanupLeaseExpired(now)) {
            throw new IllegalStateException("Cannot " + (isCompletion ? "complete" : "record")
                    + " cleanup outcome because cleanup lease has expired: " + this.leaseExpiresAt + " vs " + now);
        }
        if (this.cleanupStatus != UploadSessionCleanupStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot " + (isCompletion ? "complete" : "record")
                    + " cleanup outcome when cleanupStatus is not IN_PROGRESS: " + this.cleanupStatus);
        }
        if (this.status != MediaUploadSessionStatus.ABORTED && this.status != MediaUploadSessionStatus.EXPIRED) {
            throw new IllegalStateException("Cannot " + (isCompletion ? "complete" : "record")
                    + " cleanup outcome for session in lifecycle status: " + this.status);
        }
        if (isCompletion) {
            if (this.declaredContentHash != null || this.manifestHash != null || this.registeredAssetId != null) {
                throw new IllegalStateException("Cannot complete cleanup when completion intent or registered asset is present.");
            }
        }
    }

    /**
     * Records successful completion of cleanup under a verified active claim token, transitioning
     * cleanup status to COMPLETED and lifecycle status to CLEANED_UP, and releasing the lease.
     * <p>
     * <b>Important Safety Boundary:</b> This method records a locally acknowledged cleanup outcome
     * fenced by the claim token. It does not prove remote provider state.
     *
     * @param claimToken active claim token held by the caller
     * @param now completion timestamp
     * @throws IllegalStateException if claim token is invalid, expired, or session state violates cleanup prerequisites
     */
    public void markCleanupCompleted(String claimToken, Instant now) {
        validateCleanupClaimForOutcome(claimToken, now, true);

        this.cleanupStatus = UploadSessionCleanupStatus.COMPLETED;
        this.status = MediaUploadSessionStatus.CLEANED_UP;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        validateInvariants();
    }

    /**
     * Records failure of a cleanup attempt under a verified active claim token, incrementing the retry
     * count, recording error details, transitioning cleanup status to FAILED, and releasing the lease.
     *
     * @param claimToken active claim token held by the caller
     * @param errorDetail diagnostic error detail describing the failure
     * @param now failure timestamp
     * @throws IllegalStateException if claim token is invalid, expired, or session state violates cleanup prerequisites
     */
    public void markCleanupFailed(String claimToken, String errorDetail, Instant now) {
        validateCleanupClaimForOutcome(claimToken, now, false);

        this.cleanupStatus = UploadSessionCleanupStatus.FAILED;
        this.cleanupRetryCount++;
        this.cleanupLastAttemptAt = now;
        this.cleanupErrorDetail = errorDetail;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        this.updatedAt = now;
        validateInvariants();
    }

    public void markCleanupPending(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.leaseOwner != null) {
            throw new IllegalStateException("Cannot mark cleanup pending while lease is held by: " + this.leaseOwner);
        }
        if (this.cleanupStatus != UploadSessionCleanupStatus.NONE) {
            throw new IllegalStateException("Cannot mark cleanup pending from cleanup status: " + this.cleanupStatus + ". Cleanup status cannot be reset to PENDING.");
        }
        if (this.status != MediaUploadSessionStatus.ABORTED && this.status != MediaUploadSessionStatus.EXPIRED) {
            throw new IllegalStateException("Cannot mark cleanup pending for session in lifecycle status: " + this.status);
        }
        if (this.declaredContentHash != null || this.manifestHash != null || this.registeredAssetId != null) {
            throw new IllegalStateException("Cannot mark cleanup pending when completion intent or registered asset is present.");
        }
        this.cleanupStatus = UploadSessionCleanupStatus.PENDING;
        this.updatedAt = now;
    }

    @Deprecated
    public void markCleanupInProgress(Instant now) {
        throw new IllegalStateException("Direct un-tokened cleanup progress transition is prohibited. Use claimCleanup with an active claim token.");
    }

    @Deprecated
    public void markCleanupCompleted(Instant now) {
        throw new IllegalStateException("Direct un-tokened cleanup completion is prohibited. An active cleanup claim token is required.");
    }

    @Deprecated
    public void markCleanupFailed(String errorDetail, Instant now) {
        throw new IllegalStateException("Direct un-tokened cleanup failure recording is prohibited. An active cleanup claim token is required.");
    }

    // ---------------------------------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getInitiatorPrincipalId() {
        return initiatorPrincipalId;
    }

    public String getConsumerContext() {
        return consumerContext;
    }

    public String getTargetEntityType() {
        return targetEntityType;
    }

    public UUID getTargetEntityId() {
        return targetEntityId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public String getDeclaredMimeType() {
        return declaredMimeType;
    }

    public long getDeclaredSizeBytes() {
        return declaredSizeBytes;
    }

    public StorageProviderId getStorageProviderId() {
        return storageProviderId;
    }

    public String getStorageBucketName() {
        return storageBucketName;
    }

    public StorageKey getStorageKey() {
        return storageKey;
    }

    public UUID getStorageAllocationId() {
        return storageAllocationId;
    }

    public String getProviderUploadId() {
        return providerUploadId;
    }

    public long getPartSizeBytes() {
        return partSizeBytes;
    }

    public int getExpectedPartCount() {
        return expectedPartCount;
    }

    public String getDeclaredContentHash() {
        return declaredContentHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getClientTag() {
        return clientTag;
    }

    public MediaUploadSessionStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public UUID getRegisteredAssetId() {
        return registeredAssetId;
    }

    public UploadSessionCleanupStatus getCleanupStatus() {
        return cleanupStatus;
    }

    public int getCleanupRetryCount() {
        return cleanupRetryCount;
    }

    public Instant getCleanupLastAttemptAt() {
        return cleanupLastAttemptAt;
    }

    public String getCleanupErrorDetail() {
        return cleanupErrorDetail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public String getManifestHash() {
        return manifestHash;
    }

    public void recordManifestHash(String manifestHash) {
        if (this.manifestHash != null && !this.manifestHash.equalsIgnoreCase(manifestHash)) {
            throw new IllegalStateException("Cannot overwrite existing manifestHash on session " + this.id);
        }
        String normalized = requireNonBlank(manifestHash, "manifestHash").toLowerCase();
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("manifestHash must be a 64-character lowercase hex string.");
        }
        this.manifestHash = normalized;
    }
}
