package com.universe.novel.application.chapter;

import com.universe.novel.application.chapter.revision.ChapterRevisionRecorder;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.revision.ChapterRevisionChangeType;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class UnpublishChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ClockPort
            clockPort;

    private final ChapterRevisionRecorder
            chapterRevisionRecorder;

    public UnpublishChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ClockPort clockPort,
            ChapterRevisionRecorder chapterRevisionRecorder
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.clockPort =
                clockPort;

        this.chapterRevisionRecorder =
                chapterRevisionRecorder;
    }

    @Transactional
    public ChapterDTO execute(
            UnpublishChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Unpublish chapter command không được để trống."
        );

        UUID chapterId =
                Objects.requireNonNull(
                        command.chapterId(),
                        "Chapter ID không được để trống."
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

        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.unpublish(
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
                ChapterRevisionChangeType.UNPUBLISH,
                command.actorId(),
                null
        );

        return ChapterDTOMapper.toDTO(
                savedChapter
        );
    }
}
