package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterSortOrderAlreadyExistsException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReorderChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ClockPort
            clockPort;

    public ReorderChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ClockPort clockPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public ChapterDTO execute(
            ReorderChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Reorder chapter command không được để trống."
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

        int newSortOrder =
                command.sortOrder();

        ensureSortOrderAvailable(
                chapter,
                newSortOrder
        );

        /*
         * Giữ version cũ trước khi Domain mutate.
         */
        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.reorder(
                newSortOrder,
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

    private void ensureSortOrderAvailable(
            Chapter chapter,
            int newSortOrder
    ) {
        /*
         * sortOrder hiện tại đang thuộc chính Chapter này.
         */
        if (chapter.getSortOrder()
                == newSortOrder) {
            return;
        }

        if (chapterRepositoryPort
                .existsByVolumeIdAndSortOrder(
                        chapter.getVolumeId(),
                        newSortOrder
                )) {

            throw new ChapterSortOrderAlreadyExistsException(
                    chapter.getVolumeId(),
                    newSortOrder
            );
        }
    }
}