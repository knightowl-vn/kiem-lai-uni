package com.universe.novel.infrastructure.locator;

import com.universe.novel.application.locator.LocatePublishedChaptersUseCase;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import com.universe.novel.contracts.interfaces.NovelChapterLocatorContract;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Public Contract adapter implementing NovelChapterLocatorContract for cross-module locator requests.
 */
@Component
public class NovelChapterLocatorAdapter implements NovelChapterLocatorContract {

    private final LocatePublishedChaptersUseCase locatePublishedChaptersUseCase;

    public NovelChapterLocatorAdapter(LocatePublishedChaptersUseCase locatePublishedChaptersUseCase) {
        this.locatePublishedChaptersUseCase = Objects.requireNonNull(
                locatePublishedChaptersUseCase,
                "LocatePublishedChaptersUseCase must not be null"
        );
    }

    @Override
    public NovelChapterLocatorResultDTO locateChapters(String query, int limit) {
        return locatePublishedChaptersUseCase.locate(query, limit);
    }
}
