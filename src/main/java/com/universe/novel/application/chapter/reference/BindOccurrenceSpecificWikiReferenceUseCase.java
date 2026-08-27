package com.universe.novel.application.chapter.reference;

import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.ports.ChapterRepositoryPort;
import com.universe.novel.application.ports.ChapterWikiReferenceRepositoryPort;
import com.universe.novel.domain.Chapter;
import com.universe.novel.domain.reference.ChapterWikiReference;
import com.universe.novel.domain.reference.ChapterWikiReferenceTermNormalizer;
import com.universe.shared.id.IdGeneratorPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class BindOccurrenceSpecificWikiReferenceUseCase {

    private final ChapterRepositoryPort chapterRepositoryPort;
    private final ChapterWikiReferenceRepositoryPort referenceRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;

    public BindOccurrenceSpecificWikiReferenceUseCase(
            ChapterRepositoryPort chapterRepositoryPort,
            ChapterWikiReferenceRepositoryPort referenceRepositoryPort,
            IdGeneratorPort idGeneratorPort
    ) {
        this.chapterRepositoryPort = Objects.requireNonNull(
                chapterRepositoryPort,
                "ChapterRepositoryPort không được để trống."
        );
        this.referenceRepositoryPort = Objects.requireNonNull(
                referenceRepositoryPort,
                "ChapterWikiReferenceRepositoryPort không được để trống."
        );
        this.idGeneratorPort = Objects.requireNonNull(
                idGeneratorPort,
                "IdGeneratorPort không được để trống."
        );
    }

    public ChapterWikiReferenceItemDTO execute(BindOccurrenceSpecificWikiReferenceCommand command) {
        Objects.requireNonNull(command, "Command không được để trống.");

        Chapter chapter = chapterRepositoryPort.findById(command.chapterId())
                .orElseThrow(() -> new ChapterNotFoundException(command.chapterId()));

        long currentContentVersion = chapter.getContentVersion();
        String displayTerm = ChapterWikiReferenceTermNormalizer.normalizeDisplayTerm(command.term());
        String normalizedTerm = ChapterWikiReferenceTermNormalizer.normalizeSearchKey(command.term());

        Instant now = Instant.now();
        Optional<ChapterWikiReference> existingOpt = referenceRepositoryPort
                .findByChapterIdAndNormalizedTermAndOccurrenceIndex(
                        command.chapterId(),
                        normalizedTerm,
                        command.occurrenceIndex()
                );

        ChapterWikiReference referenceToSave;
        if (existingOpt.isPresent()) {
            referenceToSave = existingOpt.get();
            referenceToSave.updateTargetArticle(command.wikiArticleId(), command.actorId(), now);
            referenceToSave.updateOccurrenceContext(
                    command.occurrenceIndex(),
                    command.contextSnippet(),
                    currentContentVersion,
                    command.actorId(),
                    now
            );
        } else {
            UUID refId = idGeneratorPort.generate();
            referenceToSave = ChapterWikiReference.createOccurrenceSpecific(
                    refId,
                    command.chapterId(),
                    displayTerm,
                    command.occurrenceIndex(),
                    command.contextSnippet(),
                    currentContentVersion,
                    command.wikiArticleId(),
                    command.actorId(),
                    now
            );
        }

        ChapterWikiReference saved = referenceRepositoryPort.save(referenceToSave);
        return new ChapterWikiReferenceItemDTO(
                saved.getId(),
                saved.getChapterId(),
                saved.getTerm(),
                saved.getNormalizedTerm(),
                saved.getReferenceScope(),
                saved.getOccurrenceIndex(),
                saved.getContextSnippet(),
                saved.getBoundContentVersion(),
                currentContentVersion,
                saved.getWikiArticleId(),
                ChapterWikiReferenceStatus.ACTIVE,
                saved.getCreatedBy(),
                saved.getUpdatedBy(),
                saved.getCreatedAt(),
                saved.getUpdatedAt()
        );
    }
}
