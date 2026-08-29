package com.universe.novel.entry.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reader Wiki Lookup Template and JavaScript Contract Tests")
class ReaderWikiLookupTemplateContractTest {

    @Test
    @DisplayName("Novel chapter reading page (chapter.html) links reader-wiki-lookup.css, includes reader-wiki-lookup.js, and exposes data-chapter-id")
    void chapterReadingPageIncludesWikiLookupAssetsContract() throws Exception {
        String chapterPage = read("src/main/resources/templates/novel/chapter.html");

        assertThat(chapterPage).contains("th:href=\"@{/css/novel/reader-wiki-lookup.css}\"");
        assertThat(chapterPage).contains("th:src=\"@{/js/novel/reader-wiki-lookup.js}\"");
        assertThat(chapterPage).contains("defer");

        // Chapter ID exposure on reader body
        assertThat(chapterPage).contains("class=\"novel-reader-chapter-body\"");
        assertThat(chapterPage).contains("th:attr=\"data-chapter-id=${chapter.id}\"");
    }

    @Test
    @DisplayName("reader-wiki-lookup.js defines expected DOM structure, occurrence calculation, presentation states, and routing")
    void wikiLookupJsScriptContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/reader-wiki-lookup.js");

        // 1. Selection & Chapter ID Extraction
        assertThat(js).contains(".novel-reader-chapter-body");
        assertThat(js).contains("dataset.chapterId");

        // 2. Rendered DOM Occurrence Calculation Algorithm
        assertThat(js).contains("calculateOccurrenceIndex");
        assertThat(js).contains("normalizeDisplayTerm");
        assertThat(js).contains("normalizeSearchKey");
        assertThat(js).contains("countOccurrences");
        assertThat(js).contains(".normalize('NFC')");
        assertThat(js).contains("replace(/\\s+/g, ' ')");
        assertThat(js).contains("document.createRange()");
        assertThat(js).contains("preRange.selectNodeContents(chapterBody)");
        assertThat(js).contains("preRange.setEnd(range.startContainer, range.startOffset)");
        assertThat(js).contains("priorOccurrences + 1");

        // 3. Trigger & Request Construction
        assertThat(js).contains("novelWikiLookupActionBtn");
        assertThat(js).contains("Tra Wiki");
        assertThat(js).contains("actionBtn.addEventListener('click', onActionButtonClick)");
        assertThat(js).contains("/novel/api/wiki/lookup?q=");
        assertThat(js).contains("&chapterId=");
        assertThat(js).contains("&occurrence=");
        assertThat(js).contains("AbortController");
        assertThat(js).contains("displayTerm.length > 100");

        // 4. Accessibility & Container
        assertThat(js).contains("novelWikiLookupResultContainer");
        assertThat(js).contains("novelWikiLookupBackdrop");
        assertThat(js).contains("novelWikiLookupCloseBtn");
        assertThat(js).contains("role");
        assertThat(js).contains("dialog");

        // 5. States & Presentation
        assertThat(js).contains("novel-wiki-lookup-loading");
        assertThat(js).contains("novel-wiki-lookup-empty");
        assertThat(js).contains("novel-wiki-lookup-error");
        assertThat(js).contains("novel-wiki-lookup-primary-card");
        assertThat(js).contains("novel-wiki-lookup-secondary-section");
        assertThat(js).contains("novel-wiki-lookup-alias-badge");
        assertThat(js).contains("novel-wiki-lookup-secondary-alias");
        assertThat(js).contains("Khớp danh xưng:");

        // 6. Public Wiki Route Mapping
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

        // 7. No Global Variable Leakage
        assertThat(js).doesNotContain("window.NovelWikiLookup");
    }

    @Test
    @DisplayName("reader-wiki-lookup.css defines responsive popover/bottom-sheet layout and presentation cards")
    void wikiLookupCssStylesContract() throws Exception {
        String css = read("src/main/resources/static/css/novel/reader-wiki-lookup.css");

        // 1. Trigger & Container
        assertThat(css).contains(".novel-wiki-lookup-action-btn");
        assertThat(css).contains(".novel-wiki-lookup-container");
        assertThat(css).contains(".novel-wiki-lookup-backdrop");
        assertThat(css).contains("z-index: 1060");

        // 2. Responsive Breakpoints
        assertThat(css).contains("@media (min-width: 641px)");
        assertThat(css).contains("@media (max-width: 640px)");

        // 3. Presentation Cards & Mobile Fixed Action
        assertThat(css).contains(".novel-wiki-lookup-primary-card");
        assertThat(css).contains(".novel-wiki-lookup-secondary-section");
        assertThat(css).contains(".novel-wiki-lookup-type-badge");
        assertThat(css).contains(".novel-wiki-lookup-alias-badge");
        assertThat(css).contains(".novel-wiki-lookup-secondary-alias");
        assertThat(css).contains(".novel-wiki-lookup-article-link");
        assertThat(css).contains("env(safe-area-inset-bottom");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}