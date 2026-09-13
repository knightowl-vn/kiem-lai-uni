package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.render.NovelMarkdownRenderer;
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.narration.NarrationTextSegmenter;
import com.universe.novel.application.ports.ChapterNarrationSegmentRepositoryPort;
import com.universe.novel.application.ports.PublicReaderRenderedChapterLoadResult;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort;
import com.universe.novel.application.ports.ReaderChapterDetailQueryPort.ReaderChapterRecord;
import com.universe.novel.application.reader.render.ReaderNarrationMarkdownRenderer;
import com.universe.novel.contracts.dto.reader.ReaderChapterRenderedSnapshotDTO;
import com.universe.novel.contracts.dto.reader.ReaderVolumeSummaryDTO;
import com.universe.novel.domain.narration.ChapterNarrationSegmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class PublicReaderRenderedChapterLoader {
    private static final Logger log = LoggerFactory.getLogger(PublicReaderRenderedChapterLoader.class);

    private final ReaderChapterDetailQueryPort readerChapterDetailQueryPort;
    private final NovelMarkdownRenderer novelMarkdownRenderer;
    private final NarrationTextSegmenter narrationTextSegmenter;
    private final ChapterNarrationSegmentRepositoryPort segmentRepository;
    private final ReaderNarrationBlockMappingResolver blockMappingResolver;
    private final ReaderNarrationMarkdownRenderer narrationRenderer;

    public PublicReaderRenderedChapterLoader(
            ReaderChapterDetailQueryPort readerChapterDetailQueryPort,
            NovelMarkdownRenderer novelMarkdownRenderer,
            NarrationTextSegmenter narrationTextSegmenter,
            ChapterNarrationSegmentRepositoryPort segmentRepository,
            ReaderNarrationBlockMappingResolver blockMappingResolver,
            ReaderNarrationMarkdownRenderer narrationRenderer) {
        this.readerChapterDetailQueryPort = Objects.requireNonNull(readerChapterDetailQueryPort);
        this.novelMarkdownRenderer = Objects.requireNonNull(novelMarkdownRenderer);
        this.narrationTextSegmenter = Objects.requireNonNull(narrationTextSegmenter);
        this.segmentRepository = Objects.requireNonNull(segmentRepository);
        this.blockMappingResolver = Objects.requireNonNull(blockMappingResolver);
        this.narrationRenderer = Objects.requireNonNull(narrationRenderer);
    }

    @Transactional(readOnly = true)
    public PublicReaderRenderedChapterLoadResult load(String normalizedSlug) {
        Objects.requireNonNull(normalizedSlug, "normalizedSlug must not be null");

        ReaderChapterRecord chapterRecord = readerChapterDetailQueryPort.findPublishedChapterBySlug(normalizedSlug)
                .orElseThrow(() -> new ChapterNotFoundException(normalizedSlug));

        String markdown = chapterRecord.rawContent();
        String contentHtml;
        boolean cacheable = true;

        try {
            var currentSegments = segmentRepository.findByChapterIdAndStatus(
                    chapterRecord.id(),
                    ChapterNarrationSegmentStatus.CURRENT
            );
            var plans = narrationTextSegmenter.plan(markdown);
            var mapping = blockMappingResolver.resolve(chapterRecord.id(), plans, currentSegments);
            if (!mapping.isEmpty()) {
                contentHtml = narrationRenderer.renderToHtml(markdown, mapping);
            } else {
                contentHtml = novelMarkdownRenderer.renderToHtml(markdown);
            }
        } catch (RuntimeException exception) {
            log.warn("Reader narration mapping unavailable for chapter {}", chapterRecord.id(), exception);
            contentHtml = novelMarkdownRenderer.renderToHtml(markdown);
            cacheable = false;
        }

        ReaderVolumeSummaryDTO volume = new ReaderVolumeSummaryDTO(
                chapterRecord.volumeId(),
                chapterRecord.volumeTitle(),
                chapterRecord.volumeSlug(),
                chapterRecord.volumeSortOrder()
        );

        ReaderChapterRenderedSnapshotDTO snapshot = new ReaderChapterRenderedSnapshotDTO(
                chapterRecord.id(),
                chapterRecord.chapterNumber(),
                chapterRecord.title(),
                chapterRecord.slug(),
                contentHtml,
                volume
        );

        return new PublicReaderRenderedChapterLoadResult(snapshot, cacheable);
    }
}
