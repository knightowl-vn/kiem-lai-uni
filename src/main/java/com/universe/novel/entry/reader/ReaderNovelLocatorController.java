package com.universe.novel.entry.reader;

import com.universe.novel.application.locator.ChapterNumberQueryParser;
import com.universe.novel.application.locator.LocatePublishedChaptersUseCase;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorItemDTO;
import com.universe.novel.contracts.dto.locator.NovelChapterLocatorResultDTO;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Objects;
import java.util.Optional;

/**
 * Thin web controller for contextual Novel chapter locator.
 * Handles numeric chapter queries when navigating from Novel pages,
 * locating exact published chapters and redirecting to the novel catalogue
 * with volume open and chapter scrolled into view.
 */
@Controller
@RequestMapping("/novel")
public class ReaderNovelLocatorController {

    private final LocatePublishedChaptersUseCase locatePublishedChaptersUseCase;

    public ReaderNovelLocatorController(LocatePublishedChaptersUseCase locatePublishedChaptersUseCase) {
        this.locatePublishedChaptersUseCase = Objects.requireNonNull(
                locatePublishedChaptersUseCase,
                "LocatePublishedChaptersUseCase must not be null"
        );
    }

    @GetMapping("/locate")
    public String locate(@RequestParam(name = "q", required = false) String q) {
        if (q == null || q.isBlank()) {
            return "redirect:/novel";
        }

        String normalizedQuery = q.trim().replaceAll("\\s+", " ");
        Optional<Integer> parsedChapterNumber = ChapterNumberQueryParser.parseChapterNumber(normalizedQuery);

        if (parsedChapterNumber.isEmpty()) {
            String searchUrl = UriComponentsBuilder.fromPath("/search")
                    .queryParam("q", normalizedQuery)
                    .queryParam("scope", "novel")
                    .build()
                    .encode()
                    .toUriString();
            return "redirect:" + searchUrl;
        }

        int targetChapterNumber = parsedChapterNumber.get();
        NovelChapterLocatorResultDTO result = locatePublishedChaptersUseCase.locate(normalizedQuery, 1);

        if (result.items().isEmpty()) {
            String searchUrl = UriComponentsBuilder.fromPath("/search")
                    .queryParam("q", normalizedQuery)
                    .queryParam("scope", "novel")
                    .build()
                    .encode()
                    .toUriString();
            return "redirect:" + searchUrl;
        }

        NovelChapterLocatorItemDTO matchedItem = result.items().get(0);
        if (matchedItem.chapterNumber() != targetChapterNumber) {
            String searchUrl = UriComponentsBuilder.fromPath("/search")
                    .queryParam("q", normalizedQuery)
                    .queryParam("scope", "novel")
                    .build()
                    .encode()
                    .toUriString();
            return "redirect:" + searchUrl;
        }

        String targetUrl = UriComponentsBuilder.fromPath("/novel")
                .queryParam("openVolume", matchedItem.volumeSortOrder())
                .queryParam("locateChapter", matchedItem.chapterNumber())
                .fragment("chapter-" + matchedItem.chapterNumber())
                .build()
                .encode()
                .toUriString();

        return "redirect:" + targetUrl;
    }
}
