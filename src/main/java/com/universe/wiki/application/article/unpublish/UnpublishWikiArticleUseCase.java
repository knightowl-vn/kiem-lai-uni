package com.universe.wiki.application.article.unpublish;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.article.common
        .WikiArticleDTOMapper;

import com.universe.wiki.application.exceptions
        .WikiArticleNotFoundException;

import com.universe.wiki.application.ports
        .WikiArticleRepositoryPort;

import com.universe.wiki.application.ports
        .WikiArticleRevisionRepositoryPort;

import com.universe.wiki.contracts.dto
        .WikiArticleDTO;

import com.universe.wiki.domain.article
        .WikiArticle;

import com.universe.wiki.domain.revision
        .RevisionChangeType;

import com.universe.wiki.domain.revision
        .WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class UnpublishWikiArticleUseCase {

    private static final String
            DEFAULT_EDIT_SUMMARY =
            "Gỡ xuất bản bài viết";

    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public UnpublishWikiArticleUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleRevisionRepositoryPort revisionRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort =
                articleRepositoryPort;

        this.revisionRepositoryPort =
                revisionRepositoryPort;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public WikiArticleDTO execute(
            UnpublishWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Unpublish wiki article command không được để trống."
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

        Instant now =
                clockPort.now();

        article.unpublish(
                command.actorId(),
                now
        );

        articleRepositoryPort.save(
                article
        );

        saveRevision(
                article,
                resolveEditSummary(
                        command.editSummary()
                )
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
                        RevisionChangeType.UNPUBLISH,
                        editSummary
                );

        revisionRepositoryPort.save(
                revision
        );
    }

    private String resolveEditSummary(
            String editSummary
    ) {
        if (
                editSummary == null
                || editSummary.isBlank()
        ) {
            return DEFAULT_EDIT_SUMMARY;
        }

        return editSummary.trim();
    }
}