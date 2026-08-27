package com.universe.wiki.application.article.alias;

import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleAliasRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.domain.article.WikiAliasNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class RemoveWikiArticleAliasUseCase {

    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final WikiArticleAliasRepositoryPort aliasRepositoryPort;

    public RemoveWikiArticleAliasUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleAliasRepositoryPort aliasRepositoryPort
    ) {
        this.articleRepositoryPort = articleRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
    }

    @Transactional
    public void execute(RemoveWikiArticleAliasCommand command) {
        Objects.requireNonNull(command, "Remove wiki article alias command không được để trống.");
        UUID articleId = Objects.requireNonNull(command.articleId(), "Article ID không được để trống.");

        articleRepositoryPort.findById(articleId)
                .orElseThrow(() -> new WikiArticleNotFoundException(articleId));

        String normalizedAlias = WikiAliasNormalizer.normalize(command.alias());
        if (normalizedAlias.isEmpty()) {
            return;
        }

        aliasRepositoryPort.deleteByArticleIdAndNormalizedAlias(articleId, normalizedAlias);
    }
}