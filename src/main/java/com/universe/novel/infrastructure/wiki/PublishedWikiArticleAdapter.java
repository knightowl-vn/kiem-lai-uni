package com.universe.novel.infrastructure.wiki;

import com.universe.novel.application.chapter.reference.PublishedWikiArticleSummary;
import com.universe.novel.application.ports.PublishedWikiArticlePort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.interfaces.WikiArticleContract;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter thực thi PublishedWikiArticlePort bằng cách gọi sang WikiArticleContract
 * và ánh xạ DTO từ Wiki sang model tóm lược của Novel.
 */
@Component
public class PublishedWikiArticleAdapter implements PublishedWikiArticlePort {

    private final WikiArticleContract wikiArticleContract;

    public PublishedWikiArticleAdapter(WikiArticleContract wikiArticleContract) {
        this.wikiArticleContract = Objects.requireNonNull(
                wikiArticleContract,
                "WikiArticleContract không được để trống."
        );
    }

    @Override
    public Optional<PublishedWikiArticleSummary> findPublishedById(UUID articleId) {
        if (articleId == null) {
            return Optional.empty();
        }
        return wikiArticleContract.findPublishedById(articleId).map(this::toSummary);
    }

    private PublishedWikiArticleSummary toSummary(PublishedWikiArticleDTO dto) {
        return new PublishedWikiArticleSummary(
                dto.id(),
                dto.title(),
                dto.slug(),
                dto.articleType(),
                dto.summary()
        );
    }
}
