package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.exceptions.UploadContentMimeMismatchException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageKeyAllocationType;
import com.universe.media.domain.StorageProviderId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class UploadMediaAssetVersionUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final StorageProviderResolverPort storageProviderResolverPort;
    private final RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;
    private final RasterContentSignatureValidator rasterContentSignatureValidator;
    private final VideoUploadValidator videoUploadValidator;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageCutoverBarrier cutoverBarrier;

    @Autowired
    public UploadMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.storageProviderResolverPort = Objects.requireNonNull(storageProviderResolverPort, "StorageProviderResolverPort cannot be null.");
        this.registerMediaAssetVersionUseCase = Objects.requireNonNull(registerMediaAssetVersionUseCase, "RegisterMediaAssetVersionUseCase cannot be null.");
        this.rasterContentSignatureValidator = Objects.requireNonNull(rasterContentSignatureValidator, "RasterContentSignatureValidator cannot be null.");
        this.videoUploadValidator = Objects.requireNonNull(videoUploadValidator, "VideoUploadValidator cannot be null.");
        this.storageKeyAllocationService = storageKeyAllocationService;
        this.cutoverBarrier = cutoverBarrier;
    }

    public UploadMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetVersionUseCase,
                rasterContentSignatureValidator,
                videoUploadValidator,
                storageKeyAllocationService,
                null
        );
    }

    public UploadMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetVersionUseCase,
                rasterContentSignatureValidator,
                videoUploadValidator,
                null,
                null
        );
    }

    public UploadMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator
    ) {
        this(
                mediaAssetRepositoryPort,
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetVersionUseCase,
                rasterContentSignatureValidator,
                new VideoUploadValidator(),
                null,
                null
        );
    }

    public UploadMediaAssetVersionResult execute(
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

        MessageDigest messageDigest = createSha256Digest();
        DigestInputStream digestInputStream = new DigestInputStream(validatedContent, messageDigest);

        StoredBinaryObject stored;
        try {
            stored = binaryStoragePort.store(
                    storageKey,
                    digestInputStream,
                    command.sizeBytes(),
                    mimeType
            );
        } catch (RuntimeException storeException) {
            if (cutoverActive && allocation != null) {
                try {
                    storageKeyAllocationService.tombstoneAllocation(allocation.getId());
                } catch (RuntimeException tombstoneException) {
                    storeException.addSuppressed(tombstoneException);
                }
            }
            // Do not treat store() failure as evidence that a new object was successfully created;
            // no speculative delete is performed. When cutover is active, the reservation remains
            // permanently TOMBSTONED to guarantee non-reuse for uncertain outcomes.
            throw storeException;
        }

        String contentHash = HexFormat.of().formatHex(messageDigest.digest());

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

        RegisterMediaAssetVersionResult registerResult;
        try {
            registerResult = registerMediaAssetVersionUseCase.execute(registerCommand);
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

        return new UploadMediaAssetVersionResult(
                registerResult.assetId(),
                registerResult.versionId(),
                registerResult.newVersionNumber(),
                registerResult.updatedAt()
        );
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
