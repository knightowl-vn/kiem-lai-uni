package com.universe.media.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable derivative snapshot representing a specific image variant of a {@link MediaAssetVersion}.
 * <p>
 * Invariants:
 * <ul>
 *     <li>Belongs to exactly one immutable {@code versionId}.</li>
 *     <li>Derives canonical {@code variantKey} from {@link ImageVariantSpec} (e.g. {@code "w300"}).</li>
 *     <li>Storage ownership is strictly XOR:
 *         <ul>
 *             <li><strong>Physical variant:</strong> owns a physical {@link StorageLocation}, {@code publicUrl} is null, measured derivative metadata is populated.</li>
 *             <li><strong>External virtual variant:</strong> owns a validated HTTPS {@code publicUrl}, {@link StorageLocation} is null, derivative measurements are null.</li>
 *         </ul>
 *     </li>
 *     <li>Is disposable and regenerable; not a {@link MediaAsset}.</li>
 *     <li>Immutable after creation (write-once, read-only).</li>
 * </ul>
 */
public final class MediaImageVariant {

    private static final int MAX_URL_LENGTH = 1000;

    private final UUID id;
    private final UUID versionId;
    private final String variantKey;
    private final int targetWidth;
    private final StorageLocation storageLocation;
    private final String publicUrl;
    private final ContentHash contentHash;
    private final MimeType mimeType;
    private final Long sizeBytes;
    private final Integer width;
    private final Integer height;
    private final Instant createdAt;

    private MediaImageVariant(
            UUID id,
            UUID versionId,
            String variantKey,
            int targetWidth,
            StorageLocation storageLocation,
            String publicUrl,
            ContentHash contentHash,
            MimeType mimeType,
            Long sizeBytes,
            Integer width,
            Integer height,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Variant ID cannot be null.");
        this.versionId = Objects.requireNonNull(versionId, "Version ID cannot be null.");
        this.targetWidth = validateTargetWidth(targetWidth);
        this.variantKey = validateVariantKey(variantKey, targetWidth);
        this.mimeType = Objects.requireNonNull(mimeType, "MimeType cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");

        boolean hasLocation = storageLocation != null;
        boolean hasUrl = publicUrl != null;

        if (hasLocation && hasUrl) {
            throw new IllegalArgumentException(
                    "Variant cannot have both a physical StorageLocation and an external publicUrl."
            );
        }
        if (!hasLocation && !hasUrl) {
            throw new IllegalArgumentException(
                    "Variant must have either a physical StorageLocation or an external publicUrl."
            );
        }

        if (hasUrl) {
            // External virtual variant
            this.publicUrl = validateHttpsUrl(publicUrl);
            this.storageLocation = null;
            this.contentHash = null;
            this.sizeBytes = null;
            this.width = null;
            this.height = null;
        } else {
            // Physical variant
            this.storageLocation = storageLocation;
            this.publicUrl = null;
            this.contentHash = Objects.requireNonNull(contentHash, "ContentHash cannot be null for physical variant.");

            if (sizeBytes == null || sizeBytes <= 0) {
                throw new IllegalArgumentException("sizeBytes must be greater than 0: " + sizeBytes);
            }
            this.sizeBytes = sizeBytes;

            if (width == null || width <= 0) {
                throw new IllegalArgumentException("width must be greater than 0: " + width);
            }
            if (width > targetWidth) {
                throw new IllegalArgumentException(
                        "width (" + width + ") cannot exceed targetWidth (" + targetWidth + ")"
                );
            }
            this.width = width;

            if (height == null || height <= 0) {
                throw new IllegalArgumentException("height must be greater than 0: " + height);
            }
            this.height = height;
        }
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
        Objects.requireNonNull(storageLocation, "StorageLocation cannot be null for physical variant.");
        return new MediaImageVariant(
                id,
                versionId,
                spec.variantKey(),
                spec.targetWidth(),
                storageLocation,
                null,
                contentHash,
                mimeType,
                sizeBytes,
                width,
                height,
                createdAt
        );
    }

    public static MediaImageVariant createExternal(
            UUID id,
            UUID versionId,
            ImageVariantSpec spec,
            String publicUrl,
            MimeType mimeType,
            Instant createdAt
    ) {
        Objects.requireNonNull(spec, "ImageVariantSpec cannot be null.");
        Objects.requireNonNull(publicUrl, "External variant publicUrl cannot be null.");
        return new MediaImageVariant(
                id,
                versionId,
                spec.variantKey(),
                spec.targetWidth(),
                null,
                publicUrl,
                null,
                mimeType,
                null,
                null,
                null,
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
                null,
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
            String publicUrl,
            ContentHash contentHash,
            MimeType mimeType,
            Long sizeBytes,
            Integer width,
            Integer height,
            Instant createdAt
    ) {
        return new MediaImageVariant(
                id,
                versionId,
                variantKey,
                targetWidth,
                storageLocation,
                publicUrl,
                contentHash,
                mimeType,
                sizeBytes,
                width,
                height,
                createdAt
        );
    }

    public static String validateHttpsUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new IllegalArgumentException("External variant publicUrl cannot be blank.");
        }
        String trimmed = publicUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "External variant publicUrl cannot exceed " + MAX_URL_LENGTH + " characters."
            );
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid external variant publicUrl: " + publicUrl, e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(
                    "External variant publicUrl must be an absolute URI with a host: " + publicUrl
            );
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(
                    "External variant publicUrl must use HTTPS scheme: " + publicUrl
            );
        }
        return trimmed;
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

    public String getPublicUrl() {
        return publicUrl;
    }

    public boolean hasPublicUrl() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    public boolean isVirtual() {
        return hasPublicUrl() && storageLocation == null;
    }

    public boolean isPhysical() {
        return storageLocation != null && !hasPublicUrl();
    }

    public ContentHash getContentHash() {
        return contentHash;
    }

    public MimeType getMimeType() {
        return mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
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
                ", publicUrl='" + publicUrl + '\'' +
                ", mimeType=" + mimeType +
                ", sizeBytes=" + sizeBytes +
                ", width=" + width +
                ", height=" + height +
                ", createdAt=" + createdAt +
                '}';
    }
}
