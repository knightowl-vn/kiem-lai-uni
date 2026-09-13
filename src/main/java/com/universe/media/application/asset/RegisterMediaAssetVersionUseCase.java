package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RegisterMediaAssetVersionUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final ClockPort clockPort;

    public RegisterMediaAssetVersionUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetRepositoryPort,
                        "MediaAssetRepositoryPort cannot be null."
                );
        this.mediaAssetVersionRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetVersionRepositoryPort,
                        "MediaAssetVersionRepositoryPort cannot be null."
                );
        this.clockPort =
                Objects.requireNonNull(
                        clockPort,
                        "ClockPort cannot be null."
                );
    }

    @Transactional
    public RegisterMediaAssetVersionResult execute(
            RegisterMediaAssetVersionCommand command
    ) {
        Objects.requireNonNull(
                command,
                "RegisterMediaAssetVersionCommand cannot be null."
        );

        UUID assetId =
                Objects.requireNonNull(
                        command.assetId(),
                        "Asset ID cannot be null."
                );

        MediaAsset asset =
                mediaAssetRepositoryPort
                        .findById(assetId)
                        .orElseThrow(() ->
                                new MediaAssetNotFoundException(assetId)
                        );

        StorageLocation storageLocation =
                StorageLocation.of(
                        StorageProviderId.of(command.storageProviderId()),
                        StorageKey.of(command.storageKey())
                );

        if (mediaAssetVersionRepositoryPort.existsByStorageLocation(storageLocation)) {
            throw new DuplicateStorageLocationException(storageLocation);
        }

        ContentHash contentHash =
                ContentHash.of(command.contentHash());

        MimeType mimeType =
                MimeType.of(command.mimeType());

        Instant now = clockPort.now();

        int newVersionNumber = asset.registerNextVersion(now);

        UUID versionId = UUID.randomUUID();

        MediaAssetVersion version =
                MediaAssetVersion.create(
                        versionId,
                        asset.getId(),
                        newVersionNumber,
                        storageLocation,
                        command.publicUrl(),
                        contentHash,
                        mimeType,
                        command.sizeBytes(),
                        command.originalFilename(),
                        now
                );

        mediaAssetRepositoryPort.save(asset);
        mediaAssetVersionRepositoryPort.save(version);

        return new RegisterMediaAssetVersionResult(
                asset.getId(),
                version.getId(),
                newVersionNumber,
                now
        );
    }
}
