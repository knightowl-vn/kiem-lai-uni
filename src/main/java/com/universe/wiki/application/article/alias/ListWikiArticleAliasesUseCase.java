package com.universe.wiki.application.article.alias;

import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleAliasRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleAliasDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ListWikiArticleAliasesUseCase {

    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final WikiArticleAliasRepositoryPort aliasRepositoryPort;

    public ListWikiArticleAliasesUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleAliasRepositoryPort aliasRepositoryPort
    ) {
        this.articleRepositoryPort = articleRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
    }

    @Transactional(readOnly = true)
    public List<WikiArticleAliasDTO> execute(ListWikiArticleAliasesQuery query) {
        Objects.requireNonNull(query, "Query không được để trống.");
        UUID articleId = Objects.requireNonNull(query.articleId(), "Article ID không được để trống.");

        articleRepositoryPort.findById(articleId)
                .orElseThrow(() -> new WikiArticleNotFoundException(articleId));

        return aliasRepositoryPort.listByArticleId(articleId);
    }
}