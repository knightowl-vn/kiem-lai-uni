package com.universe.novel.infrastructure.wiki;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupItem;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupResult;
import com.universe.wiki.contracts.dto.WikiContextualLookupItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Adapter thực thi WikiContextualLookupPort bằng cách gọi sang WikiContextualLookupContract
 * và ánh xạ các DTO của Wiki sang model trung lập thuộc Novel module.
 */
@Component
public class WikiContextualLookupAdapter implements WikiContextualLookupPort {

    private final WikiContextualLookupContract wikiContextualLookupContract;

    public WikiContextualLookupAdapter(WikiContextualLookupContract wikiContextualLookupContract) {
        this.wikiContextualLookupContract = Objects.requireNonNull(
                wikiContextualLookupContract,
                "WikiContextualLookupContract không được để trống."
        );
    }

    @Override
    public WikiContextualLookupResult lookup(String query) {
        if (query == null || query.isBlank()) {
            return WikiContextualLookupResult.empty("");
        }

        WikiContextualLookupResultDTO resultDTO =
                wikiContextualLookupContract.lookupByTitle(query);

        if (resultDTO == null || resultDTO.items() == null) {
            return WikiContextualLookupResult.empty(query);
        }

        List<WikiContextualLookupItem> items = resultDTO.items().stream()
                .map(this::toLookupItem)
                .toList();

        return new WikiContextualLookupResult(
                resultDTO.query() != null ? resultDTO.query() : query,
                resultDTO.hasExactMatch(),
                items
        );
    }

    private WikiContextualLookupItem toLookupItem(WikiContextualLookupItemDTO dto) {
        return new WikiContextualLookupItem(
                dto.id(),
                dto.title(),
                dto.articleType(),
                dto.slug(),
                dto.summary(),
                dto.matchedAlias()
        );
    }
}
