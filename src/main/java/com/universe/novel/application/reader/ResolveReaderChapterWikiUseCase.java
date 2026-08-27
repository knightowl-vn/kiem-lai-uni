package com.universe.novel.application.reader;

import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.application.ports.PublishedWikiArticlePort;
import com.universe.novel.application.ports.VolumeRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.ChapterStatus;
import com.universe.novel.domain.Volume;
import com.universe.novel.domain.VolumeStatus;
import com.universe.novel.domain.reference.ChapterWikiReference;
import com.universe.novel.domain.reference.ChapterWikiReferenceTermNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case phân giải tham chiếu Wiki cho người đọc theo 3 cấp độ ưu tiên:
 * 1. OCCURRENCE_BINDING: Khớp chính xác occurrenceIndex và không bị stale (boundContentVersion == chapter.contentVersion).
 * 2. CHAPTER_WIDE_BINDING: Khớp binding toàn chương (occurrenceIndex = 0).
 * 3. GLOBAL_LOOKUP: Tra cứu từ điển ngữ cảnh Wiki toàn cục.
 */
@Service
@Transactional(readOnly = true)
public class ResolveReaderChapterWikiUseCase {

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final VolumeRepositoryPort volumeRepositoryPort;
    private final ChapterWikiReferenceRepositoryPort referenceRepositoryPort;
    private final PublishedWikiArticlePort publishedWikiArticlePort;
    private final LookupContextualWikiUseCase lookupContextualWikiUseCase;

    public ResolveReaderChapterWikiUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            VolumeRepositoryPort volumeRepositoryPort,
            ChapterWikiReferenceRepositoryPort referenceRepositoryPort,
            PublishedWikiArticlePort publishedWikiArticlePort,
            LookupContextualWikiUseCase lookupContextualWikiUseCase
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(
                chapterRepositoryPort,
                "ChapterRepositoryPort không được để trống."
        );
        this.volumeRepositoryPort = Objects.requireNonNull(
                volumeRepositoryPort,
                "VolumeRepositoryPort không được để trống."
        );
        this.referenceRepositoryPort = Objects.requireNonNull(
                referenceRepositoryPort,
                "ChapterWikiReferenceRepositoryPort không được để trống."
        );
        this.publishedWikiArticlePort = Objects.requireNonNull(
                publishedWikiArticlePort,
                "PublishedWikiArticlePort không được để trống."
        );
        this.lookupContextualWikiUseCase = Objects.requireNonNull(
                lookupContextualWikiUseCase,
                "LookupContextualWikiUseCase không được để trống."
        );
    }

    public ReaderChapterWikiResolutionResult execute(ResolveReaderChapterWikiQuery query) {
        if (query == null || query.selectedTerm() == null || query.selectedTerm().isBlank()) {
            return new ReaderChapterWikiResolutionResult(
                    "",
                    ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP,
                    false,
                    List.of(),
                    null
            );
        }

        String rawTerm = query.selectedTerm();
        String displayTerm;
        String normalizedTerm;
        try {
            displayTerm = ChapterWikiReferenceTermNormalizer.normalizeDisplayTerm(rawTerm);
            normalizedTerm = ChapterWikiReferenceTermNormalizer.normalizeSearchKey(rawTerm);
        } catch (IllegalArgumentException e) {
            // Overlong or invalid term: fall through gracefully to global lookup
            return fallbackToGlobalLookup(rawTerm);
        }

        // Public readability check: chapter-specific bindings are only exposed for readable chapters
        if (query.chapterId() != null) {
            Optional<Chapter> chapterOpt = chapterRepositoryPort.findById(query.chapterId());
            if (chapterOpt.isPresent()) {
                Chapter chapter = chapterOpt.get();
                if (isPubliclyReadable(chapter)) {
                    long currentContentVersion = chapter.getContentVersion();

                    // Priority 1: Active occurrence-specific binding
                    if (query.occurrenceIndex() != null && query.occurrenceIndex() >= 1) {
                        Optional<ChapterWikiReference> occRefOpt = referenceRepositoryPort
                                .findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                                        query.chapterId(),
                                        normalizedTerm,
                                        query.occurrenceIndex()
                                );

                        if (occRefOpt.isPresent()) {
                            ChapterWikiReference occRef = occRefOpt.get();
                            // Invariant: must match current contentVersion
                            if (occRef.getBoundContentVersion() != null
                                    && occRef.getBoundContentVersion() == currentContentVersion) {
                                // Must have a published target article
                                Optional<PublishedWikiArticleSummary> articleOpt =
                                        publishedWikiArticlePort.findPublishedById(occRef.getWikiArticleId());

                                if (articleOpt.isPresent()) {
                                    PublishedWikiArticleSummary article = articleOpt.get();
                                    ReaderWikiLookupItem item = new ReaderWikiLookupItem(
                                            article.id(),
                                            article.title(),
                                            article.articleType(),
                                            article.slug(),
                                            article.summary()
                                    );
                                    return new ReaderChapterWikiResolutionResult(
                                            displayTerm,
                                            ChapterWikiReferenceResolutionSource.OCCURRENCE_BINDING,
                                            true,
                                            List.of(item),
                                            query.occurrenceIndex()
                                    );
                                }
                            }
                        }
                    }

                    // Priority 2: Chapter-wide binding
                    Optional<ChapterWikiReference> wideRefOpt = referenceRepositoryPort
                            .findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                                    query.chapterId(),
                                    normalizedTerm,
                                    0
                            );

                    if (wideRefOpt.isPresent()) {
                        ChapterWikiReference wideRef = wideRefOpt.get();
                        Optional<PublishedWikiArticleSummary> articleOpt =
                                publishedWikiArticlePort.findPublishedById(wideRef.getWikiArticleId());

                        if (articleOpt.isPresent()) {
                            PublishedWikiArticleSummary article = articleOpt.get();
                            ReaderWikiLookupItem item = new ReaderWikiLookupItem(
                                    article.id(),
                                    article.title(),
                                    article.articleType(),
                                    article.slug(),
                                    article.summary()
                            );
                            return new ReaderChapterWikiResolutionResult(
                                    displayTerm,
                                    ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING,
                                    true,
                                    List.of(item),
                                    null
                            );
                        }
                    }
                }
            }
        }

        // Priority 3: Global Contextual Lookup Fallback
        return fallbackToGlobalLookup(displayTerm);
    }

    private boolean isPubliclyReadable(Chapter chapter) {
        if (chapter.getStatus() != ChapterStatus.PUBLISHED) {
            return false;
        }
        Optional<Volume> volumeOpt = volumeRepositoryPort.findById(chapter.getVolumeId());
        return volumeOpt.isPresent() && volumeOpt.get().getStatus() == VolumeStatus.PUBLISHED;
    }

    private ReaderChapterWikiResolutionResult fallbackToGlobalLookup(String term) {
        ReaderWikiLookupResult lookupResult = lookupContextualWikiUseCase.execute(term);
        return new ReaderChapterWikiResolutionResult(
                lookupResult.query(),
                ChapterWikiReferenceResolutionSource.GLOBAL_LOOKUP,
                lookupResult.hasExactMatch(),
                lookupResult.items(),
                null
        );
    }
}
