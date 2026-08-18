package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterSortOrderAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class MoveChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    public MoveChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public ChapterDTO execute(
            MoveChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Move chapter command không được để trống."
        );

        UUID chapterId =
                Objects.requireNonNull(
                        command.chapterId(),
                        "Chapter ID không được để trống."
                );

        UUID targetVolumeId =
                Objects.requireNonNull(
                        command.targetVolumeId(),
                        "Volume đích không được để trống."
                );

        Chapter chapter =
                chapterRepositoryPort
                        .findById(
                                chapterId
                        )
                        .orElseThrow(() ->
                                new ChapterNotFoundException(
                                        chapterId
                                )
                        );

        ensureDifferentVolume(
                chapter,
                targetVolumeId
        );

        ensureTargetVolumeExists(
                targetVolumeId
        );

        ensureSortOrderAvailable(
                targetVolumeId,
                command.targetSortOrder()
        );

        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.moveToVolume(
                targetVolumeId,
                command.targetSortOrder(),
                command.actorId(),
                now
        );

        Chapter savedChapter =
                chapterRepositoryPort.save(
                        chapter,
                        expectedVersion
                );

        return ChapterDTOMapper.toDTO(
                savedChapter
        );
    }

    private void ensureDifferentVolume(
            Chapter chapter,
            UUID targetVolumeId
    ) {
        if (chapter.getVolumeId()
                .equals(
                        targetVolumeId
                )) {

            throw new IllegalArgumentException(
                    "Chapter đã thuộc Volume đích. "
                            + "Hãy dùng chức năng sắp xếp lại chương."
            );
        }
    }

    private void ensureTargetVolumeExists(
            UUID targetVolumeId
    ) {
        if (volumeRepositoryPort
                .findById(
                        targetVolumeId
                )
                .isEmpty()) {

            throw new VolumeNotFoundException(
                    targetVolumeId
            );
        }
    }

    private void ensureSortOrderAvailable(
            UUID targetVolumeId,
            int targetSortOrder
    ) {
        if (chapterRepositoryPort
                .existsByVolumeIdAndSortOrder(
                        targetVolumeId,
                        targetSortOrder
                )) {

            throw new ChapterSortOrderAlreadyExistsException(
                    targetVolumeId,
                    targetSortOrder
            );
        }
    }
}