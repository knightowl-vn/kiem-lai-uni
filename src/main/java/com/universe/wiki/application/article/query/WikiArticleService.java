package com.universe.wiki.application.article.query;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.interfaces.WikiArticleContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service thực thi WikiArticleContract để phục vụ các module bên ngoài.
 */
@Service
public class WikiArticleService implements WikiArticleContract {

    private final WikiArticleQueryPort wikiArticleQueryPort;

    public WikiArticleService(WikiArticleQueryPort wikiArticleQueryPort) {
        this.wikiArticleQueryPort = Objects.requireNonNull(
                wikiArticleQueryPort,
                "WikiArticleQueryPort không được để trống."
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedWikiArticleDTO> findPublishedById(UUID articleId) {
        if (articleId == null) {
            return Optional.empty();
        }
        return wikiArticleQueryPort.findPublishedById(articleId);
    }
}
