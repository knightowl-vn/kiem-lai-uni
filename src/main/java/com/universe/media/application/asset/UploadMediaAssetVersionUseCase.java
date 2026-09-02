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
public class UploadMediaAssetVersionUseCase {

    private final BinaryStoragePort binaryStoragePort;
    private final RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;

    public UploadMediaAssetVersionUseCase(
            BinaryStoragePort binaryStoragePort,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase
    ) {
        this.binaryStoragePort = Objects.requireNonNull(
                binaryStoragePort,
                "BinaryStoragePort cannot be null."
        );
        this.registerMediaAssetVersionUseCase = Objects.requireNonNull(
                registerMediaAssetVersionUseCase,
                "RegisterMediaAssetVersionUseCase cannot be null."
        );
    }

    public UploadMediaAssetVersionResult execute(
            UploadMediaAssetVersionCommand command
    ) {
        Objects.requireNonNull(command, "UploadMediaAssetVersionCommand cannot be null.");

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

        RegisterMediaAssetVersionCommand registerCommand = new RegisterMediaAssetVersionCommand(
                command.assetId(),
                providerId.value(),
                storageKey.value(),
                null,
                contentHash,
                mimeType.value(),
                command.sizeBytes(),
                command.originalFilename()
        );

        RegisterMediaAssetVersionResult registerResult;
        try {
            registerResult = registerMediaAssetVersionUseCase.execute(registerCommand);
        } catch (RuntimeException primaryException) {
            compensateStorage(storageKey, primaryException);
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
