package com.universe.novel.infrastructure.wiki;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import com.universe.novel.application.reader.ReaderWikiLookupItem;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import com.universe.wiki.contracts.dto.WikiContextualLookupItemDTO;
import com.universe.wiki.contracts.dto.WikiContextualLookupResultDTO;
import com.universe.wiki.contracts.interfaces.WikiContextualLookupContract;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Adapter thực thi WikiContextualLookupPort bằng cách gọi sang WikiContextualLookupContract
 * và ánh xạ các DTO của Wiki sang model thuộc Novel module.
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
    public ReaderWikiLookupResult lookup(String query) {
        if (query == null || query.isBlank()) {
            return new ReaderWikiLookupResult("", false, List.of());
        }

        WikiContextualLookupResultDTO resultDTO =
                wikiContextualLookupContract.lookupByTitle(query);

        if (resultDTO == null || resultDTO.items() == null) {
            return new ReaderWikiLookupResult(query, false, List.of());
        }

        List<ReaderWikiLookupItem> items = resultDTO.items().stream()
                .map(this::toReaderLookupItem)
                .toList();

        return new ReaderWikiLookupResult(
                resultDTO.query() != null ? resultDTO.query() : query,
                resultDTO.hasExactMatch(),
                items
        );
    }

    private ReaderWikiLookupItem toReaderLookupItem(WikiContextualLookupItemDTO dto) {
        return new ReaderWikiLookupItem(
                dto.id(),
                dto.title(),
                dto.articleType(),
                dto.slug(),
                dto.summary(),
                dto.matchedAlias()
        );
    }
}