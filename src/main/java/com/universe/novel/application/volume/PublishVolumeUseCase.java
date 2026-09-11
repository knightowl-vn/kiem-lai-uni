package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.application.reader.PublicNovelLandingInvalidationCoordinator;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Volume;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class PublishVolumeUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    private final PublicNovelLandingInvalidationCoordinator
            publicNovelLandingInvalidationCoordinator;

    public PublishVolumeUseCase(
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort,
            PublicNovelLandingInvalidationCoordinator publicNovelLandingInvalidationCoordinator
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;

        this.publicNovelLandingInvalidationCoordinator =
                publicNovelLandingInvalidationCoordinator;
    }

    @Transactional
    public VolumeDTO execute(
            PublishVolumeCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Publish volume command không được để trống."
        );

        UUID volumeId =
                Objects.requireNonNull(
                        command.volumeId(),
                        "Volume ID không được để trống."
                );

        Volume volume =
                volumeRepositoryPort
                        .findById(
                                volumeId
                        )
                        .orElseThrow(() ->
                                new VolumeNotFoundException(
                                        volumeId
                                )
                        );

        /*
         * Phải giữ version trước khi Domain mutate.
         */
        long expectedVersion =
                volume.getAggregateVersion();

        Instant now =
                clockPort.now();

        volume.publish(
                command.actorId(),
                now
        );

        Volume savedVolume =
                volumeRepositoryPort.save(
                        volume,
                        expectedVersion
                );

        publicNovelLandingInvalidationCoordinator.invalidateAfterCommit();

        return VolumeDTOMapper.toDTO(
                savedVolume
        );
    }
}