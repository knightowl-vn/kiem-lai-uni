package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service for conditional media asset version replacement (MS-05G9, MS-05G9.1).
 */
@Service
public class UploadMediaAssetVersionConditionalUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final StorageProviderResolverPort storageProviderResolverPort;
    private final RasterContentSignatureValidator rasterContentSignatureValidator;
    private final RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;
    private final VideoUploadValidator videoUploadValidator;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageCutoverBarrier cutoverBarrier;

    @Autowired
    public UploadMediaAssetVersionConditionalUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.storageProviderResolverPort = Objects.requireNonNull(storageProviderResolverPort, "StorageProviderResolverPort cannot be null.");
        this.rasterContentSignatureValidator = Objects.requireNonNull(rasterContentSignatureValidator, "RasterContentSignatureValidator cannot be null.");
        this.registerMediaAssetVersionUseCase = Objects.requireNonNull(registerMediaAssetVersionUseCase, "RegisterMediaAssetVersionUseCase cannot be null.");
        this.videoUploadValidator = Objects.requireNonNull(videoUploadValidator, "VideoUploadValidator cannot be null.");
        this.storageKeyAllocationService = storageKeyAllocationService;
        this.cutoverBarrier = cutoverBarrier;
    }

    public UploadMediaAssetVersionConditionalUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                rasterContentSignatureValidator,
                registerMediaAssetVersionUseCase,
                videoUploadValidator,
                storageKeyAllocationService,
                null
        );
    }

    public UploadMediaAssetVersionConditionalUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            VideoUploadValidator videoUploadValidator
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                rasterContentSignatureValidator,
                registerMediaAssetVersionUseCase,
                videoUploadValidator,
                null,
                null
        );
    }

    public UploadMediaAssetVersionConditionalUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                rasterContentSignatureValidator,
                registerMediaAssetVersionUseCase,
                new VideoUploadValidator(),
                null,
                null
        );
    }

    public UploadMediaAssetVersionConditionalResult execute(
            UploadMediaAssetVersionCommand command
    ) {
        Objects.requireNonNull(command, "UploadMediaAssetVersionCommand cannot be null.");

        MimeType mimeType = MimeType.of(command.mimeType());
        MediaAsset asset = mediaAssetRepositoryPort.findById(command.assetId())
                .orElseThrow(() -> new MediaAssetNotFoundException(command.assetId()));

        InputStream validatedContent;
        if (asset.getMediaType() == MediaType.VIDEO) {
            videoUploadValidator.validate(MediaType.VIDEO, command.sizeBytes(), mimeType);
            validatedContent = command.content();
        } else {
            String mimeValue = mimeType.value().toLowerCase(java.util.Locale.ROOT);
            if (mimeValue.startsWith("video/")) {
                throw new UploadContentMimeMismatchException(
                        "Cannot upload video to non-VIDEO asset of type: " + asset.getMediaType()
                );
            }
            validatedContent = rasterContentSignatureValidator.validateAndPrepareStream(
                    command.content(),
                    command.sizeBytes(),
                    mimeType
            );
        }

        Path spoolFile = null;
        try {
            try {
                spoolFile = Files.createTempFile("media-conditional-upload-", ".tmp");
            } catch (IOException e) {
                throw new StorageException("Failed to create temporary file for conditional upload: " + e.getMessage(), e);
            }
            MessageDigest messageDigest = createSha256Digest();

            long bytesWritten = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream out = Files.newOutputStream(spoolFile);
                 InputStream in = validatedContent) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytesWritten += read;
                    if (bytesWritten > command.sizeBytes()) {
                        throw new StorageException(
                                "Payload size exceeded declared sizeBytes: declared="
                                        + command.sizeBytes()
                                        + ", received at least="
                                        + bytesWritten
                        );
                    }
                    messageDigest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (IOException e) {
                throw new StorageException("Failed to buffer upload stream to temporary file: " + e.getMessage(), e);
            }

            if (bytesWritten != command.sizeBytes()) {
                throw new StorageException(
                        "Payload size mismatch: declared="
                                + command.sizeBytes()
                                + ", received="
                                + bytesWritten
                );
            }

            String contentHash = HexFormat.of().formatHex(messageDigest.digest());

            // Phase B: Authoritative locked duplicate probe (before any storage write)
            AuthoritativeDuplicateProbeResult probeResult =
                    registerMediaAssetVersionUseCase.probeAuthoritativeDuplicate(command.assetId(), contentHash);

            if (probeResult.isDuplicate()) {
                // Exact content match: zero storage write, zero version row creation, zero allocation
                return UploadMediaAssetVersionConditionalResult.unchanged(
                        command.assetId(),
                        probeResult.currentVersionNumber()
                );
            }

            // Hashes differ -> execute storage write
            StorageProviderId providerId = mediaStorageRoutingService.resolveWriteProvider(asset.getMediaType(), asset.getClientTag());
            StorageKey storageKey = mediaStorageRoutingService.generateStorageKey(
                    asset.getMediaType(),
                    asset.getClientTag(),
                    command.assetId(),
                    asset.getCurrentVersionNumber() + 1
            );

            boolean cutoverActive = (cutoverBarrier != null && cutoverBarrier.isCutoverActive());
            StorageKeyAllocation allocation = null;
            UUID reservationCorrelationId = UUID.randomUUID();

            if (cutoverActive && storageKeyAllocationService != null) {
                String bucketName = mediaStorageRoutingService.resolveBucketName(providerId);
                allocation = storageKeyAllocationService.reserveKey(
                        providerId,
                        bucketName,
                        storageKey,
                        StorageKeyAllocationType.ASSET_VERSION,
                        reservationCorrelationId
                );
            }

            BinaryStoragePort binaryStoragePort = storageProviderResolverPort.resolve(providerId);
            StoredBinaryObject stored;

            try (InputStream uploadIn = Files.newInputStream(spoolFile)) {
                stored = binaryStoragePort.store(
                        storageKey,
                        uploadIn,
                        command.sizeBytes(),
                        mimeType
                );
            } catch (IOException e) {
                if (cutoverActive && allocation != null) {
                    try {
                        storageKeyAllocationService.tombstoneAllocation(allocation.getId());
                    } catch (RuntimeException tombstoneEx) {
                        e.addSuppressed(tombstoneEx);
                    }
                }
                throw new StorageException("Failed to stream spooled content to storage: " + e.getMessage(), e);
            } catch (RuntimeException storeEx) {
                if (cutoverActive && allocation != null) {
                    try {
                        storageKeyAllocationService.tombstoneAllocation(allocation.getId());
                    } catch (RuntimeException tombstoneEx) {
                        storeEx.addSuppressed(tombstoneEx);
                    }
                }
                throw storeEx;
            }

            RegisterMediaAssetVersionCommand registerCommand = new RegisterMediaAssetVersionCommand(
                    command.assetId(),
                    stored.location().providerId().value(),
                    stored.location().key().value(),
                    stored.publicUrl(),
                    contentHash,
                    mimeType.value(),
                    command.sizeBytes(),
                    command.originalFilename(),
                    (allocation != null) ? allocation.getId() : null,
                    (allocation != null) ? reservationCorrelationId : null
            );

            RegisterMediaAssetVersionConditionalResult registerResult;
            try {
                registerResult = registerMediaAssetVersionUseCase.registerConditionalVersion(registerCommand);
            } catch (RuntimeException primaryException) {
                compensateStorage(binaryStoragePort, stored.location().key(), primaryException);
                if (cutoverActive && allocation != null) {
                    try {
                        storageKeyAllocationService.tombstoneAllocation(allocation.getId());
                    } catch (RuntimeException tombstoneException) {
                        primaryException.addSuppressed(tombstoneException);
                    }
                }
                throw primaryException;
            }

            if (registerResult.outcome() == MediaVersionUploadOutcome.UNCHANGED) {
                // Concurrent race resolution: another worker committed this identical hash in the interim.
                // Delete newly written candidate object and tombstone its allocation without touching winner.
                binaryStoragePort.delete(stored.location().key());
                if (cutoverActive && allocation != null) {
                    try {
                        storageKeyAllocationService.tombstoneAllocation(allocation.getId());
                    } catch (RuntimeException ignored) {
                    }
                }
                return UploadMediaAssetVersionConditionalResult.unchanged(
                        registerResult.assetId(),
                        registerResult.versionNumber()
                );
            }

            return UploadMediaAssetVersionConditionalResult.versionCreated(
                    registerResult.assetId(),
                    registerResult.versionId(),
                    registerResult.versionNumber(),
                    registerResult.updatedAt()
            );

        } finally {
            if (spoolFile != null) {
                try {
                    Files.deleteIfExists(spoolFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void compensateStorage(
            BinaryStoragePort binaryStoragePort,
            StorageKey storageKey,
            RuntimeException primaryException
    ) {
        try {
            binaryStoragePort.delete(storageKey);
        } catch (RuntimeException compensationException) {
            primaryException.addSuppressed(compensationException);
        }
    }

    private MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
