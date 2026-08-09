package com.universe.wiki.application.article.create;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.common.WikiArticleDTOMapper;
import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.ports.SlugGeneratorPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreateWikiArticleUseCase {

    private static final String
            DEFAULT_INITIAL_REVISION_SUMMARY =
            "Tạo bản nháp đầu tiên";

    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final SlugGeneratorPort
            slugGeneratorPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public CreateWikiArticleUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleRevisionRepositoryPort revisionRepositoryPort,
            SlugGeneratorPort slugGeneratorPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort =
                articleRepositoryPort;

        this.revisionRepositoryPort =
                revisionRepositoryPort;

        this.slugGeneratorPort =
                slugGeneratorPort;

        this.idGeneratorPort =
                idGeneratorPort;

        this.clockPort =
                clockPort;
    }

    @Transactional
    public WikiArticleDTO execute(
            CreateWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Create wiki article command không được để trống."
        );

        Slug slug =
                slugGeneratorPort.generate(
                        command.title()
                );

        if (articleRepositoryPort
                .existsByArticleTypeAndSlug(
                        command.articleType(),
                        slug
                )) {

            throw new ArticleSlugAlreadyExistsException(
                    "Slug bài viết đã tồn tại trong loại "
                            + command.articleType().name()
                            + ": "
                            + slug.value()
            );
        }

        UUID articleId =
                idGeneratorPort.generate();

        Instant now =
                clockPort.now();

        WikiArticle article =
                WikiArticle.createDraft(
                        articleId,
                        command.title(),
                        slug,
                        command.articleType(),
                        command.summary(),
                        command.content(),
                        command.actorId(),
                        now
                );

        articleRepositoryPort.save(
                article
        );

        saveInitialRevision(
                article,
                resolveEditSummary(
                        command.editSummary()
                )
        );

        return WikiArticleDTOMapper.toDTO(
                article
        );
    }

    private void saveInitialRevision(
            WikiArticle article,
            String editSummary
    ) {
        UUID revisionId =
                idGeneratorPort.generate();

        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        revisionId,
                        article,
                        RevisionChangeType.CREATE_DRAFT,
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

            return DEFAULT_INITIAL_REVISION_SUMMARY;
        }

        return editSummary.trim();
    }
}