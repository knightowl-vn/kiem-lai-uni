package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.exceptions.VolumeSlugAlreadyExistsException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UpdateDraftVolumeUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    public UpdateDraftVolumeUseCase(
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public VolumeDTO execute(
            UpdateDraftVolumeCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Update draft volume command không được để trống."
        );

        UUID volumeId =
                Objects.requireNonNull(
                        command.volumeId(),
                        "Volume ID không được để trống."
                );

        Volume volume =
                findVolume(
                        volumeId
                );

        Slug newSlug =
                new Slug(
                        command.slug()
                );

        ensureSlugAvailable(
                volume,
                newSlug
        );

        /*
         * Cực kỳ quan trọng:
         *
         * phải lấy version TRƯỚC khi Domain mutate.
         */
        long expectedVersion =
                volume.getAggregateVersion();

        Instant now =
                clockPort.now();

        volume.updateDraft(
                command.title(),
                newSlug,
                command.description(),
                command.actorId(),
                now
        );

        Volume savedVolume =
                volumeRepositoryPort.save(
                        volume,
                        expectedVersion
                );

        return VolumeDTOMapper.toDTO(
                savedVolume
        );
    }

    private Volume findVolume(
            UUID volumeId
    ) {
        return volumeRepositoryPort
                .findById(
                        volumeId
                )
                .orElseThrow(() ->
                        new VolumeNotFoundException(
                                volumeId
                        )
                );
    }

    private void ensureSlugAvailable(
            Volume currentVolume,
            Slug slug
    ) {
        Optional<Volume> existingVolume =
                volumeRepositoryPort
                        .findBySlug(
                                slug
                        );

        boolean belongsToAnotherVolume =
                existingVolume.isPresent()
                        && !existingVolume
                        .orElseThrow()
                        .getId()
                        .equals(
                                currentVolume.getId()
                        );

        if (belongsToAnotherVolume) {
            throw new VolumeSlugAlreadyExistsException(
                    "Slug của tập đã tồn tại: "
                            + slug.value()
            );
        }
    }
}