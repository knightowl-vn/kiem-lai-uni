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

        // Extracts plain text from DOM Range without reading innerHTML/markup for snippet content
        assertThat(js).contains("preRange.toString()");
        assertThat(js).contains("postRange.toString()");
        // The snippet functions must not use innerHTML to read range content
        assertThat(js).doesNotContain("range.innerHTML");
        assertThat(js).doesNotContain("preRange.innerHTML");
        assertThat(js).doesNotContain("postRange.innerHTML");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js uses no legacy HTTP APIs, no mutation requests, and does not leak global helpers to window")
    void adminScriptContainsNoLegacyHttpMutationsAndNoGlobalLeaksContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // No legacy or library-based HTTP (fetch is allowed for wiki search, Step 6D2A)
        assertThat(js).doesNotContain("XMLHttpRequest");
        assertThat(js).doesNotContain("sendBeacon");
        assertThat(js).doesNotContain("$.ajax");
        assertThat(js).doesNotContain("axios");

        // No POST mutations (selection and search are read-only)
        assertThat(js).doesNotContain("method: 'POST'");
        assertThat(js).doesNotContain("method: \"POST\"");

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

    // ----------------------------------------------------------------
    // Step 6D2A: Wiki Target Search UI contract tests
    // ----------------------------------------------------------------

    @Test
    @DisplayName("chapter-wiki-references.html defines wiki target search area and selected-target display elements")
    void templateDefinesWikiTargetSearchAreaAndSelectedTargetElementsContract() throws Exception {
        String page = read("src/main/resources/templates/admin/novel/chapter-wiki-references.html");

        // Search area wrapper
        assertThat(page).contains("class=\"novel-admin-wiki-target-search-area\"");

        // Search input
        assertThat(page).contains("id=\"novelAdminWikiTargetSearchInput\"");
        assertThat(page).contains("class=\"novel-admin-wiki-search-input\"");
        assertThat(page).contains("maxlength=\"100\"");
        assertThat(page).contains("autocomplete=\"off\"");

        // Status and results list
        assertThat(page).contains("id=\"novelAdminWikiTargetSearchStatus\"");
        assertThat(page).contains("id=\"novelAdminWikiTargetResultsList\"");
        assertThat(page).contains("role=\"listbox\"");

        // Selected target card
        assertThat(page).contains("id=\"novelAdminSelectedWikiTarget\"");
        assertThat(page).contains("id=\"novelAdminSelectedWikiTitle\"");
        assertThat(page).contains("id=\"novelAdminSelectedWikiType\"");
        assertThat(page).contains("id=\"novelAdminSelectedWikiAlias\"");
        assertThat(page).contains("id=\"novelAdminSelectedWikiSummary\"");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js cancels stale Wiki searches immediately on input, debounces valid queries, and guards async completions with request-local controller")
    void adminScriptImplementsDebouncedWikiTargetSearchContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // Input event handler: immediately cancels debounce timer and aborts in-flight request
        assertThat(js).contains("function handleWikiSearchInput()");
        int handleInputIndex = js.indexOf("function handleWikiSearchInput()");
        int clearTimeoutIndex = js.indexOf("clearTimeout(wikiSearchDebounceTimer)", handleInputIndex);
        int abortIndex = js.indexOf("wikiSearchAbortController.abort()", handleInputIndex);
        assertThat(handleInputIndex).isGreaterThan(-1);
        assertThat(clearTimeoutIndex).isGreaterThan(handleInputIndex);
        assertThat(abortIndex).isGreaterThan(handleInputIndex);

        // Immediately invalidates old displayed search results and status on any input change
        int clearResultsIndex = js.indexOf("clearWikiSearchResults()", handleInputIndex);
        int hideStatusIndex = js.indexOf("hideWikiSearchStatus()", handleInputIndex);
        int setDebounceIndex = js.indexOf("setTimeout(performWikiSearch, 250)", handleInputIndex);
        assertThat(clearResultsIndex).isGreaterThan(handleInputIndex).isLessThan(setDebounceIndex);
        assertThat(hideStatusIndex).isGreaterThan(handleInputIndex).isLessThan(setDebounceIndex);

        // Blank or >100-char query immediately returns without scheduling a request
        assertThat(js).contains("!trimmed || trimmed.length > 100");
        int blankGuardIndex = js.indexOf("!trimmed || trimmed.length > 100", handleInputIndex);
        assertThat(blankGuardIndex).isGreaterThan(handleInputIndex).isLessThan(setDebounceIndex);

        // Debounce ~250ms for valid queries
        assertThat(js).contains("setTimeout(performWikiSearch, 250)");

        // Request-local AbortController instantiated and signal passed in fetch
        assertThat(js).contains("function performWikiSearch()");
        int performSearchIndex = js.indexOf("function performWikiSearch()");
        assertThat(js.substring(performSearchIndex)).contains("const currentController = new AbortController()");
        assertThat(js.substring(performSearchIndex)).contains("fetch(url, { signal: currentController.signal })");

        // Request-local controller guard and query guard against stale async completion (success and failure)
        assertThat(js.substring(performSearchIndex)).contains("currentController.signal.aborted || wikiSearchAbortController !== currentController");
        assertThat(js.substring(performSearchIndex)).contains("currentQuery !== trimmed");
        assertThat(js.substring(performSearchIndex)).contains("err.name === 'AbortError'");

        // Correct Admin endpoint with encodeURIComponent
        assertThat(js.substring(performSearchIndex)).contains("/wiki-references/search-targets?q=");
        assertThat(js.substring(performSearchIndex)).contains("encodeURIComponent(currentChapterId)");
        assertThat(js.substring(performSearchIndex)).contains("encodeURIComponent(trimmed)");

        // Safe error handling for non-abort failures
        assertThat(js.substring(performSearchIndex)).contains("'Không thể tải kết quả tìm kiếm.'");
    }

    @Test
    @DisplayName("admin-chapter-wiki-references.js renders wiki results with metadata, stores selected target, and clears target only on captured selection identity change")
    void adminScriptRendersWikiResultsAndStoresSelectedTargetInDomStateContract() throws Exception {
        String js = read("src/main/resources/static/js/novel/admin-chapter-wiki-references.js");

        // Render function
        assertThat(js).contains("function renderWikiSearchResults(items)");
        assertThat(js).contains("'novel-admin-wiki-result-item'");
        assertThat(js).contains("item.title");
        assertThat(js).contains("item.articleType");
        assertThat(js).contains("item.matchedAlias");
        assertThat(js).contains("item.summary");

        // Accessible: role=option, tabindex, keyboard support
        assertThat(js).contains("role', 'option'");
        assertThat(js).contains("tabindex', '0'");
        assertThat(js).contains("'Enter'");

        // Select target: stores id, title, type in dataset
        assertThat(js).contains("function selectWikiTarget(item)");
        assertThat(js).contains("selectionPanel.dataset.wikiTargetId");
        assertThat(js).contains("selectionPanel.dataset.wikiTargetTitle");
        assertThat(js).contains("selectionPanel.dataset.wikiTargetType");

        // resetWikiTarget clears dataset entries, search input, results, and status
        assertThat(js).contains("function resetWikiTarget()");
        assertThat(js).contains("delete selectionPanel.dataset.wikiTargetId");
        assertThat(js).contains("delete selectionPanel.dataset.wikiTargetTitle");
        assertThat(js).contains("delete selectionPanel.dataset.wikiTargetType");

        // Selection identity helper distinguishes term, occurrence index / unknown occurrence, and context snippet
        assertThat(js).contains("function isSameCapturedSelection(displayTerm, occurrenceIndex, contextSnippet)");
        int isSameFuncIndex = js.indexOf("function isSameCapturedSelection");
        String isSameSub = js.substring(isSameFuncIndex, js.indexOf("function renderSelectionState"));
        assertThat(isSameSub).contains("prevTerm !== displayTerm");
        assertThat(isSameSub).contains("prevValid !== newValid");
        assertThat(isSameSub).contains("prevIndex !== newIndex");
        assertThat(isSameSub).contains("prevSnippet !== newSnippet");

        // renderSelectionState conditionally clears Wiki target only when captured selection identity actually changes
        assertThat(js).contains("function renderSelectionState(displayTerm, occurrenceIndex, contextSnippet)");
        int renderSelectionIndex = js.indexOf("function renderSelectionState");
        int sameSelectionCheckIndex = js.indexOf("isSameCapturedSelection(displayTerm, occurrenceIndex, contextSnippet)", renderSelectionIndex);
        int conditionalResetIndex = js.indexOf("if (!sameSelection)", renderSelectionIndex);
        int storeTermIndex = js.indexOf("selectionPanel.dataset.selectedTerm = displayTerm", renderSelectionIndex);

        assertThat(sameSelectionCheckIndex).isGreaterThan(renderSelectionIndex);
        assertThat(conditionalResetIndex).isGreaterThan(sameSelectionCheckIndex).isLessThan(storeTermIndex);

        // Reset selection panel also calls resetWikiTarget
        int resetPanelIndex = js.indexOf("function resetSelectionPanel()");
        int resetWikiInPanelIndex = js.indexOf("resetWikiTarget();", resetPanelIndex);
        assertThat(resetPanelIndex).isGreaterThan(-1);
        assertThat(resetWikiInPanelIndex).isGreaterThan(resetPanelIndex);
    }

    @Test
    @DisplayName("admin.css reserves fixed vertical space for idle/active selection states and bounds search results with vertical scroll")
    void adminCssReservesStableSelectionSpaceAndBoundsSearchResultsScrollContract() throws Exception {
        String css = read("src/main/resources/static/css/novel/admin.css");

        // Reserved fixed height on idle and active selection states to prevent layout shift of Chapter Preview
        assertThat(css).contains(".novel-admin-selection-idle");
        assertThat(css).contains(".novel-admin-selection-active");
        int idleIndex = css.indexOf(".novel-admin-selection-idle");
        int activeIndex = css.indexOf(".novel-admin-selection-active");
        String idleBlock = css.substring(idleIndex, activeIndex);
        String activeBlock = css.substring(activeIndex, css.indexOf(".novel-admin-selection-meta-row", activeIndex));

        assertThat(idleBlock).contains("height: 320px;");
        assertThat(idleBlock).contains("display: flex");
        assertThat(idleBlock).contains("align-items: center");
        assertThat(idleBlock).contains("justify-content: center");

        assertThat(activeBlock).contains("height: 320px;");
        assertThat(activeBlock).contains("display: grid");
        assertThat(activeBlock).contains("overflow-y: auto");

        // Bounded max-height with vertical scrolling for Wiki search results list
        assertThat(css).contains(".novel-admin-wiki-target-results-list");
        int resultsListIndex = css.indexOf(".novel-admin-wiki-target-results-list");
        String resultsListBlock = css.substring(resultsListIndex, css.indexOf(".novel-admin-wiki-result-item", resultsListIndex));
        assertThat(resultsListBlock).contains("max-height:");
        assertThat(resultsListBlock).contains("overflow-y: auto");

     // Responsive Wiki selection-panel rules on small viewports
        int wikiMediaIndex = css.indexOf(
                "@media (max-width: 640px)",
                resultsListIndex
        );

        assertThat(wikiMediaIndex).isGreaterThan(resultsListIndex);

        String wikiMediaBlock = css.substring(wikiMediaIndex);

        assertThat(wikiMediaBlock).contains(".novel-admin-selection-idle");
        assertThat(wikiMediaBlock).contains(".novel-admin-selection-active");
        assertThat(wikiMediaBlock).contains("height: 220px;");
        assertThat(wikiMediaBlock).contains(".novel-admin-wiki-target-results-list");
        assertThat(wikiMediaBlock).contains("max-height: 160px;");
        }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
