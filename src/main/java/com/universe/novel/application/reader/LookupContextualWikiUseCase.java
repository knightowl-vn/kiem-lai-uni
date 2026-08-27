package com.universe.novel.application.reader;

import com.universe.novel.application.ports.WikiContextualLookupPort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
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

        return wikiContextualLookupPort.lookup(normalizedQuery);
    }
}