package com.universe.novel.entry.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Admin Novel Chapter Wiki Reference Template and JavaScript Contract Tests")
class AdminNovelChapterWikiReferenceTemplateContractTest {

    @Test
    @DisplayName("Admin Chapter Wiki References page links stylesheets, loads script with defer, and defines selection panel and preview")
    void managementPageLoadsAdminScriptAndDefinesSelectionPanelContract() throws Exception {
        String page = read("src/main/resources/templates/admin/novel/chapter-wiki-references.html");

        // Stylesheets
        assertThat(page).contains("th:href=\"@{/css/admin/dashboard.css}\"");
        assertThat(page).contains("th:href=\"@{/css/novel/admin.css}\"");

        // Script loading with defer
        assertThat(page).contains("th:src=\"@{/js/novel/admin-chapter-wiki-references.js}\"");
        assertThat(page).contains("defer");

        // Selection Panel & Elements
        assertThat(page).contains("id=\"novelAdminWikiSelectionPanel\"");
        assertThat(page).contains("class=\"novel-admin-wiki-selection-panel\"");
        assertThat(page).contains("id=\"novelAdminSelectionEmptyState\"");
        assertThat(page).contains("id=\"novelAdminSelectionActiveState\"");
        assertThat(page).contains("id=\"novelAdminClearSelectionBtn\"");
        assertThat(page).contains("id=\"novelAdminSelectedTermText\"");
        assertThat(page).contains("id=\"novelAdminSelectedOccurrenceBadge\"");
        assertThat(page).contains("id=\"novelAdminSelectedSnippet\"");
        assertThat(page).contains("id=\"novelAdminSelectedChapterId\"");

        // Content Preview Section
        assertThat(page).contains("class=\"novel-reader-chapter-body\"");
        assertThat(page).contains("th:attr=\"data-chapter-id=${chapter.id}\"");
        assertThat(page).contains("th:utext=\"${contentHtml}\"");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js is loaded exclusively on chapter-wiki-references.html and not on other pages")
    void adminScriptIsNotLoadedOnOtherAdminOrReaderPagesContract() throws Exception {
        String scriptRef = "admin-chapter-wiki-references.js";

        // Admin pages that must NOT load this script
        assertThat(read("src/main/resources/templates/admin/novel/chapter-detail.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/admin/novel/chapter-create.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/admin/novel/chapter-edit.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/admin/novel/chapter-revisions.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/admin/novel/chapter-revision-detail.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/admin/novel/chapters.html")).doesNotContain(scriptRef);

        // Reader pages that must NOT load this script
        assertThat(read("src/main/resources/templates/novel/chapter.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/novel/index.html")).doesNotContain(scriptRef);
        assertThat(read("src/main/resources/templates/novel/chapter-list.html")).doesNotContain(scriptRef);
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js restricts selection strictly to rendered chapter preview")
    void adminScriptRestrictsSelectionToRenderedChapterPreviewContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        assertThat(js).contains(".novel-reader-chapter-body");
        assertThat(js).contains("chapterBodyEl.contains(anchorNode)");
        assertThat(js).contains("chapterBodyEl.contains(focusNode)");
        assertThat(js).contains("selection.isCollapsed");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js enforces Unicode NFC, whitespace normalization, and 1..100 length constraint")
    void adminScriptNormalizesTermAndSearchKeyContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        assertThat(js).contains("function normalizeDisplayTerm(term)");
        assertThat(js).contains(".normalize('NFC')");
        assertThat(js).contains(".trim()");
        assertThat(js).contains(".replace(/\\s+/g, ' ')");

        assertThat(js).contains("function normalizeSearchKey(term)");
        assertThat(js).contains(".toLowerCase()");

        assertThat(js).contains("MAX_TERM_LENGTH = 100");
        assertThat(js).contains("displayTerm.length < 1 || displayTerm.length > MAX_TERM_LENGTH");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js occurrence calculation matches Reader semantics exactly (Range, non-overlapping, 1-based)")
    void adminScriptOccurrenceCalculationMatchesReaderExactContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // Uses DOM Range, not raw markdown
        assertThat(js).contains("function calculateOccurrenceIndex(chapterBody, range, displayTerm)");
        assertThat(js).contains("document.createRange()");
        assertThat(js).contains("preRange.selectNodeContents(chapterBody)");
        assertThat(js).contains("preRange.setEnd(range.startContainer, range.startOffset)");

        // Normalized pre-range text
        assertThat(js).contains("preRange.toString()");
        assertThat(js).contains(".normalize('NFC').replace(/\\s+/g, ' ').toLowerCase()");

        // Non-overlapping substring search
        assertThat(js).contains("function countOccurrences(haystack, needle)");
        assertThat(js).contains("pos += needle.length");

        // 1-based index calculation
        assertThat(js).contains("priorOccurrences + 1");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js generates bounded plain-text context snippet (<= 255 chars, no markup)")
    void adminScriptExtractsBoundedPlainTextContextSnippetContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        assertThat(js).contains("function extractContextSnippet(chapterBody, range, displayTerm)");
        assertThat(js).contains("MAX_SNIPPET_LENGTH = 255");
        assertThat(js).contains("postRange.setStart(range.endContainer, range.endOffset)");
        assertThat(js).contains("slice(0, MAX_SNIPPET_LENGTH)");

        // Extracts plain text from DOM Range without innerHTML/markup
        assertThat(js).contains("preRange.toString()");
        assertThat(js).contains("postRange.toString()");
        assertThat(js).doesNotContain("innerHTML");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js sends no HTTP requests and does not leak global helpers to window")
    void adminScriptContainsNoHttpRequestsAndNoGlobalLeaksContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // Pure client-side: no HTTP calls on selection
        assertThat(js).doesNotContain("fetch(");
        assertThat(js).doesNotContain("XMLHttpRequest");
        assertThat(js).doesNotContain("sendBeacon");
        assertThat(js).doesNotContain("$.ajax");
        assertThat(js).doesNotContain("axios");

        // Encapsulation: IIFE and strict mode
        assertThat(js).startsWith("/**");
        assertThat(js).contains("(function () {");
        assertThat(js).contains("'use strict';");
        assertThat(js).contains("})();");

        // No global leakage on window
        assertThat(js).doesNotContain("window.Admin");
        assertThat(js).doesNotContain("window.admin");
        assertThat(js).doesNotContain("window.calculateOccurrence");
        assertThat(js).doesNotContain("window.NovelWikiLookup");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js safely handles undetermined occurrence without inventing invalid values")
    void adminScriptSafelyHandlesInvalidOrUndeterminedOccurrenceContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // Safe fallback in calculateOccurrenceIndex
        assertThat(js).contains("return null;");
        assertThat(js).contains("catch (e)");

        // Safe UI representation in renderSelectionState
        assertThat(js).contains("occurrenceIndex !== null && occurrenceIndex >= 1");
        assertThat(js).contains("`Vị trí #${occurrenceIndex}`");
        assertThat(js).contains("'Không xác định được vị trí'");
        assertThat(js).contains("occurrenceValid = 'false'");
        assertThat(js).contains("occurrenceValid = 'true'");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js preserves collapsed selection but resets stale panel state on invalid non-collapsed selections")
    void adminScriptPreservesCollapsedSelectionAndResetsOnInvalidNonCollapsedSelectionContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // 1. Preserve collapsed selection (when clicking future controls)
        assertThat(js).contains("if (!selection || selection.isCollapsed)");
        assertThat(js).contains("return;");

        // 2. Reset on invalid non-collapsed selection: outside container
        assertThat(js).contains("if (!anchorNode || !focusNode || !chapterBodyEl.contains(anchorNode) || !chapterBodyEl.contains(focusNode))");

        // 3. Reset on invalid non-collapsed selection: length constraints
        assertThat(js).contains("if (displayTerm.length < 1 || displayTerm.length > MAX_TERM_LENGTH)");

        // 4. Reset on invalid non-collapsed selection: range count and zero-size rect
        assertThat(js).contains("if (selection.rangeCount === 0)");
        assertThat(js).contains("if (rect.width === 0 && rect.height === 0)");

        // 5. Explicit reset calls in evaluateSelection
        assertThat(js).contains("resetSelectionPanel();");
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
