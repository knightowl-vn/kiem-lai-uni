package com.universe.media.application.asset;

import com.universe.media.application.hls.OptInHlsPackagingEnqueueCoordinator;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageCutoverBarrier;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.StorageKeyAllocationService;
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
public class UploadMediaAssetUseCase {

    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final StorageProviderResolverPort storageProviderResolverPort;
    private final RegisterMediaAssetUseCase registerMediaAssetUseCase;
    private final RasterContentSignatureValidator rasterContentSignatureValidator;
    private final VideoUploadValidator videoUploadValidator;
    private final StorageKeyAllocationService storageKeyAllocationService;
    private final MediaStorageCutoverBarrier cutoverBarrier;
    private final OptInHlsPackagingEnqueueCoordinator optInEnqueueCoordinator;

    @Autowired
    public UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier,
            OptInHlsPackagingEnqueueCoordinator optInEnqueueCoordinator
    ) {
        this(
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetUseCase,
                rasterContentSignatureValidator,
                videoUploadValidator,
                storageKeyAllocationService,
                cutoverBarrier,
                Objects.requireNonNull(optInEnqueueCoordinator, "OptInHlsPackagingEnqueueCoordinator cannot be null."),
                true
        );
    }

    public UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier
    ) {
        this(
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetUseCase,
                rasterContentSignatureValidator,
                videoUploadValidator,
                storageKeyAllocationService,
                cutoverBarrier,
                null,
                false
        );
    }

    public UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService
    ) {
        this(
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetUseCase,
                rasterContentSignatureValidator,
                videoUploadValidator,
                storageKeyAllocationService,
                null,
                null,
                false
        );
    }

    public UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator
    ) {
        this(
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetUseCase,
                rasterContentSignatureValidator,
                videoUploadValidator,
                null,
                null,
                null,
                false
        );
    }

    public UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator
    ) {
        this(
                mediaStorageRoutingService,
                storageProviderResolverPort,
                registerMediaAssetUseCase,
                rasterContentSignatureValidator,
                new VideoUploadValidator(),
                null,
                null,
                null,
                false
        );
    }

    private UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            VideoUploadValidator videoUploadValidator,
            StorageKeyAllocationService storageKeyAllocationService,
            MediaStorageCutoverBarrier cutoverBarrier,
            OptInHlsPackagingEnqueueCoordinator optInEnqueueCoordinator,
            boolean strictAutoEnqueueWiring
    ) {
        this.mediaStorageRoutingService = Objects.requireNonNull(mediaStorageRoutingService, "MediaStorageRoutingService cannot be null.");
        this.storageProviderResolverPort = Objects.requireNonNull(storageProviderResolverPort, "StorageProviderResolverPort cannot be null.");
        this.registerMediaAssetUseCase = Objects.requireNonNull(registerMediaAssetUseCase, "RegisterMediaAssetUseCase cannot be null.");
        this.rasterContentSignatureValidator = Objects.requireNonNull(rasterContentSignatureValidator, "RasterContentSignatureValidator cannot be null.");
        this.videoUploadValidator = Objects.requireNonNull(videoUploadValidator, "VideoUploadValidator cannot be null.");
        this.storageKeyAllocationService = storageKeyAllocationService;
        this.cutoverBarrier = cutoverBarrier;
        this.optInEnqueueCoordinator = strictAutoEnqueueWiring
                ? Objects.requireNonNull(optInEnqueueCoordinator, "OptInHlsPackagingEnqueueCoordinator cannot be null.")
                : optInEnqueueCoordinator;
    }

    public UploadMediaAssetResult execute(
            UploadMediaAssetCommand command
    ) {
        Objects.requireNonNull(command, "UploadMediaAssetCommand cannot be null.");

        MimeType mimeType = MimeType.of(command.mimeType());
        InputStream validatedContent;
        if (command.mediaType() == MediaType.VIDEO) {
            videoUploadValidator.validate(command.mediaType(), command.sizeBytes(), mimeType);
            validatedContent = command.content();
        } else {
            validatedContent = rasterContentSignatureValidator.validateAndPrepareStream(
                    command.content(),
                    command.sizeBytes(),
                    mimeType
            );
        }

        StorageProviderId providerId = mediaStorageRoutingService.resolveWriteProvider(
                command.mediaType(),
                command.clientTag()
        );
        StorageKey storageKey = mediaStorageRoutingService.generateStorageKey(
                command.mediaType(),
                command.clientTag(),
                null,
                1
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

        RegisterMediaAssetCommand registerCommand = new RegisterMediaAssetCommand(
                command.mediaType(),
                command.visibility(),
                stored.location().providerId().value(),
                stored.location().key().value(),
                stored.publicUrl(),
                contentHash,
                mimeType.value(),
                command.sizeBytes(),
                command.originalFilename(),
                command.clientTag(),
                (allocation != null) ? allocation.getId() : null,
                (allocation != null) ? reservationCorrelationId : null
        );

        RegisterMediaAssetResult registerResult;
        try {
            registerResult = registerMediaAssetUseCase.execute(registerCommand);
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

        if (registerResult.mediaType() == MediaType.VIDEO && optInEnqueueCoordinator != null) {
            optInEnqueueCoordinator.enqueueVideoVersionIfEnabledSafely(
                    registerResult.assetId(),
                    registerResult.versionNumber(),
                    registerResult.versionId()
            );
        }

        return new UploadMediaAssetResult(
                registerResult.assetId(),
                registerResult.versionId(),
                registerResult.versionNumber(),
                registerResult.mediaType(),
                registerResult.visibility(),
                registerResult.status(),
                registerResult.createdAt()
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
