package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class UploadMediaAssetVersionUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final StorageProviderResolverPort storageProviderResolverPort;
    private final RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;
    private final RasterContentSignatureValidator rasterContentSignatureValidator;

    public UploadMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.mediaStorageRoutingService = Objects.requireNonNull(
                mediaStorageRoutingService,
                "MediaStorageRoutingService cannot be null."
        );
        this.storageProviderResolverPort = Objects.requireNonNull(
                storageProviderResolverPort,
                "StorageProviderResolverPort cannot be null."
        );
        this.registerMediaAssetVersionUseCase = Objects.requireNonNull(
                registerMediaAssetVersionUseCase,
                "RegisterMediaAssetVersionUseCase cannot be null."
        );
        this.rasterContentSignatureValidator = Objects.requireNonNull(
                rasterContentSignatureValidator,
                "RasterContentSignatureValidator cannot be null."
        );
    }

    public UploadMediaAssetVersionResult execute(
            UploadMediaAssetVersionCommand command
    ) {
        Objects.requireNonNull(command, "UploadMediaAssetVersionCommand cannot be null.");

        MimeType mimeType = MimeType.of(command.mimeType());
        InputStream validatedContent = rasterContentSignatureValidator.validateAndPrepareStream(
                command.content(),
                command.sizeBytes(),
                mimeType
        );

        MediaAsset asset = mediaAssetRepositoryPort.findById(command.assetId())
                .orElseThrow(() -> new MediaAssetNotFoundException(command.assetId()));

        StorageProviderId providerId = mediaStorageRoutingService.resolveWriteProvider(asset.getMediaType(), asset.getClientTag());
        StorageKey storageKey = mediaStorageRoutingService.generateStorageKey(
                asset.getMediaType(),
                asset.getClientTag(),
                command.assetId(),
                asset.getCurrentVersionNumber() + 1
        );

        BinaryStoragePort binaryStoragePort = storageProviderResolverPort.resolve(providerId);

        MessageDigest messageDigest = createSha256Digest();
        DigestInputStream digestInputStream = new DigestInputStream(validatedContent, messageDigest);

        StoredBinaryObject stored = binaryStoragePort.store(
                storageKey,
                digestInputStream,
                command.sizeBytes(),
                mimeType
        );

        String contentHash = HexFormat.of().formatHex(messageDigest.digest());

        RegisterMediaAssetVersionCommand registerCommand = new RegisterMediaAssetVersionCommand(
                command.assetId(),
                stored.location().providerId().value(),
                stored.location().key().value(),
                stored.publicUrl(),
                contentHash,
                mimeType.value(),
                command.sizeBytes(),
                command.originalFilename()
        );

        RegisterMediaAssetVersionResult registerResult;
        try {
            registerResult = registerMediaAssetVersionUseCase.execute(registerCommand);
        } catch (RuntimeException primaryException) {
            compensateStorage(binaryStoragePort, stored.location().key(), primaryException);
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
        } catch (RuntimeException cleanupException) {
            primaryException.addSuppressed(cleanupException);
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
