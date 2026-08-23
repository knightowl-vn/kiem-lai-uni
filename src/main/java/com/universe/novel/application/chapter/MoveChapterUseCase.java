package com.universe.novel.application.chapter;

import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.exceptions.VolumeNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class MoveChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final VolumeRepositoryPort
            volumeRepositoryPort;

    private final ClockPort
            clockPort;

    private final ChapterRevisionRecorder
            chapterRevisionRecorder;

    public MoveChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort,
            ClockPort clockPort,
            ChapterRevisionRecorder chapterRevisionRecorder
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.volumeRepositoryPort =
                volumeRepositoryPort;

        this.clockPort =
                clockPort;

        this.chapterRevisionRecorder =
                chapterRevisionRecorder;
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

        Volume targetVolume =
                volumeRepositoryPort
                        .findById(
                                targetVolumeId
                        )
                        .orElseThrow(() ->
                                new VolumeNotFoundException(
                                        targetVolumeId
                                )
                        );

        Integer chapterNumber =
                chapter.getChapterNumber();

        if (chapterNumber == null) {
            throw new IllegalStateException(
                    "Chương phải có số chương trước khi di chuyển."
            );
        }

        Slug targetSlug =
                ChapterSlugGenerator.generate(
                        targetVolume,
                        chapterNumber
                );

        ensureSlugAvailable(
                chapter,
                targetSlug
        );

        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.moveToVolume(
                targetVolumeId,
                targetSlug,
                command.actorId(),
                now
        );

        Chapter savedChapter =
                chapterRepositoryPort.save(
                        chapter,
                        expectedVersion
                );

        chapterRevisionRecorder.record(
                savedChapter,
                ChapterRevisionChangeType.MOVE_VOLUME,
                command.actorId(),
                null
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
                    "Chapter đã thuộc Volume đích."
            );
        }
    }

    private void ensureSlugAvailable(
            Chapter currentChapter,
            Slug slug
    ) {
        Optional<Chapter> existingChapter =
                chapterRepositoryPort
                        .findBySlug(
                                slug
                        );

        boolean belongsToAnotherChapter =
                existingChapter.isPresent()
                        && !existingChapter
                        .orElseThrow()
                        .getId()
                        .equals(
                                currentChapter.getId()
                        );

        if (belongsToAnotherChapter) {
            throw new ChapterSlugAlreadyExistsException(
                    "Slug của chương đã tồn tại: "
                            + slug.value()
            );
        }
    }
}
