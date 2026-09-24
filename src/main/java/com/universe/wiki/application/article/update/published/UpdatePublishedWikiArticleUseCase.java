package com.universe.wiki.application.article.update.published;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.common.WikiArticleDTOMapper;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class UpdatePublishedWikiArticleUseCase {

    private static final String DEFAULT_EDIT_SUMMARY =
            "Cập nhật nội dung bài viết đã xuất bản";

    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final WikiCoverOrphanRepositoryPort
            orphanRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public UpdatePublishedWikiArticleUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleRevisionRepositoryPort revisionRepositoryPort,
            WikiCoverOrphanRepositoryPort orphanRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort =
                articleRepositoryPort;

        this.revisionRepositoryPort =
                revisionRepositoryPort;

        this.orphanRepositoryPort =
                orphanRepositoryPort;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public WikiArticleDTO execute(
            UpdatePublishedWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Update published wiki article command "
                        + "không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        command.articleId(),
                        "Article ID không được để trống."
                );

        WikiArticle article =
                articleRepositoryPort
                        .findById(articleId)
                        .orElseThrow(() ->
                                new WikiArticleNotFoundException(
                                        articleId
                                )
                        );

        Instant now =
                clockPort.now();

        UUID previousCoverId =
                article.getCoverMediaAssetId();

        UUID targetCoverMediaAssetId = command.updateCover()
                ? command.coverMediaAssetId()
                : article.getCoverMediaAssetId();

        Integer targetCoverPositionX = command.updateCover()
                ? command.coverPositionX()
                : article.getCoverPositionX();

        Integer targetCoverPositionY = command.updateCover()
                ? command.coverPositionY()
                : article.getCoverPositionY();

        boolean changed =
                article.updatePublishedContent(
                        command.summary(),
                        command.content(),
                        targetCoverMediaAssetId,
                        targetCoverPositionX,
                        targetCoverPositionY,
                        command.actorId(),
                        now
                );

        UUID finalCoverId =
                article.getCoverMediaAssetId();

        if (!Objects.equals(previousCoverId, finalCoverId)) {
            lockCoverReferenceKeys(previousCoverId, finalCoverId);
        }

        if (changed) {
            articleRepositoryPort.save(
                    article
            );

            articleRepositoryPort.flush();

            saveRevision(
                    article,
                    resolveEditSummary(
                            command.editSummary()
                    )
            );
        }

        reconcileCoverOrphanState(
                previousCoverId,
                finalCoverId
        );

        return WikiArticleDTOMapper.toDTO(
                article
        );
    }

    private void saveRevision(
            WikiArticle article,
            String editSummary
    ) {
        UUID revisionId =
                idGeneratorPort.generate();

        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        revisionId,
                        article,
                        RevisionChangeType.UPDATE_PUBLISHED,
                        editSummary
                );

        revisionRepositoryPort.save(
                revision
        );
    }

    private String resolveEditSummary(
            String editSummary
    ) {
        if (editSummary == null
                || editSummary.isBlank()) {

            return DEFAULT_EDIT_SUMMARY;
        }

        return editSummary.trim();
    }

    private void lockCoverReferenceKeys(UUID... assetIds) {
        Stream.of(assetIds)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(articleRepositoryPort::lockCoverReferenceKey);
    }

    private void reconcileCoverOrphanState(
            UUID previousCoverId,
            UUID finalCoverId
    ) {
        if (previousCoverId != null && !previousCoverId.equals(finalCoverId)) {
            if (!articleRepositoryPort.hasCoverReference(previousCoverId)) {
                orphanRepositoryPort.recordOrphanObservation(
                        previousCoverId,
                        clockPort.now()
                );
            } else {
                orphanRepositoryPort.deleteByMediaAssetId(previousCoverId);
            }
        }
        if (finalCoverId != null) {
            orphanRepositoryPort.deleteByMediaAssetId(finalCoverId);
        }
    }
}