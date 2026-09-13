package com.universe.media.application.variant;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.MediaAssetVersionNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.image.ImageProcessorPort;
import com.universe.media.application.ports.image.ProcessedImageResource;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case for generating derivative image variants synchronously from an immutable {@link MediaAssetVersion}.
 * <p>
 * Core Flow:
 * <ul>
 *     <li>Validates existence, ACTIVE status, and compatibility of source {@link MediaAsset} (must be {@link MediaType#IMAGE}).</li>
 *     <li>Resolves targeted {@link MediaAssetVersion}.</li>
 *     <li>Checks idempotency: returns existing variant if {@code (versionId, variantKey)} already exists.</li>
 *     <li>Opens source binary via {@link BinaryStoragePort} and processes via {@link ImageProcessorPort}.</li>
 *     <li>Streams derivative binary to storage, computing SHA-256 hash in-flight.</li>
 *     <li>Persists new {@link MediaImageVariant} record via repository port.</li>
 *     <li>Compensates derivative binary storage if persistence fails, and handles concurrent winner resolution for integrity violations.</li>
 * </ul>
 */
@Service
public class GenerateMediaImageVariantUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private final BinaryStoragePort binaryStoragePort;
    private final ImageProcessorPort imageProcessorPort;
    private final ClockPort clockPort;

    public GenerateMediaImageVariantUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort,
            BinaryStoragePort binaryStoragePort,
            ImageProcessorPort imageProcessorPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(
                mediaAssetVersionRepositoryPort,
                "MediaAssetVersionRepositoryPort cannot be null."
        );
        this.mediaImageVariantRepositoryPort = Objects.requireNonNull(
                mediaImageVariantRepositoryPort,
                "MediaImageVariantRepositoryPort cannot be null."
        );
        this.binaryStoragePort = Objects.requireNonNull(
                binaryStoragePort,
                "BinaryStoragePort cannot be null."
        );
        this.imageProcessorPort = Objects.requireNonNull(
                imageProcessorPort,
                "ImageProcessorPort cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
    }

    public GenerateMediaImageVariantResult execute(
            GenerateMediaImageVariantCommand command
    ) {
        Objects.requireNonNull(command, "GenerateMediaImageVariantCommand cannot be null.");

        UUID assetId = Objects.requireNonNull(command.assetId(), "Asset ID cannot be null.");
        ImageVariantSpec spec = Objects.requireNonNull(command.spec(), "ImageVariantSpec cannot be null.");

        // 1. Resolve source asset
        MediaAsset asset = mediaAssetRepositoryPort.findById(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (asset.getStatus() != MediaAssetStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot generate image variant for media asset " + assetId
                            + " with status: " + asset.getStatus()
                            + ". Variant generation is only permitted while the asset is ACTIVE."
            );
        }

        if (asset.getMediaType() != MediaType.IMAGE) {
            throw new IllegalArgumentException(
                    "Media asset " + assetId + " is of type " + asset.getMediaType()
                            + ", but IMAGE is required for variant generation."
            );
        }

        int versionNumber = command.versionNumber() != null
                ? command.versionNumber()
                : asset.getCurrentVersionNumber();

        MediaAssetVersion version = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, versionNumber)
                .orElseThrow(() -> new MediaAssetVersionNotFoundException(assetId, versionNumber));

        // 2. Idempotency check before processing/storage
        Optional<MediaImageVariant> existingVariant = mediaImageVariantRepositoryPort
                .findByVersionIdAndVariantKey(version.getId(), spec.variantKey());
        if (existingVariant.isPresent()) {
            return toResult(assetId, versionNumber, existingVariant.get());
        }

        // 3. Storage provider validation
        StorageProviderId providerId = binaryStoragePort.providerId();
        if (!providerId.equals(version.getStorageLocation().providerId())) {
            throw new StorageException(
                    "Storage provider mismatch for asset " + assetId + " version " + versionNumber
                            + ": configured provider is " + providerId.value()
                            + ", but asset version requires " + version.getStorageLocation().providerId().value()
            );
        }

        // 4. Open source binary and process variant
        InputStream sourceStream = binaryStoragePort.open(version.getStorageLocation().key());
        ProcessedImageResource processedResource;
        try {
            processedResource = imageProcessorPort.process(
                    sourceStream,
                    version.getMimeType(),
                    spec
            );
        } catch (RuntimeException processException) {
            try {
                sourceStream.close();
            } catch (IOException closeException) {
                processException.addSuppressed(closeException);
            }
            throw processException;
        }

        try {
            sourceStream.close();
        } catch (IOException closeException) {
            StorageException storageException = new StorageException(
                    "Failed to close source binary stream for asset " + assetId,
                    closeException
            );
            if (processedResource != null) {
                try {
                    processedResource.close();
                } catch (RuntimeException cleanupException) {
                    storageException.addSuppressed(cleanupException);
                }
            }
            throw storageException;
        }

        // 5. Enter processed resource cleanup scope immediately before derivative setup
        try (processedResource) {
            StorageKey derivativeKey = StorageKey.of("objects/variants/" + UUID.randomUUID());
            StorageLocation derivativeLocation = StorageLocation.of(providerId, derivativeKey);
            boolean storeSucceeded = false;
            boolean persisted = false;

            try {
                MessageDigest messageDigest = createSha256Digest();
                try (InputStream processedStream = processedResource.openStream();
                     DigestInputStream digestStream = new DigestInputStream(processedStream, messageDigest)) {
                    binaryStoragePort.store(
                            derivativeKey,
                            digestStream,
                            processedResource.sizeBytes(),
                            processedResource.mimeType()
                    );
                    storeSucceeded = true;
                } catch (IOException e) {
                    throw new StorageException("Failed to stream processed variant binary for asset " + assetId, e);
                }

                ContentHash derivativeHash = ContentHash.of(HexFormat.of().formatHex(messageDigest.digest()));
                UUID variantId = UUID.randomUUID();
                Instant now = clockPort.now();

                MediaImageVariant variant = MediaImageVariant.create(
                        variantId,
                        version.getId(),
                        spec,
                        derivativeLocation,
                        derivativeHash,
                        processedResource.mimeType(),
                        processedResource.sizeBytes(),
                        processedResource.width(),
                        processedResource.height(),
                        now
                );

                MediaImageVariant savedVariant = mediaImageVariantRepositoryPort.save(variant);
                persisted = true;
                return toResult(assetId, versionNumber, savedVariant);

            } catch (DataIntegrityViolationException integrityException) {
                if (storeSucceeded && !persisted) {
                    boolean compensated = compensateStorage(derivativeKey, integrityException);
                    if (compensated) {
                        try {
                            Optional<MediaImageVariant> winner = mediaImageVariantRepositoryPort
                                    .findByVersionIdAndVariantKey(version.getId(), spec.variantKey());
                            if (winner.isPresent()) {
                                return toResult(assetId, versionNumber, winner.get());
                            }
                        } catch (RuntimeException lookupException) {
                            integrityException.addSuppressed(lookupException);
                        }
                    }
                }
                throw integrityException;
            } catch (RuntimeException primaryException) {
                if (storeSucceeded && !persisted) {
                    compensateStorage(derivativeKey, primaryException);
                }
                throw primaryException;
            }
        }
    }

    private boolean compensateStorage(
            StorageKey storageKey,
            RuntimeException primaryException
    ) {
        try {
            binaryStoragePort.delete(storageKey);
            return true;
        } catch (RuntimeException cleanupException) {
            primaryException.addSuppressed(cleanupException);
            return false;
        }
    }

    private MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private GenerateMediaImageVariantResult toResult(
            UUID assetId,
            int versionNumber,
            MediaImageVariant variant
    ) {
        return new GenerateMediaImageVariantResult(
                variant.getId(),
                assetId,
                variant.getVersionId(),
                versionNumber,
                variant.getVariantKey(),
                variant.getTargetWidth(),
                variant.getMimeType().value(),
                variant.getSizeBytes(),
                variant.getWidth(),
                variant.getHeight(),
                variant.getCreatedAt()
        );
    }
}
