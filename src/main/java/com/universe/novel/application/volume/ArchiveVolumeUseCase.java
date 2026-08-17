package com.universe.novel.application.volume;

import com.universe.novel.application.exceptions.VolumeHasPublishedChaptersException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
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
public class ArchiveVolumeUseCase {

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ClockPort
            clockPort;

    public ArchiveVolumeUseCase(
            VolumeRepositoryPort volumeRepositoryPort,
            ChapterRepositoryPort chapterRepositoryPort,
            ClockPort clockPort
    ) {
        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public VolumeDTO execute(
            ArchiveVolumeCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Archive volume command không được để trống."
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

        ensureNoPublishedChapters(
                volumeId
        );

        /*
         * Phải lấy version trước khi Domain mutate.
         */
        long expectedVersion =
                volume.getAggregateVersion();

        Instant now =
                clockPort.now();

        volume.archive(
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

    private void ensureNoPublishedChapters(
            UUID volumeId
    ) {
        if (chapterRepositoryPort
                .existsPublishedByVolumeId(
                        volumeId
                )) {

            throw new VolumeHasPublishedChaptersException(
                    volumeId
            );
        }
    }
}