package com.universe.media.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable derivative snapshot representing a specific image variant of a {@link MediaAssetVersion}.
 * <p>
 * Invariants:
 * <ul>
 *     <li>Belongs to exactly one immutable {@code versionId}.</li>
 *     <li>Owns a unique {@link StorageLocation}.</li>
 *     <li>Derives canonical {@code variantKey} from {@link ImageVariantSpec} (e.g. {@code "w300"}).</li>
 *     <li>Is disposable and regenerable; not a {@link MediaAsset}.</li>
 *     <li>Immutable after creation (write-once, read-only).</li>
 * </ul>
 */
public final class MediaImageVariant {

    private final UUID id;
    private final UUID versionId;
    private final String variantKey;
    private final int targetWidth;
    private final StorageLocation storageLocation;
    private final ContentHash contentHash;
    private final MimeType mimeType;
    private final long sizeBytes;
    private final int width;
    private final int height;
    private final Instant createdAt;

    private MediaImageVariant(
            UUID id,
            UUID versionId,
            String variantKey,
            int targetWidth,
            StorageLocation storageLocation,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes,
            int width,
            int height,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Variant ID cannot be null.");
        this.versionId = Objects.requireNonNull(versionId, "Version ID cannot be null.");
        this.targetWidth = validateTargetWidth(targetWidth);
        this.variantKey = validateVariantKey(variantKey, targetWidth);
        this.storageLocation = Objects.requireNonNull(storageLocation, "StorageLocation cannot be null.");
        this.contentHash = Objects.requireNonNull(contentHash, "ContentHash cannot be null.");
        this.mimeType = Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be greater than 0: " + sizeBytes);
        }
        this.sizeBytes = sizeBytes;

        if (width <= 0) {
            throw new IllegalArgumentException("width must be greater than 0: " + width);
        }
        if (width > targetWidth) {
            throw new IllegalArgumentException(
                    "width (" + width + ") cannot exceed targetWidth (" + targetWidth + ")"
            );
        }
        this.width = width;

        if (height <= 0) {
            throw new IllegalArgumentException("height must be greater than 0: " + height);
        }
        this.height = height;

        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
    }

    public static MediaImageVariant create(
            UUID id,
            UUID versionId,
            ImageVariantSpec spec,
            StorageLocation storageLocation,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes,
            int width,
            int height,
            Instant createdAt
    ) {
        Objects.requireNonNull(spec, "ImageVariantSpec cannot be null.");
        return new MediaImageVariant(
                id,
                versionId,
                spec.variantKey(),
                spec.targetWidth(),
                storageLocation,
                contentHash,
                mimeType,
                sizeBytes,
                width,
                height,
                createdAt
        );
    }

    public static MediaImageVariant rehydrate(
            UUID id,
            UUID versionId,
            String variantKey,
            int targetWidth,
            StorageLocation storageLocation,
            ContentHash contentHash,
            MimeType mimeType,
            long sizeBytes,
            int width,
            int height,
            Instant createdAt
    ) {
        return new MediaImageVariant(
                id,
                versionId,
                variantKey,
                targetWidth,
                storageLocation,
                contentHash,
                mimeType,
                sizeBytes,
                width,
                height,
                createdAt
        );
    }

    private static int validateTargetWidth(int targetWidth) {
        ImageVariantSpec.of(targetWidth);
        return targetWidth;
    }

    private static String validateVariantKey(String variantKey, int targetWidth) {
        Objects.requireNonNull(variantKey, "variantKey cannot be null.");
        String expectedKey = "w" + targetWidth;
        if (!expectedKey.equals(variantKey)) {
            throw new IllegalArgumentException(
                    "variantKey '" + variantKey + "' does not match expected canonical key '" + expectedKey + "'"
            );
        }
        return expectedKey;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getVariantKey() {
        return variantKey;
    }

    public int getTargetWidth() {
        return targetWidth;
    }

    public StorageLocation getStorageLocation() {
        return storageLocation;
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

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaImageVariant that = (MediaImageVariant) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MediaImageVariant{" +
                "id=" + id +
                ", versionId=" + versionId +
                ", variantKey='" + variantKey + '\'' +
                ", targetWidth=" + targetWidth +
                ", storageLocation=" + storageLocation +
                ", mimeType=" + mimeType +
                ", sizeBytes=" + sizeBytes +
                ", width=" + width +
                ", height=" + height +
                ", createdAt=" + createdAt +
                '}';
    }
}
