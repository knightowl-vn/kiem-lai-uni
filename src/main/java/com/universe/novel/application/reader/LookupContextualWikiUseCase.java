package com.universe.novel.application.reader;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupItem;
import com.universe.novel.application.wiki.lookup.WikiContextualLookupResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class LookupContextualWikiUseCase {

    private static final int MAX_QUERY_LENGTH = 100;

    private final WikiContextualLookupPort wikiContextualLookupPort;

    public LookupContextualWikiUseCase(WikiContextualLookupPort wikiContextualLookupPort) {
        this.wikiContextualLookupPort = Objects.requireNonNull(
                wikiContextualLookupPort,
                "WikiContextualLookupPort không được để trống."
        );
    }

    public ReaderWikiLookupResult execute(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return new ReaderWikiLookupResult("", false, List.of());
        }

        String normalizedQuery = rawQuery.trim();
        if (normalizedQuery.length() > MAX_QUERY_LENGTH) {
            return new ReaderWikiLookupResult(normalizedQuery, false, List.of());
        }

        WikiContextualLookupResult result = wikiContextualLookupPort.lookup(normalizedQuery);
        if (result == null || result.items() == null) {
            return new ReaderWikiLookupResult(normalizedQuery, false, List.of());
        }

        List<ReaderWikiLookupItem> readerItems = result.items().stream()
                .map(this::toReaderLookupItem)
                .toList();

        return new ReaderWikiLookupResult(
                result.query() != null ? result.query() : normalizedQuery,
                result.hasExactMatch(),
                readerItems
        );
    }

    private ReaderWikiLookupItem toReaderLookupItem(WikiContextualLookupItem item) {
        return new ReaderWikiLookupItem(
                item.id(),
                item.title(),
                item.articleType(),
                item.slug(),
                item.summary(),
                item.matchedAlias()
        );
    }
}
