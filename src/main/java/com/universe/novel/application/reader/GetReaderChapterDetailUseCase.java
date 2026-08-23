package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort.ReaderChapterRecord;
import com.universe.novel.contracts.dto.reader.ReaderChapterDetailDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterNavigationDTO;
import com.universe.novel.contracts.dto.reader.ReaderChapterTocItemDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class GetReaderChapterDetailUseCase {

    private final ReaderChapterDetailQueryPort
            readerChapterDetailQueryPort;

    private final NovelMarkdownRenderer
            novelMarkdownRenderer;

    public GetReaderChapterDetailUseCase(
            ReaderChapterDetailQueryPort readerChapterDetailQueryPort,
            NovelMarkdownRenderer novelMarkdownRenderer
    ) {
        this.readerChapterDetailQueryPort =
                Objects.requireNonNull(
                        readerChapterDetailQueryPort,
                        "ReaderChapterDetailQueryPort không được để trống."
                );

        this.novelMarkdownRenderer =
                Objects.requireNonNull(
                        novelMarkdownRenderer,
                        "NovelMarkdownRenderer không được để trống."
                );
    }

    public ReaderChapterDetailDTO execute(
            String chapterSlug
    ) {
        if (chapterSlug == null || chapterSlug.isBlank()) {
            throw new ChapterNotFoundException(
                    chapterSlug
            );
        }

        String normalizedSlug =
                chapterSlug.trim().toLowerCase();

        ReaderChapterRecord chapterRecord =
                readerChapterDetailQueryPort
                        .findPublishedChapterBySlug(
                                normalizedSlug
                        )
                        .orElseThrow(() -> new ChapterNotFoundException(
                                normalizedSlug
                        ));

        String contentHtml =
                novelMarkdownRenderer.renderToHtml(
                        chapterRecord.rawContent()
                );

        ReaderChapterNavigationDTO previousChapter =
                readerChapterDetailQueryPort
                        .findPreviousPublishedChapter(
                                chapterRecord.chapterNumber()
                        )
                        .orElse(null);

        ReaderChapterNavigationDTO nextChapter =
                readerChapterDetailQueryPort
                        .findNextPublishedChapter(
                                chapterRecord.chapterNumber()
                        )
                        .orElse(null);

        List<ReaderChapterTocItemDTO> tableOfContents =
                readerChapterDetailQueryPort
                        .findAllPublishedChaptersForToc();

        ReaderVolumeSummaryDTO volume =
                new ReaderVolumeSummaryDTO(
                        chapterRecord.volumeId(),
                        chapterRecord.volumeTitle(),
                        chapterRecord.volumeSlug(),
                        chapterRecord.volumeSortOrder()
                );

        return new ReaderChapterDetailDTO(
                chapterRecord.id(),
                chapterRecord.chapterNumber(),
                chapterRecord.title(),
                chapterRecord.slug(),
                contentHtml,
                volume,
                previousChapter,
                nextChapter,
                tableOfContents
        );
    }
}
