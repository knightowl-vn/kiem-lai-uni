package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.VolumeDTO;
import com.universe.novel.domain.Volume;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RestoreVolumeUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    public RestoreVolumeUseCase(
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
            RestoreVolumeCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Restore volume command không được để trống."
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

        long expectedVersion =
                volume.getAggregateVersion();

        Instant now =
                clockPort.now();

        volume.restoreToDraft(
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
}
