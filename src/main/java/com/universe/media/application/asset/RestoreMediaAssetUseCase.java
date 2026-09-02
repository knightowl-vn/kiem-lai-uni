package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.shared.time.ClockPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RestoreMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final ClockPort clockPort;

    public RestoreMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetRepositoryPort,
                        "MediaAssetRepositoryPort cannot be null."
                );
        this.clockPort =
                Objects.requireNonNull(
                        clockPort,
                        "ClockPort cannot be null."
                );
    }

    @Transactional
    public void execute(
            RestoreMediaAssetCommand command
    ) {
        Objects.requireNonNull(
                command,
                "RestoreMediaAssetCommand cannot be null."
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

        Instant now = clockPort.now();

        asset.restoreToActive(now);

        mediaAssetRepositoryPort.save(asset);
    }
}
