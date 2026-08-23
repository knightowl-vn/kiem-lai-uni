package com.universe.wiki.application.article.update.published;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.common.WikiArticleDTOMapper;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class UpdatePublishedWikiArticleUseCase {

    private static final String DEFAULT_EDIT_SUMMARY =
            "Cập nhật nội dung bài viết đã xuất bản";

    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public UpdatePublishedWikiArticleUseCase(
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

        boolean changed =
                article.updatePublishedContent(
                        command.summary(),
                        command.content(),
                        command.actorId(),
                        now
                );

        if (!changed) {
            return WikiArticleDTOMapper.toDTO(
                    article
            );
        }

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
}