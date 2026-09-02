package com.universe.media.application.asset;

import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.springframework.stereotype.Service;

import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class UploadMediaAssetUseCase {

    private final BinaryStoragePort binaryStoragePort;
    private final RegisterMediaAssetUseCase registerMediaAssetUseCase;

    public UploadMediaAssetUseCase(
            BinaryStoragePort binaryStoragePort,
            RegisterMediaAssetUseCase registerMediaAssetUseCase
    ) {
        this.binaryStoragePort = Objects.requireNonNull(
                binaryStoragePort,
                "BinaryStoragePort cannot be null."
        );
        this.registerMediaAssetUseCase = Objects.requireNonNull(
                registerMediaAssetUseCase,
                "RegisterMediaAssetUseCase cannot be null."
        );
    }

    public UploadMediaAssetResult execute(
            UploadMediaAssetCommand command
    ) {
        Objects.requireNonNull(command, "UploadMediaAssetCommand cannot be null.");

        MimeType mimeType = MimeType.of(command.mimeType());
        StorageKey storageKey = StorageKey.of("objects/" + UUID.randomUUID());
        StorageProviderId providerId = binaryStoragePort.providerId();

        MessageDigest messageDigest = createSha256Digest();
        DigestInputStream digestInputStream = new DigestInputStream(command.content(), messageDigest);

        binaryStoragePort.store(
                storageKey,
                digestInputStream,
                command.sizeBytes(),
                mimeType
        );

        String contentHash = HexFormat.of().formatHex(messageDigest.digest());

        RegisterMediaAssetCommand registerCommand = new RegisterMediaAssetCommand(
                command.mediaType(),
                command.visibility(),
                providerId.value(),
                storageKey.value(),
                null,
                contentHash,
                mimeType.value(),
                command.sizeBytes(),
                command.originalFilename()
        );

        RegisterMediaAssetResult registerResult;
        try {
            registerResult = registerMediaAssetUseCase.execute(registerCommand);
        } catch (RuntimeException primaryException) {
            compensateStorage(storageKey, primaryException);
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
