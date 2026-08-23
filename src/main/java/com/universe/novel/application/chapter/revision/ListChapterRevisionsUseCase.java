package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionQueryPort;
import com.universe.novel.contracts.dto.revision.ChapterRevisionListPageDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class ListChapterRevisionsUseCase {

    private static final int MAX_PAGE_SIZE =
            100;

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ChapterRevisionQueryPort
            chapterRevisionQueryPort;

    public ListChapterRevisionsUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterRevisionQueryPort chapterRevisionQueryPort
    ) {
        this.chapterRepositoryPort =
                Objects.requireNonNull(
                        chapterRepositoryPort,
                        "ChapterRepositoryPort không được để trống."
                );

        this.chapterRevisionQueryPort =
                Objects.requireNonNull(
                        chapterRevisionQueryPort,
                        "ChapterRevisionQueryPort không được để trống."
                );
    }

    @Transactional(readOnly = true)
    public ChapterRevisionListPageDTO execute(
            UUID chapterId,
            int page,
            int size
    ) {
        Objects.requireNonNull(
                chapterId,
                "Chapter ID không được để trống."
        );

        validatePagination(
                page,
                size
        );

        ensureChapterExists(
                chapterId
        );

        return chapterRevisionQueryPort
                .listRevisions(
                        chapterId,
                        page,
                        size
                );
    }

    private void ensureChapterExists(
            UUID chapterId
    ) {
        if (chapterRepositoryPort
                .findById(
                        chapterId
                )
                .isEmpty()) {

            throw new ChapterNotFoundException(
                    chapterId
            );
        }
    }

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 1) {
            throw new IllegalArgumentException(
                    "Số trang phải lớn hơn hoặc bằng 1."
            );
        }

        if (size < 1
                || size > MAX_PAGE_SIZE) {

            throw new IllegalArgumentException(
                    "Kích thước trang phải từ 1 đến "
                            + MAX_PAGE_SIZE
                            + "."
            );
        }
    }
}
