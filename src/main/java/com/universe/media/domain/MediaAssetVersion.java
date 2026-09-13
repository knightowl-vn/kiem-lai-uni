package com.universe.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing a specific version snapshot of a MediaAsset.
 *
 * Each version snapshot contains the storage location, content hash, MIME type,
 * file size, original filename, and optional public delivery URL.
 *
 * Once created, a version is write-once and read-only.
 */
public final class MediaAssetVersion {

    private static final int MAX_FILENAME_LENGTH =
            255;

    private static final int MAX_URL_LENGTH =
            1000;

    private final UUID id;

    private final UUID assetId;

    private final int versionNumber;

    private final StorageLocation storageLocation;

    private final String publicUrl;

    private final ContentHash contentHash;

    private final MimeType mimeType;

    private final long sizeBytes;

    private final String originalFilename;

    private final Instant createdAt;

    private MediaAssetVersion(
            UUID id,
            UUID assetId,
            int versionNumber,
            StorageLocation storageLocation,
            String publicUrl,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes,
            String originalFilename,
            Instant createdAt
    ) {
        this.id =
                Objects.requireNonNull(
                        id,
                        "Version ID cannot be null."
                );

        this.assetId =
                Objects.requireNonNull(
                        assetId,
                        "Asset ID cannot be null."
                );

        if (versionNumber < 1) {
            throw new IllegalArgumentException(
                    "Version number must be greater than or equal to 1."
            );
        }
        this.versionNumber =
                versionNumber;

        this.storageLocation =
                Objects.requireNonNull(
                        storageLocation,
                        "StorageLocation cannot be null."
                );

        this.publicUrl =
                validatePublicUrl(publicUrl);

        this.contentHash =
                Objects.requireNonNull(
                        contentHash,
                        "ContentHash cannot be null."
                );

        this.mimeType =
                Objects.requireNonNull(
                        mimeType,
                        "MimeType cannot be null."
                );

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "File size must be greater than 0 bytes."
            );
        }
        this.sizeBytes =
                sizeBytes;

        this.originalFilename =
                validateOriginalFilename(originalFilename);

        this.createdAt =
                Objects.requireNonNull(
                        createdAt,
                        "Creation timestamp cannot be null."
                );
    }

    public static MediaAssetVersion create(
            UUID id,
            UUID assetId,
            int versionNumber,
            StorageLocation storageLocation,
            String publicUrl,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes,
            String originalFilename,
            Instant createdAt
    ) {
        return new MediaAssetVersion(
                id,
                assetId,
                versionNumber,
                storageLocation,
                publicUrl,
                contentHash,
                mimeType,
                sizeBytes,
                originalFilename,
                createdAt
        );
    }

    public static MediaAssetVersion rehydrate(
            UUID id,
            UUID assetId,
            int versionNumber,
            StorageLocation storageLocation,
            String publicUrl,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes,
            String originalFilename,
            Instant createdAt
    ) {
        return new MediaAssetVersion(
                id,
                assetId,
                versionNumber,
                storageLocation,
                publicUrl,
                contentHash,
                mimeType,
                sizeBytes,
                originalFilename,
                createdAt
        );
    }

    private static String validatePublicUrl(
            String publicUrl
    ) {
        if (publicUrl == null) {
            return null;
        }

        String trimmed = publicUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "Public URL cannot exceed "
                            + MAX_URL_LENGTH
                            + " characters."
            );
        }

        return trimmed;
    }

    private static String validateOriginalFilename(
            String originalFilename
    ) {
        if (originalFilename == null
                || originalFilename.isBlank()) {
            throw new IllegalArgumentException(
                    "Original filename cannot be blank."
            );
        }

        String trimmed =
                originalFilename.trim();

        if (trimmed.length() > MAX_FILENAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Original filename cannot exceed "
                            + MAX_FILENAME_LENGTH
                            + " characters."
            );
        }

        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public StorageLocation getStorageLocation() {
        return storageLocation;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public ContentHash getContentHash() {
        return contentHash;
    }

    public MimeType getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaAssetVersion that = (MediaAssetVersion) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MediaAssetVersion{" +
                "id=" + id +
                ", assetId=" + assetId +
                ", versionNumber=" + versionNumber +
                ", storageLocation=" + storageLocation +
                ", mimeType=" + mimeType +
                ", sizeBytes=" + sizeBytes +
                ", createdAt=" + createdAt +
                '}';
    }
}
