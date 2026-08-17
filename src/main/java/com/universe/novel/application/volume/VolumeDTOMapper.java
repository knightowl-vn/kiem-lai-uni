package com.universe.novel.application.volume;

import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Volume;

import java.util.Objects;

final class VolumeDTOMapper {

    private VolumeDTOMapper() {
    }

    static VolumeDTO toDTO(
            Volume volume
    ) {
        Objects.requireNonNull(
                volume,
                "Volume không được để trống."
        );

        return new VolumeDTO(
                volume.getId(),
                volume.getTitle(),
                volume.getSlug().value(),
                volume.getDescription(),
                volume.getSortOrder(),
                volume.getStatus().name(),
                volume.getCreatedBy(),
                volume.getUpdatedBy(),
                volume.getPublishedBy(),
                volume.getArchivedBy(),
                volume.getCreatedAt(),
                volume.getUpdatedAt(),
                volume.getPublishedAt(),
                volume.getArchivedAt(),
                volume.getAggregateVersion()
        );
    }
}