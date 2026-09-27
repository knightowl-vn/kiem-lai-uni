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
public class AssignMediaAssetClientTagUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final ClockPort clockPort;

    public AssignMediaAssetClientTagUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            ClockPort clockPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(
                mediaAssetRepositoryPort,
                "MediaAssetRepositoryPort cannot be null."
        );
        this.clockPort = Objects.requireNonNull(
                clockPort,
                "ClockPort cannot be null."
        );
    }

    @Transactional
    public void execute(AssignMediaAssetClientTagCommand command) {
        Objects.requireNonNull(command, "AssignMediaAssetClientTagCommand cannot be null.");

        UUID assetId = command.assetId();
        String clientTag = command.clientTag();

        MediaAsset asset = mediaAssetRepositoryPort.findByIdForUpdate(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        String existingTag = asset.getClientTag();
        Instant now = clockPort.now();
        asset.assignClientTagIfAbsent(clientTag, now);

        if (!Objects.equals(existingTag, asset.getClientTag())) {
            mediaAssetRepositoryPort.save(asset);
        }
    }
}
