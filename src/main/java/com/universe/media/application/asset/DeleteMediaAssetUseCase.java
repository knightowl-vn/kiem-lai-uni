package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.exceptions.MediaReferenceCheckUnavailableException;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.shared.time.ClockPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DeleteMediaAssetUseCase {

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final ClockPort clockPort;
    private final List<MediaAssetReferenceCheckPort> referenceCheckPorts;

    @Autowired
    public DeleteMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            ClockPort clockPort,
            List<MediaAssetReferenceCheckPort> referenceCheckPorts
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
        this.referenceCheckPorts = referenceCheckPorts != null ? referenceCheckPorts : List.of();
    }

    public DeleteMediaAssetUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            ClockPort clockPort
    ) {
        this(mediaAssetRepositoryPort, clockPort, List.of());
    }

    @Transactional
    public void execute(
            DeleteMediaAssetCommand command
    ) {
        Objects.requireNonNull(
                command,
                "DeleteMediaAssetCommand cannot be null."
        );

        UUID assetId =
                Objects.requireNonNull(
                        command.assetId(),
                        "Asset ID cannot be null."
                );

        MediaAsset asset =
                mediaAssetRepositoryPort
                        .findByIdForUpdate(assetId)
                        .orElseThrow(() ->
                                new MediaAssetNotFoundException(assetId)
                        );

        if (asset.getStatus() == MediaAssetStatus.DELETED) {
            return;
        }

        MediaType domainType = asset.getMediaType();
        MediaTypeDTO typeDto = MediaTypeDTO.valueOf(domainType.name());

        List<MediaAssetReferenceCheckPort> applicablePorts = referenceCheckPorts.stream()
                .filter(port -> port.supports(typeDto))
                .toList();

        if (domainType == MediaType.VIDEO && applicablePorts.isEmpty()) {
            throw new MediaReferenceCheckUnavailableException(
                    assetId,
                    typeDto,
                    "Cannot delete VIDEO asset [" + assetId + "]: "
                            + "required reference check protection is not registered or unavailable."
            );
        }

        for (MediaAssetReferenceCheckPort port : applicablePorts) {
            port.ensureNotReferenced(assetId);
        }

        Instant now = clockPort.now();

        asset.markDeleted(now);

        mediaAssetRepositoryPort.save(asset);
    }
}
