package com.universe.media.application.asset;

import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.application.storage.MediaStorageRoutingService;
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
public class UploadMediaAssetUseCase {

    private final MediaStorageRoutingService mediaStorageRoutingService;
    private final StorageProviderResolverPort storageProviderResolverPort;
    private final RegisterMediaAssetUseCase registerMediaAssetUseCase;
    private final RasterContentSignatureValidator rasterContentSignatureValidator;

    public UploadMediaAssetUseCase(
            MediaStorageRoutingService mediaStorageRoutingService,
            StorageProviderResolverPort storageProviderResolverPort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase,
            RasterContentSignatureValidator rasterContentSignatureValidator
    ) {
        this.mediaStorageRoutingService = Objects.requireNonNull(
                mediaStorageRoutingService,
                "MediaStorageRoutingService cannot be null."
        );
        this.storageProviderResolverPort = Objects.requireNonNull(
                storageProviderResolverPort,
                "StorageProviderResolverPort cannot be null."
        );
        this.registerMediaAssetUseCase = Objects.requireNonNull(
                registerMediaAssetUseCase,
                "RegisterMediaAssetUseCase cannot be null."
        );
        this.rasterContentSignatureValidator = Objects.requireNonNull(
                rasterContentSignatureValidator,
                "RasterContentSignatureValidator cannot be null."
        );
    }

    public UploadMediaAssetResult execute(
            UploadMediaAssetCommand command
    ) {
        Objects.requireNonNull(command, "UploadMediaAssetCommand cannot be null.");

        MimeType mimeType = MimeType.of(command.mimeType());
        InputStream validatedContent = rasterContentSignatureValidator.validateAndPrepareStream(
                command.content(),
                command.sizeBytes(),
                mimeType
        );

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
                command.clientTag()
        );

        RegisterMediaAssetResult registerResult;
        try {
            registerResult = registerMediaAssetUseCase.execute(registerCommand);
        } catch (RuntimeException primaryException) {
            compensateStorage(binaryStoragePort, stored.location().key(), primaryException);
            throw primaryException;
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
