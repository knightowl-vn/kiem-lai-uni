package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.narration.NarrationTextSegmenter;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.reader.render.ReaderNarrationMarkdownRenderer;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(GetReaderChapterDetailUseCase.class);
    private final NarrationTextSegmenter narrationTextSegmenter;
    private final ChapterNarrationSegmentRepositoryPort segmentRepository;
    private final ReaderNarrationBlockMappingResolver blockMappingResolver;
    private final ReaderNarrationMarkdownRenderer narrationRenderer;

    private final ReaderChapterDetailQueryPort
            readerChapterDetailQueryPort;

    private final NovelMarkdownRenderer
            novelMarkdownRenderer;

    public GetReaderChapterDetailUseCase(
            ReaderChapterDetailQueryPort readerChapterDetailQueryPort,
            NovelMarkdownRenderer novelMarkdownRenderer,
            NarrationTextSegmenter narrationTextSegmenter,
            ChapterNarrationSegmentRepositoryPort segmentRepository,
            ReaderNarrationBlockMappingResolver blockMappingResolver,
            ReaderNarrationMarkdownRenderer narrationRenderer
    ) {
        this.narrationTextSegmenter = Objects.requireNonNull(narrationTextSegmenter);
        this.segmentRepository = Objects.requireNonNull(segmentRepository);
        this.blockMappingResolver = Objects.requireNonNull(blockMappingResolver);
        this.narrationRenderer = Objects.requireNonNull(narrationRenderer);
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

        String contentHtml = renderReaderContent(chapterRecord);

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
    private String renderReaderContent(ReaderChapterRecord chapterRecord) {
        String markdown = chapterRecord.rawContent();
        try {
            var currentSegments = segmentRepository.findByChapterIdAndStatus(chapterRecord.id(), ChapterNarrationSegmentStatus.CURRENT);
            var plans = narrationTextSegmenter.plan(markdown);
            var mapping = blockMappingResolver.resolve(chapterRecord.id(), plans, currentSegments);
            if (!mapping.isEmpty()) return narrationRenderer.renderToHtml(markdown, mapping);
        } catch (RuntimeException exception) {
            // Narration annotation is optional; prose remains readable if its mapping cannot be obtained.
            log.warn("Reader narration mapping unavailable for chapter {}", chapterRecord.id(), exception);
        }
        return novelMarkdownRenderer.renderToHtml(markdown);
    }
}
