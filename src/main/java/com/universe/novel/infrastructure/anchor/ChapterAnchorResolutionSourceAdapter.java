package com.universe.novel.infrastructure.anchor;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterAnchorResolutionSourcePort;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.infrastructure.markdown.CommonMarkReaderCanonicalBlocks;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Infrastructure adapter implementing {@link ChapterAnchorResolutionSourcePort}.
 *
 * <p>Loads current chapter content and content version from {@link ChapterRepositoryPort}
 * and extracts ordered canonical Reader blocks using {@link CommonMarkReaderCanonicalBlocks}.</p>
 */
@Component
public class ChapterAnchorResolutionSourceAdapter implements ChapterAnchorResolutionSourcePort {

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final CommonMarkReaderCanonicalBlocks canonicalBlocks;

    public ChapterAnchorResolutionSourceAdapter(
            ChapterRepositoryPort chapterRepositoryPort,
            CommonMarkReaderCanonicalBlocks canonicalBlocks
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(chapterRepositoryPort, "chapterRepositoryPort cannot be null");
        this.canonicalBlocks = Objects.requireNonNull(canonicalBlocks, "canonicalBlocks cannot be null");
    }

    @Override
    @Transactional(readOnly = true)
    public ChapterAnchorDocumentSnapshot loadCurrent(UUID chapterId) {
        Objects.requireNonNull(chapterId, "chapterId cannot be null");

        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        List<CommonMarkReaderCanonicalBlocks.CanonicalBlock> extracted = canonicalBlocks.extract(chapter.getContent());
        List<ReaderBlock> blocks = extracted.stream()
                .map(b -> new ReaderBlock(b.blockKey(), b.canonicalText()))
                .toList();

        return new ChapterAnchorDocumentSnapshot(chapter.getId(), chapter.getContentVersion(), blocks);
    }
}
