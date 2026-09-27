package com.universe.media.application.asset;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
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
 *
 * <p>Enforces:
 * <ul>
 *     <li>Calculates SHA-256 digest of exact uploaded original bytes in one bounded pass.</li>
 *     <li>Authoritative row-locked duplicate probe against current version's content hash before any storage write.</li>
 *     <li>If identical: zero storage writes, zero MediaAssetVersion creation, returns {@code UNCHANGED}.</li>
 *     <li>If different: performs storage write, registers immutable N+1 version, returns {@code VERSION_CREATED}.</li>
 *     <li>Concurrency hardening: row-locked recheck during registration safely reconciles concurrent identical uploads.</li>
 * </ul>
 */
@Service
public class UploadMediaAssetVersionConditionalUseCase {

    private final BinaryStoragePort binaryStoragePort;
    private final RasterContentSignatureValidator rasterContentSignatureValidator;
    private final RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase;

    public UploadMediaAssetVersionConditionalUseCase(
            BinaryStoragePort binaryStoragePort,
            RasterContentSignatureValidator rasterContentSignatureValidator,
            RegisterMediaAssetVersionUseCase registerMediaAssetVersionUseCase
    ) {
        this.binaryStoragePort = Objects.requireNonNull(binaryStoragePort, "BinaryStoragePort cannot be null.");
        this.rasterContentSignatureValidator = Objects.requireNonNull(rasterContentSignatureValidator, "RasterContentSignatureValidator cannot be null.");
        this.registerMediaAssetVersionUseCase = Objects.requireNonNull(registerMediaAssetVersionUseCase, "RegisterMediaAssetVersionUseCase cannot be null.");
    }

    public UploadMediaAssetVersionConditionalResult execute(UploadMediaAssetVersionCommand command) {
        Objects.requireNonNull(command, "UploadMediaAssetVersionCommand cannot be null.");

        MimeType mimeType = MimeType.of(command.mimeType());
        InputStream validatedContent = rasterContentSignatureValidator.validateAndPrepareStream(
                command.content(),
                command.sizeBytes(),
                mimeType
        );

        Path spoolFile = null;
        try {
            try {
                spoolFile = Files.createTempFile("media-upload-spool-", ".tmp");
            } catch (IOException e) {
                throw new StorageException("Failed to create temporary spool file: " + e.getMessage(), e);
            }

            MessageDigest messageDigest = createSha256Digest();
            long bytesWritten = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream out = Files.newOutputStream(spoolFile)) {
                int read;
                while ((read = validatedContent.read(buffer)) != -1) {
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
                // Exact content match: zero storage write, zero version row creation
                return UploadMediaAssetVersionConditionalResult.unchanged(
                        command.assetId(),
                        probeResult.currentVersionNumber()
                );
            }

            // Hashes differ (or current version has no hash) -> execute normal storage write
            StorageKey storageKey = StorageKey.of("objects/" + UUID.randomUUID());
            StorageProviderId providerId = binaryStoragePort.providerId();

            try (InputStream uploadIn = Files.newInputStream(spoolFile)) {
                binaryStoragePort.store(
                        storageKey,
                        uploadIn,
                        command.sizeBytes(),
                        mimeType
                );
            } catch (IOException e) {
                throw new StorageException("Failed to stream spooled content to storage: " + e.getMessage(), e);
            }

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

            RegisterMediaAssetVersionConditionalResult registerResult;
            try {
                registerResult = registerMediaAssetVersionUseCase.registerConditionalVersion(registerCommand);
            } catch (RuntimeException primaryException) {
                compensateStorage(storageKey, primaryException);
                throw primaryException;
            }

            if (registerResult.outcome() == MediaVersionUploadOutcome.UNCHANGED) {
                // Concurrent race resolution: another worker committed this identical hash in the interim
                try {
                    binaryStoragePort.delete(storageKey);
                } catch (RuntimeException cleanupException) {
                    // best-effort cleanup
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
