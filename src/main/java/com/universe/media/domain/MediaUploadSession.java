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
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

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
            Long persistenceVersion
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
                null
        );
    }

    /**
     * Factory method for rehydrating an existing upload session from persistence.
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
                persistenceVersion
        );
    }

    // ---------------------------------------------------------------------------------------------
    // State Transitions
    // ---------------------------------------------------------------------------------------------

    public void markInitiated(String providerUploadId, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.ALLOCATED) {
            throw new IllegalStateException("Cannot initiate upload session with status: " + this.status);
        }
        this.providerUploadId = requireNonBlank(providerUploadId, "providerUploadId");
        this.status = MediaUploadSessionStatus.INITIATED;
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

    public void markCompleted(UUID registeredAssetId, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        if (this.status != MediaUploadSessionStatus.COMPLETING) {
            throw new IllegalStateException("Cannot complete upload session from status: " + this.status);
        }
        this.registeredAssetId = Objects.requireNonNull(registeredAssetId, "registeredAssetId cannot be null.");
        this.status = MediaUploadSessionStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
        validateInvariants();
    }

    public void abort(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
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

    public void markCleanupPending(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.cleanupStatus = UploadSessionCleanupStatus.PENDING;
        this.updatedAt = now;
    }

    public void markCleanupInProgress(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.cleanupStatus = UploadSessionCleanupStatus.IN_PROGRESS;
        this.cleanupLastAttemptAt = now;
        this.updatedAt = now;
    }

    public void markCleanupCompleted(Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.cleanupStatus = UploadSessionCleanupStatus.COMPLETED;
        this.status = MediaUploadSessionStatus.CLEANED_UP;
        this.updatedAt = now;
    }

    public void markCleanupFailed(String errorDetail, Instant now) {
        Objects.requireNonNull(now, "Timestamp cannot be null.");
        this.cleanupStatus = UploadSessionCleanupStatus.FAILED;
        this.cleanupRetryCount++;
        this.cleanupLastAttemptAt = now;
        this.cleanupErrorDetail = errorDetail;
        this.updatedAt = now;
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
}
