package com.universe.novel.application.chapter.reference;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.reference.ChapterWikiReference;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ListChapterWikiReferencesUseCase {

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ChapterWikiReferenceRepositoryPort referenceRepositoryPort;

    public ListChapterWikiReferencesUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterWikiReferenceRepositoryPort referenceRepositoryPort
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(
                chapterRepositoryPort,
                "ChapterRepositoryPort không được để trống."
        );
        this.referenceRepositoryPort = Objects.requireNonNull(
                referenceRepositoryPort,
                "ChapterWikiReferenceRepositoryPort không được để trống."
        );
    }

    public ChapterWikiReferenceListPageDTO execute(UUID chapterId) {
        Objects.requireNonNull(chapterId, "Chapter ID không được để trống.");

        Chapter chapter = chapterRepositoryPort.findById(chapterId)
                .orElseThrow(() -> new ChapterNotFoundException(chapterId));

        long currentContentVersion = chapter.getContentVersion();
        List<ChapterWikiReference> references = referenceRepositoryPort.findByChapterId(chapterId);

        List<ChapterWikiReferenceItemDTO> items = references.stream()
                .map(ref -> toItemDTO(ref, currentContentVersion))
                .toList();

        int totalCount = items.size();
        int activeCount = (int) items.stream()
                .filter(item -> item.status() == ChapterWikiReferenceStatus.ACTIVE)
                .count();
        int staleCount = totalCount - activeCount;

        return new ChapterWikiReferenceListPageDTO(
                chapter.getId(),
                chapter.getTitle(),
                chapter.getChapterNumber(),
                currentContentVersion,
                items,
                totalCount,
                activeCount,
                staleCount
        );
    }

    private ChapterWikiReferenceItemDTO toItemDTO(ChapterWikiReference ref, long currentContentVersion) {
        ChapterWikiReferenceStatus status = computeStatus(ref, currentContentVersion);

        return new ChapterWikiReferenceItemDTO(
                ref.getId(),
                ref.getChapterId(),
                ref.getTerm(),
                ref.getNormalizedTerm(),
                ref.getReferenceScope(),
                ref.getOccurrenceIndex(),
                ref.getContextSnippet(),
                ref.getBoundContentVersion(),
                currentContentVersion,
                ref.getWikiArticleId(),
                status,
                ref.getCreatedBy(),
                ref.getUpdatedBy(),
                ref.getCreatedAt(),
                ref.getUpdatedAt()
        );
    }

    private ChapterWikiReferenceStatus computeStatus(ChapterWikiReference ref, long currentContentVersion) {
        if (ref.getReferenceScope() == ChapterWikiReferenceScope.CHAPTER_WIDE) {
            return ChapterWikiReferenceStatus.ACTIVE;
        }

        if (ref.getBoundContentVersion() != null && ref.getBoundContentVersion() == currentContentVersion) {
            return ChapterWikiReferenceStatus.ACTIVE;
        }

        return ChapterWikiReferenceStatus.STALE;
    }
}
