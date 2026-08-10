package com.universe.wiki.application.article.restore;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.common.WikiArticleDTOMapper;
import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.exceptions.WikiArticleRevisionAlreadyCurrentException;
import com.universe.wiki.application.exceptions.WikiArticleRevisionNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RestoreWikiArticleUseCase {

    private final WikiArticleRepositoryPort
            articleRepositoryPort;

    private final WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private final IdGeneratorPort
            idGeneratorPort;

    private final ClockPort
            clockPort;

    public RestoreWikiArticleUseCase(
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
            RestoreWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Restore wiki article command không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        command.articleId(),
                        "Article ID không được để trống."
                );

        if (command.sourceRevisionNumber() < 1L) {
            throw new IllegalArgumentException(
                    "Source revision number phải lớn hơn hoặc bằng 1."
            );
        }

        UUID actorId =
                Objects.requireNonNull(
                        command.actorId(),
                        "Người khôi phục không được để trống."
                );

        WikiArticle article =
                findArticle(articleId);

        WikiArticleRevision sourceRevision =
                findSourceRevision(
                        articleId,
                        command.sourceRevisionNumber()
                );


        ensureSourceRevisionIsNotCurrentContent(
                article,
                sourceRevision
        );


        ensureSlugAvailable(
                article,
                sourceRevision.articleType(),
                sourceRevision.slug()
        );

        Instant now =
                clockPort.now();

        article.restoreAsDraft(
                sourceRevision.title(),
                sourceRevision.slug(),
                sourceRevision.articleType(),
                sourceRevision.summary(),
                sourceRevision.content(),
                actorId,
                now
        );

        articleRepositoryPort.save(
                article
        );

        saveRestoreRevision(
                article,
                resolveEditSummary(
                        command.editSummary(),
                        command.sourceRevisionNumber()
                )
        );

        return WikiArticleDTOMapper.toDTO(
                article
        );
    }

    private WikiArticle findArticle(
            UUID articleId
    ) {
        return articleRepositoryPort
                .findById(articleId)
                .orElseThrow(() ->
                        new WikiArticleNotFoundException(
                                articleId
                        )
                );
    }

    private WikiArticleRevision findSourceRevision(
            UUID articleId,
            long revisionNumber
    ) {
        return revisionRepositoryPort
                .findByArticleIdAndRevisionNumber(
                        articleId,
                        revisionNumber
                )
                .orElseThrow(() ->
                        new WikiArticleRevisionNotFoundException(
                                articleId,
                                revisionNumber
                        )
                );
    }

    private void ensureSlugAvailable(
            WikiArticle currentArticle,
            ArticleType restoredArticleType,
            Slug restoredSlug
    ) {
        Optional<WikiArticle> existingArticle =
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                restoredArticleType,
                                restoredSlug
                        );

        boolean belongsToAnotherArticle =
                existingArticle.isPresent()
                        && !existingArticle
                        .orElseThrow()
                        .getId()
                        .equals(
                                currentArticle.getId()
                        );

        if (belongsToAnotherArticle) {
            throw new ArticleSlugAlreadyExistsException(
                    "Slug bài viết đã tồn tại trong loại "
                            + restoredArticleType.name()
                            + ": "
                            + restoredSlug.value()
            );
        }
    }

    private void saveRestoreRevision(
            WikiArticle article,
            String editSummary
    ) {
        UUID newRevisionId =
                idGeneratorPort.generate();

        WikiArticleRevision newRevision =
                WikiArticleRevision.createSnapshot(
                        newRevisionId,
                        article,
                        RevisionChangeType.RESTORE,
                        editSummary
                );

        revisionRepositoryPort.save(
                newRevision
        );
    }

    private String resolveEditSummary(
            String editSummary,
            long sourceRevisionNumber
    ) {
        if (editSummary == null
                || editSummary.isBlank()) {

            return "Khôi phục từ revision "
                    + sourceRevisionNumber;
        }

        return editSummary.trim();
    }
    
    private void ensureSourceRevisionIsNotCurrentContent(
            WikiArticle article,
            WikiArticleRevision sourceRevision
    ) {
        if (
                article.getContentVersion()
                == sourceRevision.contentVersion()
        ) {
            throw new WikiArticleRevisionAlreadyCurrentException(
                    article.getContentVersion()
            );
        }
    }
}