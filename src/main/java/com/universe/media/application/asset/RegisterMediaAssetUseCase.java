package com.universe.media.application.asset;

import com.universe.media.application.exceptions.DuplicateStorageLocationException;
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
public class RegisterMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final ClockPort clockPort;

    public RegisterMediaAssetUseCase(
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
    public RegisterMediaAssetResult execute(
            RegisterMediaAssetCommand command
    ) {
        Objects.requireNonNull(
                command,
                "RegisterMediaAssetCommand cannot be null."
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

        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = clockPort.now();

        MediaAsset asset =
                MediaAsset.registerInitial(
                        assetId,
                        command.mediaType(),
                        command.visibility(),
                        now
                );

        MediaAssetVersion version =
                MediaAssetVersion.create(
                        versionId,
                        assetId,
                        1,
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

        return new RegisterMediaAssetResult(
                asset.getId(),
                version.getId(),
                version.getVersionNumber(),
                asset.getMediaType(),
                asset.getVisibility(),
                asset.getStatus(),
                asset.getCreatedAt()
        );
    }
}
