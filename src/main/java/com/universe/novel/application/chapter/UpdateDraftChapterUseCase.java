package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterSlugAlreadyExistsException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.contracts.dto.ChapterDTO;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.Slug;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UpdateDraftChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ClockPort
            clockPort;

    public UpdateDraftChapterUseCase(
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
            UpdateDraftChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Update draft chapter command không được để trống."
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

        Slug newSlug =
                new Slug(
                        command.slug()
                );

        ensureSlugAvailable(
                chapter,
                newSlug
        );

        /*
         * Phải lấy version TRƯỚC khi Domain mutate.
         */
        long expectedVersion =
                chapter.getAggregateVersion();

        Instant now =
                clockPort.now();

        chapter.updateDraft(
                command.chapterNumber(),
                command.title(),
                newSlug,
                command.summary(),
                command.content(),
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