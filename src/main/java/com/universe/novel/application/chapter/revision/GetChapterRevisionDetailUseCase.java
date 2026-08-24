package com.universe.novel.application.chapter.revision;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.exceptions.ChapterRevisionNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterRevisionQueryPort;
import com.universe.novel.contracts.dto.revision.ChapterRevisionDetailDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetChapterRevisionDetailUseCase {

    private final ChapterRepositoryPort
            chapterRepositoryPort;

    private final ChapterRevisionQueryPort
            chapterRevisionQueryPort;

    private final NovelMarkdownRenderer
            novelMarkdownRenderer;

    public GetChapterRevisionDetailUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterRevisionQueryPort chapterRevisionQueryPort,
            NovelMarkdownRenderer novelMarkdownRenderer
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

        this.novelMarkdownRenderer =
                Objects.requireNonNull(
                        novelMarkdownRenderer,
                        "NovelMarkdownRenderer không được để trống."
                );
    }

    @Transactional(readOnly = true)
    public ChapterRevisionDetailDTO execute(
            UUID chapterId,
            long revisionNumber
    ) {
        Objects.requireNonNull(
                chapterId,
                "Chapter ID không được để trống."
        );

        if (revisionNumber < 1L) {
            throw new IllegalArgumentException(
                    "Revision number phải lớn hơn hoặc bằng 1."
            );
        }

        ensureChapterExists(
                chapterId
        );

        ChapterRevisionDetailDTO rawDetail =
                chapterRevisionQueryPort
                        .getRevisionDetail(
                                chapterId,
                                revisionNumber
                        )
                        .orElseThrow(() ->
                                new ChapterRevisionNotFoundException(
                                        chapterId,
                                        revisionNumber
                                )
                        );

        String contentHtml =
                novelMarkdownRenderer
                        .renderToHtml(
                                rawDetail.content()
                        );

        return rawDetail.withContentHtml(
                contentHtml
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
}
