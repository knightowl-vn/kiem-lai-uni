package com.universe.wiki.application.article.delete;

import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions
        .WikiArticleNotFoundException;

import com.universe.wiki.application.ports
        .WikiArticleRepositoryPort;

import com.universe.wiki.application.ports
        .WikiArticleRevisionRepositoryPort;

import com.universe.wiki.application.ports
        .WikiCoverOrphanRepositoryPort;

import com.universe.wiki.domain.article
        .WikiArticle;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class DeleteWikiArticleUseCase {

    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final WikiCoverOrphanRepositoryPort
            orphanRepositoryPort;

    private final ClockPort
            clockPort;

    public DeleteWikiArticleUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleRevisionRepositoryPort revisionRepositoryPort,
            WikiCoverOrphanRepositoryPort orphanRepositoryPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort =
                articleRepositoryPort;

        this.revisionRepositoryPort =
                revisionRepositoryPort;

        this.orphanRepositoryPort =
                orphanRepositoryPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public void execute(
            DeleteWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Delete wiki article command không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        command.articleId(),
                        "Article ID không được để trống."
                );

        WikiArticle article =
                articleRepositoryPort
                        .findById(
                                articleId
                        )
                        .orElseThrow(() ->
                                new WikiArticleNotFoundException(
                                        articleId
                                )
                        );

        /*
         * PUBLISHED không được xóa trực tiếp.
         */
        article.ensureCanBeDeleted();

        UUID coverMediaAssetId = article.getCoverMediaAssetId();

        if (coverMediaAssetId != null) {
            articleRepositoryPort.lockCoverReferenceKey(coverMediaAssetId);
        }

        /*
         * FK revision → article đang ON DELETE RESTRICT,
         * vì vậy bắt buộc xóa revisions trước.
         */
        revisionRepositoryPort
                .deleteAllByArticleId(
                        articleId
                );

        articleRepositoryPort
                .deleteById(
                        articleId
                );

        articleRepositoryPort.flush();

        if (coverMediaAssetId != null) {
            if (!articleRepositoryPort.hasCoverReference(coverMediaAssetId)) {
                orphanRepositoryPort.recordOrphanObservation(
                        coverMediaAssetId,
                        clockPort.now()
                );
            } else {
                orphanRepositoryPort.deleteByMediaAssetId(coverMediaAssetId);
            }
        }
    }
}