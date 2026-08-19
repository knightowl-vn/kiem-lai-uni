package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeSortOrderAlreadyExistsException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreateVolumeUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public CreateVolumeUseCase(
            VolumeRepositoryPort volumeRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public VolumeDTO execute(
            CreateVolumeCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Create volume command không được để trống."
        );

        if (command.sortOrder() < 1) {
            throw new IllegalArgumentException(
                    "Thứ tự sắp xếp phải lớn hơn hoặc bằng 1."
            );
        }

        Slug slug =
                new Slug(
                        "quyen-"
                                + command.sortOrder()
                );

        ensureSlugAvailable(
                slug
        );

        ensureSortOrderAvailable(
                command.sortOrder()
        );

        UUID volumeId =
                idGeneratorPort.generate();

        Instant now =
                clockPort.now();

        Volume volume =
                Volume.createDraft(
                        volumeId,
                        command.title(),
                        slug,
                        command.description(),
                        command.sortOrder(),
                        command.actorId(),
                        now
                );

        Volume savedVolume =
                volumeRepositoryPort.save(
                        volume,
                        0L
                );

        return VolumeDTOMapper.toDTO(
                savedVolume
        );
    }

    private void ensureSlugAvailable(
            Slug slug
    ) {
        if (volumeRepositoryPort.existsBySlug(
                slug
        )) {
            throw new VolumeSlugAlreadyExistsException(
                    "Slug của tập đã tồn tại: "
                            + slug.value()
            );
        }
    }

    private void ensureSortOrderAvailable(
            int sortOrder
    ) {
        if (volumeRepositoryPort.existsBySortOrder(
                sortOrder
        )) {
            throw new VolumeSortOrderAlreadyExistsException(
                    "Thứ tự sắp xếp của tập đã tồn tại: "
                            + sortOrder
            );
        }
    }
}