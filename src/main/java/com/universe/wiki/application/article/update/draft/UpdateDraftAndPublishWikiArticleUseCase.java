package com.universe.wiki.application.article.update.draft;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.article.common.WikiArticleDTOMapper;

import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;

import com.universe.wiki.application.ports.SlugGeneratorPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;

import com.universe.wiki.contracts.dto.WikiArticleDTO;

import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class UpdateDraftAndPublishWikiArticleUseCase {

    private static final String
            DEFAULT_UPDATE_AND_PUBLISH_SUMMARY =
            "Cập nhật và xuất bản bài viết";

    private static final String
            DEFAULT_PUBLISH_SUMMARY =
            "Xuất bản bài viết";


    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final WikiCoverOrphanRepositoryPort
            orphanRepositoryPort;

    private final SlugGeneratorPort
            slugGeneratorPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;


    public UpdateDraftAndPublishWikiArticleUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleRevisionRepositoryPort revisionRepositoryPort,
            WikiCoverOrphanRepositoryPort orphanRepositoryPort,
            SlugGeneratorPort slugGeneratorPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort =
                articleRepositoryPort;

        this.revisionRepositoryPort =
                revisionRepositoryPort;

        this.orphanRepositoryPort =
                orphanRepositoryPort;

        this.slugGeneratorPort =
                slugGeneratorPort;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;
    }


    @Transactional
    public WikiArticleDTO execute(
            UpdateDraftAndPublishWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Update draft and publish wiki article command "
                        + "không được để trống."
        );


        WikiArticle article =
                findArticle(
                        command.articleId()
                );


        Slug newSlug =
                slugGeneratorPort.generate(
                        command.title()
                );


        ensureSlugAvailable(
                article,
                command.articleType(),
                newSlug
        );


        Instant now =
                clockPort.now();

        UUID previousCoverId =
                article.getCoverMediaAssetId();

        /*
         * true:
         * có thay đổi editorial.
         *
         * false:
         * dữ liệu giữ nguyên,
         * chỉ chuyển DRAFT -> PUBLISHED.
         */
        UUID targetCoverMediaAssetId = command.updateCover()
                ? command.coverMediaAssetId()
                : article.getCoverMediaAssetId();

        Integer targetCoverPositionX = command.updateCover()
                ? command.coverPositionX()
                : article.getCoverPositionX();

        Integer targetCoverPositionY = command.updateCover()
                ? command.coverPositionY()
                : article.getCoverPositionY();

        boolean contentChanged =
                article.updateDraftAndPublish(
                        command.title(),
                        newSlug,
                        command.articleType(),
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

        articleRepositoryPort.save(
                article
        );

        articleRepositoryPort.flush();

        saveRevision(
                article,
                contentChanged,
                command.editSummary()
        );

        reconcileCoverOrphanState(
                previousCoverId,
                finalCoverId
        );

        return WikiArticleDTOMapper.toDTO(
                article
        );
    }


    private WikiArticle findArticle(
            UUID articleId
    ) {
        return articleRepositoryPort
                .findById(
                        articleId
                )
                .orElseThrow(() ->
                        new WikiArticleNotFoundException(
                                articleId
                        )
                );
    }


    private void ensureSlugAvailable(
            WikiArticle currentArticle,
            com.universe.wiki.domain.article.ArticleType articleType,
            Slug slug
    ) {
        Optional<WikiArticle> existingArticle =
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                articleType,
                                slug
                        );


        boolean belongsToAnotherArticle =
                existingArticle.isPresent()
                && !existingArticle
                        .orElseThrow()
                        .getId()
                        .equals(
                                currentArticle.getId()
                        );


        if (
                belongsToAnotherArticle
        ) {
            throw new ArticleSlugAlreadyExistsException(
                    "Slug bài viết đã tồn tại trong loại "
                            + articleType.name()
                            + ": "
                            + slug.value()
            );
        }
    }


    private void saveRevision(
            WikiArticle article,
            boolean contentChanged,
            String editSummary
    ) {
        UUID revisionId =
                idGeneratorPort.generate();


        RevisionChangeType changeType =
                contentChanged
                        ? RevisionChangeType.UPDATE_AND_PUBLISH
                        : RevisionChangeType.PUBLISH;


        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        revisionId,
                        article,
                        changeType,
                        resolveEditSummary(
                                editSummary,
                                contentChanged
                        )
                );


        revisionRepositoryPort.save(
                revision
        );
    }


    private String resolveEditSummary(
            String editSummary,
            boolean contentChanged
    ) {
        if (
                editSummary != null
                && !editSummary.isBlank()
        ) {
            return editSummary.trim();
        }


        return contentChanged
                ? DEFAULT_UPDATE_AND_PUBLISH_SUMMARY
                : DEFAULT_PUBLISH_SUMMARY;
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