package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaVisibility;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChangeMediaVisibilityUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final Clock clock;

    public ChangeMediaVisibilityUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            Clock clock
    ) {
        this.mediaAssetRepositoryPort =
                Objects.requireNonNull(
                        mediaAssetRepositoryPort,
                        "MediaAssetRepositoryPort cannot be null."
                );
        this.clock =
                Objects.requireNonNull(
                        clock,
                        "Clock cannot be null."
                );
    }

    @Transactional
    public void execute(
            ChangeMediaVisibilityCommand command
    ) {
        Objects.requireNonNull(
                command,
                "ChangeMediaVisibilityCommand cannot be null."
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

        Instant now = Instant.now(clock);

        MediaVisibility currentVisibility = asset.getVisibility();

        asset.changeVisibility(
                command.newVisibility(),
                now
        );

        if (asset.getVisibility() != currentVisibility) {
            mediaAssetRepositoryPort.save(asset);
        }
    }
}
