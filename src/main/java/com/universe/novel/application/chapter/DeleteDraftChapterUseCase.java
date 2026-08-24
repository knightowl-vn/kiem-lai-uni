package com.universe.novel.application.chapter;

import com.universe.novel.application.exceptions.ChapterCannotBeDeletedException;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionRepositoryPort;
import com.universe.novel.domain.Chapter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class DeleteDraftChapterUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ChapterRevisionRepositoryPort
            chapterRevisionRepositoryPort;

    public DeleteDraftChapterUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterRevisionRepositoryPort chapterRevisionRepositoryPort
    ) {
        this.chapterRepositoryPort =
                chapterRepositoryPort;

        this.chapterRevisionRepositoryPort =
                chapterRevisionRepositoryPort;
    }

    @Transactional
    public void execute(
            DeleteDraftChapterCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Delete draft chapter command không được để trống."
        );

        UUID chapterId =
                Objects.requireNonNull(
                        command.chapterId(),
                        "Chapter ID không được để trống."
                );

        Objects.requireNonNull(
                command.actorId(),
                "Actor ID không được để trống."
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

        if (!chapter.canBeDeleted()) {
            throw new IllegalStateException(
                    "Chỉ chương ở trạng thái DRAFT mới được xóa."
            );
        }

        if (!chapterRevisionRepositoryPort
                .canSafelyHardDelete(
                        chapterId
                )) {

            throw new ChapterCannotBeDeletedException(
                    chapterId
            );
        }

        long expectedVersion =
                chapter.getAggregateVersion();

        chapterRevisionRepositoryPort.deleteAllByChapterId(
                chapterId
        );

        chapterRepositoryPort.delete(
                chapter,
                expectedVersion
        );
    }
}
