package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderWikiLookupTemplateContractTest {

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) links reader-wiki-lookup.css and includes reader-wiki-lookup.js")
    void chapterReadingPageIncludesWikiLookupAssetsContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("th:href=\"@{/css/novel/reader-wiki-lookup.css}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-wiki-lookup.js}\"");
        assertThat(chapterPage).contains("defer");
    }

    @Test
    @DisplayName("reader-wiki-lookup.js defines expected DOM structure, presentation states, dialog semantics, and public Wiki routing")
    void wikiLookupJsScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/reader-wiki-lookup.js");

        // 1. Selection & Trigger
        assertThat(js).contains(".novel-reader-chapter-body");
        assertThat(js).contains("novelWikiLookupActionBtn");
        assertThat(js).contains("Tra Wiki");
        assertThat(js).contains("/novel/api/wiki/lookup?q=");
        assertThat(js).contains("AbortController");
        assertThat(js).contains("normalizedText.length > 100");

        // 2. Accessibility & Container
        assertThat(js).contains("novelWikiLookupResultContainer");
        assertThat(js).contains("novelWikiLookupBackdrop");
        assertThat(js).contains("novelWikiLookupCloseBtn");
        assertThat(js).contains("role");
        assertThat(js).contains("dialog");

        // 3. States & Presentation
        assertThat(js).contains("novel-wiki-lookup-loading");
        assertThat(js).contains("novel-wiki-lookup-empty");
        assertThat(js).contains("novel-wiki-lookup-error");
        assertThat(js).contains("novel-wiki-lookup-primary-card");
        assertThat(js).contains("novel-wiki-lookup-secondary-section");

        // 4. Public Wiki Route Mapping (/wiki/{type}/{slug}) matching ArticleTypePathMapper
        assertThat(js).contains("CHARACTER: 'character'");
        assertThat(js).contains("REALM: 'realm'");
        assertThat(js).contains("CULTIVATION_PATH: 'cultivation-path'");
        assertThat(js).contains("FACTION: 'faction'");
        assertThat(js).contains("ITEM: 'item'");
        assertThat(js).contains("TECHNIQUE: 'technique'");
        assertThat(js).contains("LOCATION: 'location'");
        assertThat(js).contains("WORLD: 'world'");
        assertThat(js).contains("TIMELINE_EVENT: 'timeline-event'");
        assertThat(js).contains("/wiki/");
    }

    @Test
    @DisplayName("reader-wiki-lookup.css defines responsive popover/bottom-sheet layout and presentation cards")
    void wikiLookupCssStylesContract() throws Exception {
        String css = read("src/main/resources/static/css/novel/reader-wiki-lookup.css");

        // 1. Trigger & Container
        assertThat(css).contains(".novel-wiki-lookup-action-btn");
        assertThat(css).contains(".novel-wiki-lookup-container");
        assertThat(css).contains(".novel-wiki-lookup-backdrop");
        assertThat(css).contains("z-index: 960");

        // 2. Responsive Breakpoints
        assertThat(css).contains("@media (min-width: 641px)");
        assertThat(css).contains("@media (max-width: 640px)");

        // 3. Presentation Cards & Mobile Fixed Action
        assertThat(css).contains(".novel-wiki-lookup-primary-card");
        assertThat(css).contains(".novel-wiki-lookup-secondary-section");
        assertThat(css).contains(".novel-wiki-lookup-type-badge");
        assertThat(css).contains(".novel-wiki-lookup-article-link");
        assertThat(css).contains("env(safe-area-inset-bottom");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}