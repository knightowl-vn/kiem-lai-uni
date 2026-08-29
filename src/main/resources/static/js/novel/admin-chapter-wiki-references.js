/**
 * Kiem Lai Novel Admin — Chapter Wiki References Selection, Occurrence Detection & Target Search Script (MS-02.8.1 Steps 6C / 6D2A)
 *
 * Requirements & Behavior:
 * 1. Restricts text selection exclusively to the rendered chapter preview (.novel-reader-chapter-body).
 * 2. Normalizes selected text (Unicode NFC, trim, collapse consecutive whitespaces, 1..100 characters).
 * 3. Calculates 1-based occurrence index strictly using rendered DOM Range (MUST match Reader semantics in reader-wiki-lookup.js).
 * 4. Extracts a bounded plain-text context snippet (<= 255 chars, VARCHAR(255) DB limit, no HTML/markup).
 * 5. Updates the Admin selection panel UI state (selected term, occurrence #n / cannot determine fallback, snippet, chapter ID, clear button).
 * 6. On valid captured selection, enables Wiki target search via Admin endpoint (Step 6D2A).
 *    - Debounced typing (~250ms), AbortController per request, max 100-char query guard.
 *    - Renders returned PUBLISHED Wiki articles; clicking selects a target stored in local DOM state.
 *    - Resetting/replacing the captured selection clears the previously selected Wiki target.
 * 7. Encapsulated in IIFE; does NOT expose any helpers to the global window object.
 * 8. Safe failure handling: If Range computation fails, safely renders "Không xác định được vị trí" and sets occurrence to null.
 * 9. Keeps chapterId and term populated so Chapter-Wide binding remains possible even if occurrence is undetermined.
 */
(function () {
    'use strict';

    const MAX_TERM_LENGTH = 100;
    const MAX_SNIPPET_LENGTH = 255;

    let selectionPanel = null;
    let emptyStateEl = null;
    let activeStateEl = null;
    let clearBtnEl = null;
    let selectedTermEl = null;
    let occurrenceBadgeEl = null;
    let snippetEl = null;
    let chapterIdEl = null;
    let chapterBodyEl = null;

    // Wiki target search elements (Step 6D2A)
    let wikiSearchInputEl = null;
    let wikiSearchStatusEl = null;
    let wikiResultsListEl = null;
    let selectedWikiTargetEl = null;
    let selectedWikiTitleEl = null;
    let selectedWikiTypeEl = null;
    let selectedWikiAliasEl = null;
    let selectedWikiSummaryEl = null;

    let currentChapterId = null;
    let selectionTimeout = null;

    // Wiki search state (Step 6D2A)
    let wikiSearchDebounceTimer = null;
    let wikiSearchAbortController = null;

    document.addEventListener('DOMContentLoaded', function () {
        initChapterWikiReferenceSelection();
    });

    function initChapterWikiReferenceSelection() {
        chapterBodyEl = document.querySelector('.novel-reader-chapter-body');
        selectionPanel = document.getElementById('novelAdminWikiSelectionPanel');

        if (!chapterBodyEl || !selectionPanel) {
            return;
        }

        // Cache DOM elements
        emptyStateEl = document.getElementById('novelAdminSelectionEmptyState');
        activeStateEl = document.getElementById('novelAdminSelectionActiveState');
        clearBtnEl = document.getElementById('novelAdminClearSelectionBtn');
        selectedTermEl = document.getElementById('novelAdminSelectedTermText');
        occurrenceBadgeEl = document.getElementById('novelAdminSelectedOccurrenceBadge');
        snippetEl = document.getElementById('novelAdminSelectedSnippet');
        chapterIdEl = document.getElementById('novelAdminSelectedChapterId');

        // Wiki target search elements (Step 6D2A)
        wikiSearchInputEl = document.getElementById('novelAdminWikiTargetSearchInput');
        wikiSearchStatusEl = document.getElementById('novelAdminWikiTargetSearchStatus');
        wikiResultsListEl = document.getElementById('novelAdminWikiTargetResultsList');
        selectedWikiTargetEl = document.getElementById('novelAdminSelectedWikiTarget');
        selectedWikiTitleEl = document.getElementById('novelAdminSelectedWikiTitle');
        selectedWikiTypeEl = document.getElementById('novelAdminSelectedWikiType');
        selectedWikiAliasEl = document.getElementById('novelAdminSelectedWikiAlias');
        selectedWikiSummaryEl = document.getElementById('novelAdminSelectedWikiSummary');

        currentChapterId = chapterBodyEl.dataset.chapterId || chapterBodyEl.getAttribute('data-chapter-id') || null;

        // Selection listeners
        document.addEventListener('selectionchange', handleSelectionChange);
        document.addEventListener('mouseup', handlePointerEnd);
        document.addEventListener('touchend', handlePointerEnd);

        // Clear button listener
        if (clearBtnEl) {
            clearBtnEl.addEventListener('click', handleClearSelection);
        }

        // Wiki target search input listener (Step 6D2A)
        if (wikiSearchInputEl) {
            wikiSearchInputEl.addEventListener('input', handleWikiSearchInput);
        }
    }

    function handleSelectionChange() {
        if (selectionTimeout) {
            clearTimeout(selectionTimeout);
        }
        selectionTimeout = setTimeout(evaluateSelection, 80);
    }

    function handlePointerEnd() {
        setTimeout(evaluateSelection, 50);
    }

    function evaluateSelection() {
        const selection = window.getSelection();
        if (!selection || selection.isCollapsed) {
            // Preserve the current captured selection when selection is collapsed.
            // (Clicking future controls or inputs will collapse the browser selection).
            return;
        }

        if (!chapterBodyEl) {
            resetSelectionPanel();
            return;
        }

        // Validate selection boundaries: MUST be strictly inside chapterBody
        const anchorNode = selection.anchorNode;
        const focusNode = selection.focusNode;
        if (!anchorNode || !focusNode || !chapterBodyEl.contains(anchorNode) || !chapterBodyEl.contains(focusNode)) {
            // New non-collapsed selection is outside chapter preview — reset stale panel state
            resetSelectionPanel();
            return;
        }

        const rawText = selection.toString();
        const displayTerm = normalizeDisplayTerm(rawText);

        // Term length validation: 1..100 characters
        if (displayTerm.length < 1 || displayTerm.length > MAX_TERM_LENGTH) {
            // New non-collapsed selection has invalid length — reset stale panel state
            resetSelectionPanel();
            return;
        }

        if (selection.rangeCount === 0) {
            resetSelectionPanel();
            return;
        }

        const range = selection.getRangeAt(0);
        const rect = range.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) {
            resetSelectionPanel();
            return;
        }

        // Calculate occurrence index using locked Reader algorithm
        const occurrenceIndex = calculateOccurrenceIndex(chapterBodyEl, range, displayTerm);

        // Extract bounded context snippet
        const contextSnippet = extractContextSnippet(chapterBodyEl, range, displayTerm);

        // Render selection UI state
        renderSelectionState(displayTerm, occurrenceIndex, contextSnippet);
    }

    /**
     * Term normalization (Unicode NFC, trim, collapse whitespaces).
     * Exact parity with Reader normalizeDisplayTerm and Domain ChapterWikiReferenceTermNormalizer.
     */
    function normalizeDisplayTerm(term) {
        if (!term) return '';
        return term.normalize('NFC').trim().replace(/\s+/g, ' ');
    }

    /**
     * Search key normalization for substring matching (display term lowercase).
     * Exact parity with Reader normalizeSearchKey.
     */
    function normalizeSearchKey(term) {
        if (!term) return '';
        return normalizeDisplayTerm(term).toLowerCase();
    }

    /**
     * Non-overlapping substring counting.
     * Exact parity with Reader countOccurrences.
     */
    function countOccurrences(haystack, needle) {
        if (!haystack || !needle) return 0;
        let count = 0;
        let pos = 0;
        while ((pos = haystack.indexOf(needle, pos)) !== -1) {
            count++;
            pos += needle.length;
        }
        return count;
    }

    /**
     * Calculates 1-based occurrence index within rendered DOM Range.
     * Exact parity with Reader calculateOccurrenceIndex in reader-wiki-lookup.js.
     */
    function calculateOccurrenceIndex(chapterBody, range, displayTerm) {
        try {
            if (!chapterBody || !range || !displayTerm) {
                return null;
            }
            const searchKey = normalizeSearchKey(displayTerm);
            if (!searchKey) {
                return null;
            }

            const preRange = document.createRange();
            preRange.selectNodeContents(chapterBody);
            preRange.setEnd(range.startContainer, range.startOffset);

            const preText = preRange.toString();
            const normalizedPreText = preText.normalize('NFC').replace(/\s+/g, ' ').toLowerCase();

            const priorOccurrences = countOccurrences(normalizedPreText, searchKey);
            return priorOccurrences + 1;
        } catch (e) {
            return null;
        }
    }

    /**
     * Extracts bounded plain-text context snippet surrounding the selected range.
     * Max length guaranteed <= MAX_SNIPPET_LENGTH (255 chars, VARCHAR(255) DB limit).
     * Does not contain markup/HTML.
     */
    function extractContextSnippet(chapterBody, range, displayTerm) {
        try {
            if (!chapterBody || !range) {
                return displayTerm ? displayTerm.slice(0, MAX_SNIPPET_LENGTH) : '';
            }

            const term = displayTerm || normalizeDisplayTerm(range.toString());

            // Pre-range text
            const preRange = document.createRange();
            preRange.selectNodeContents(chapterBody);
            preRange.setEnd(range.startContainer, range.startOffset);
            const rawPreText = preRange.toString().normalize('NFC').replace(/\s+/g, ' ');

            // Post-range text
            const postRange = document.createRange();
            postRange.selectNodeContents(chapterBody);
            postRange.setStart(range.endContainer, range.endOffset);
            const rawPostText = postRange.toString().normalize('NFC').replace(/\s+/g, ' ');

            const maxSurrounding = Math.max(0, MAX_SNIPPET_LENGTH - term.length);
            const halfBudget = Math.floor(maxSurrounding / 2);

            const preSnippet = rawPreText.trimEnd();
            const postSnippet = rawPostText.trimStart();

            let preBudget = halfBudget;
            let postBudget = maxSurrounding - preBudget;

            if (preSnippet.length < preBudget) {
                postBudget += (preBudget - preSnippet.length);
            } else if (postSnippet.length < postBudget) {
                preBudget += (postBudget - postSnippet.length);
            }

            let prePart = '';
            if (preSnippet.length > preBudget) {
                prePart = (preBudget > 3) ? '...' + preSnippet.slice(-(preBudget - 3)) : preSnippet.slice(-preBudget);
            } else {
                prePart = preSnippet;
            }

            let postPart = '';
            if (postSnippet.length > postBudget) {
                postPart = (postBudget > 3) ? postSnippet.slice(0, postBudget - 3) + '...' : postSnippet.slice(0, postBudget);
            } else {
                postPart = postSnippet;
            }

            let combined = (prePart ? prePart + ' ' : '') + term + (postPart ? ' ' + postPart : '');
            combined = combined.normalize('NFC').trim().replace(/\s+/g, ' ');

            if (combined.length > MAX_SNIPPET_LENGTH) {
                combined = combined.slice(0, MAX_SNIPPET_LENGTH);
            }

            return combined;
        } catch (e) {
            return displayTerm ? displayTerm.slice(0, MAX_SNIPPET_LENGTH) : '';
        }
    }

    /**
     * Checks if the newly evaluated selection has the identical identity as the currently captured selection.
     * Identity distinguishes:
     * - normalized selected term
     * - occurrence index / unknown occurrence state
     * - context snippet (used when occurrence is unknown to distinguish different occurrences)
     */
    function isSameCapturedSelection(displayTerm, occurrenceIndex, contextSnippet) {
        if (!selectionPanel) {
            return false;
        }

        const prevTerm = selectionPanel.dataset.selectedTerm;
        if (!prevTerm || prevTerm !== displayTerm) {
            return false;
        }

        const prevValid = selectionPanel.dataset.occurrenceValid;
        const newValid = (occurrenceIndex !== null && occurrenceIndex >= 1) ? 'true' : 'false';
        if (prevValid !== newValid) {
            return false;
        }

        if (newValid === 'true') {
            const prevIndex = selectionPanel.dataset.occurrenceIndex;
            const newIndex = String(occurrenceIndex);
            if (prevIndex !== newIndex) {
                return false;
            }
        } else {
            // Both are unknown occurrence state — compare context snippets to distinguish different locations
            const prevSnippet = selectionPanel.dataset.contextSnippet || '';
            const newSnippet = contextSnippet || displayTerm || '';
            if (prevSnippet !== newSnippet) {
                return false;
            }
        }

        return true;
    }

    function renderSelectionState(displayTerm, occurrenceIndex, contextSnippet) {
        if (!selectionPanel) {
            return;
        }

        // Clear Wiki target and search state only when the captured selection identity actually changes
        const sameSelection = isSameCapturedSelection(displayTerm, occurrenceIndex, contextSnippet);
        if (!sameSelection) {
            resetWikiTarget();
        }

        // Show active state, hide empty state, show clear button
        if (emptyStateEl) emptyStateEl.style.display = 'none';
        if (activeStateEl) activeStateEl.style.display = 'grid';
        if (clearBtnEl) clearBtnEl.style.display = 'inline-flex';

        // Term
        if (selectedTermEl) {
            selectedTermEl.textContent = displayTerm;
        }

        // Occurrence Badge & Valid State
        if (occurrenceBadgeEl) {
            if (occurrenceIndex !== null && occurrenceIndex >= 1) {
                occurrenceBadgeEl.textContent = `Vị trí #${occurrenceIndex}`;
                occurrenceBadgeEl.className = 'novel-admin-badge novel-admin-occurrence-badge is-valid';
                occurrenceBadgeEl.style.background = '#f3e5f5';
                occurrenceBadgeEl.style.color = '#7b1fa2';
                selectionPanel.dataset.occurrenceIndex = String(occurrenceIndex);
                selectionPanel.dataset.occurrenceValid = 'true';
            } else {
                occurrenceBadgeEl.textContent = 'Không xác định được vị trí';
                occurrenceBadgeEl.className = 'novel-admin-badge novel-admin-occurrence-badge is-unknown';
                occurrenceBadgeEl.style.background = '#fff3e0';
                occurrenceBadgeEl.style.color = '#e65100';
                delete selectionPanel.dataset.occurrenceIndex;
                selectionPanel.dataset.occurrenceValid = 'false';
            }
        }

        // Snippet
        if (snippetEl) {
            snippetEl.textContent = contextSnippet || displayTerm;
        }

        // Chapter ID
        if (chapterIdEl && currentChapterId) {
            chapterIdEl.textContent = currentChapterId;
        }

        // Store data on selection panel for later use (bind commands / search)
        selectionPanel.dataset.selectedTerm = displayTerm;
        selectionPanel.dataset.contextSnippet = contextSnippet || displayTerm;
        if (currentChapterId) {
            selectionPanel.dataset.chapterId = currentChapterId;
        }
    }

    function handleClearSelection() {
        const selection = window.getSelection();
        if (selection) {
            selection.removeAllRanges();
        }

        resetSelectionPanel();
    }

    function resetSelectionPanel() {
        if (!selectionPanel) {
            return;
        }

        if (emptyStateEl) emptyStateEl.style.display = 'flex';
        if (activeStateEl) activeStateEl.style.display = 'none';
        if (clearBtnEl) clearBtnEl.style.display = 'none';

        if (selectedTermEl) selectedTermEl.textContent = '—';
        if (occurrenceBadgeEl) {
            occurrenceBadgeEl.textContent = '—';
            occurrenceBadgeEl.className = 'novel-admin-badge novel-admin-occurrence-badge';
            occurrenceBadgeEl.style.background = '';
            occurrenceBadgeEl.style.color = '';
        }
        if (snippetEl) snippetEl.textContent = '—';

        delete selectionPanel.dataset.selectedTerm;
        delete selectionPanel.dataset.occurrenceIndex;
        delete selectionPanel.dataset.occurrenceValid;
        delete selectionPanel.dataset.contextSnippet;

        // Also clear the wiki target whenever the captured selection is reset (Step 6D2A)
        resetWikiTarget();
    }

    // ----------------------------------------------------------------
    // Step 6D2A: Wiki Target Search
    // ----------------------------------------------------------------

    /**
     * Debounced input handler for the wiki target search input.
     * Cancels existing debounce timer and any in-flight request immediately on input.
     * Immediately invalidates previous search results and status on any input change.
     * Blank query or query > 100 chars: clear results and status immediately, no request.
     */
    function handleWikiSearchInput() {
        if (wikiSearchDebounceTimer) {
            clearTimeout(wikiSearchDebounceTimer);
            wikiSearchDebounceTimer = null;
        }

        if (wikiSearchAbortController) {
            wikiSearchAbortController.abort();
            wikiSearchAbortController = null;
        }

        // Immediately invalidate old displayed results and status so stale results cannot be clicked
        clearWikiSearchResults();
        hideWikiSearchStatus();

        const query = wikiSearchInputEl ? wikiSearchInputEl.value : '';
        const trimmed = query.trim();

        if (!trimmed || trimmed.length > 100) {
            return;
        }

        if (!currentChapterId) {
            return;
        }

        wikiSearchDebounceTimer = setTimeout(performWikiSearch, 250);
    }

    /**
     * Executes the wiki target search via the Admin endpoint.
     * Uses AbortController and request-local identity guard to prevent stale async completions
     * from updating UI state for a newer query/request.
     */
    function performWikiSearch() {
        const query = wikiSearchInputEl ? wikiSearchInputEl.value : '';
        const trimmed = query.trim();

        if (!trimmed || trimmed.length > 100 || !currentChapterId) {
            clearWikiSearchResults();
            hideWikiSearchStatus();
            return;
        }

        // Cancel any in-flight request
        if (wikiSearchAbortController) {
            wikiSearchAbortController.abort();
        }
        const currentController = new AbortController();
        wikiSearchAbortController = currentController;

        const url = '/admin/novel/chapters/' + encodeURIComponent(currentChapterId) +
                    '/wiki-references/search-targets?q=' + encodeURIComponent(trimmed);

        showWikiSearchStatus('Đang tìm kiếm…');
        if (wikiResultsListEl) wikiResultsListEl.style.display = 'none';

        fetch(url, { signal: currentController.signal })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                return response.json();
            })
            .then(function (data) {
                // Request-local controller guard: ignore completion if request was superseded or aborted
                if (currentController.signal.aborted || wikiSearchAbortController !== currentController) {
                    return;
                }
                const currentQuery = wikiSearchInputEl ? wikiSearchInputEl.value.trim() : '';
                if (currentQuery !== trimmed) {
                    return;
                }
                hideWikiSearchStatus();
                renderWikiSearchResults(data.items || []);
            })
            .catch(function (err) {
                if (err && err.name === 'AbortError') {
                    return; // Stale request — silently ignore
                }
                // Request-local controller guard: ignore completion if request was superseded or aborted
                if (currentController.signal.aborted || wikiSearchAbortController !== currentController) {
                    return;
                }
                const currentQuery = wikiSearchInputEl ? wikiSearchInputEl.value.trim() : '';
                if (currentQuery !== trimmed) {
                    return;
                }
                hideWikiSearchStatus();
                clearWikiSearchResults();
                showWikiSearchStatus('Không thể tải kết quả tìm kiếm.');
            });
    }

    /** Renders a list of wiki search result items as clickable list entries. */
    function renderWikiSearchResults(items) {
        if (!wikiResultsListEl) return;

        wikiResultsListEl.innerHTML = '';

        if (!items || items.length === 0) {
            wikiResultsListEl.style.display = 'none';
            showWikiSearchStatus('Không tìm thấy bài viết Wiki nào.');
            return;
        }

        hideWikiSearchStatus();

        items.forEach(function (item) {
            const li = document.createElement('li');
            li.className = 'novel-admin-wiki-result-item';
            li.setAttribute('role', 'option');
            li.setAttribute('tabindex', '0');
            li.dataset.wikiId = item.id || '';
            li.dataset.wikiTitle = item.title || '';
            li.dataset.wikiType = item.articleType || '';
            li.dataset.wikiAlias = item.matchedAlias || '';
            li.dataset.wikiSummary = item.summary || '';

            const titleEl = document.createElement('strong');
            titleEl.className = 'novel-admin-wiki-result-title';
            titleEl.textContent = item.title || '—';

            const typeEl = document.createElement('span');
            typeEl.className = 'novel-admin-wiki-result-type';
            typeEl.textContent = item.articleType || '';

            li.appendChild(titleEl);
            li.appendChild(typeEl);

            if (item.matchedAlias) {
                const aliasEl = document.createElement('span');
                aliasEl.className = 'novel-admin-wiki-result-alias';
                aliasEl.textContent = 'Alias: ' + item.matchedAlias;
                li.appendChild(aliasEl);
            }

            if (item.summary) {
                const summaryEl = document.createElement('span');
                summaryEl.className = 'novel-admin-wiki-result-summary';
                summaryEl.textContent = item.summary;
                li.appendChild(summaryEl);
            }

            li.addEventListener('click', function () {
                selectWikiTarget(item);
            });
            li.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    selectWikiTarget(item);
                }
            });

            wikiResultsListEl.appendChild(li);
        });

        wikiResultsListEl.style.display = 'block';
    }

    /**
     * Selects a wiki target article and stores its data in DOM state.
     * The results list is hidden; the selected-target card is shown.
     */
    function selectWikiTarget(item) {
        if (!item) return;

        if (wikiSearchDebounceTimer) {
            clearTimeout(wikiSearchDebounceTimer);
            wikiSearchDebounceTimer = null;
        }
        if (wikiSearchAbortController) {
            wikiSearchAbortController.abort();
            wikiSearchAbortController = null;
        }

        // Store selected wiki target data on the panel for the future bind step
        selectionPanel.dataset.wikiTargetId = item.id || '';
        selectionPanel.dataset.wikiTargetTitle = item.title || '';
        selectionPanel.dataset.wikiTargetType = item.articleType || '';

        // Hide results list
        if (wikiResultsListEl) wikiResultsListEl.style.display = 'none';

        // Clear search input
        if (wikiSearchInputEl) wikiSearchInputEl.value = '';

        hideWikiSearchStatus();

        // Render selected-target card
        if (selectedWikiTitleEl) selectedWikiTitleEl.textContent = item.title || '—';
        if (selectedWikiTypeEl) selectedWikiTypeEl.textContent = item.articleType || '';

        if (selectedWikiAliasEl) {
            if (item.matchedAlias) {
                selectedWikiAliasEl.textContent = 'Alias: ' + item.matchedAlias;
                selectedWikiAliasEl.style.display = 'inline';
            } else {
                selectedWikiAliasEl.textContent = '';
                selectedWikiAliasEl.style.display = 'none';
            }
        }

        if (selectedWikiSummaryEl) {
            if (item.summary) {
                selectedWikiSummaryEl.textContent = item.summary;
                selectedWikiSummaryEl.style.display = 'block';
            } else {
                selectedWikiSummaryEl.textContent = '';
                selectedWikiSummaryEl.style.display = 'none';
            }
        }

        if (selectedWikiTargetEl) selectedWikiTargetEl.style.display = 'flex';
    }

    /** Clears the selected wiki target from both DOM state and the card display. */
    function resetWikiTarget() {
        if (wikiSearchAbortController) {
            wikiSearchAbortController.abort();
            wikiSearchAbortController = null;
        }
        if (wikiSearchDebounceTimer) {
            clearTimeout(wikiSearchDebounceTimer);
            wikiSearchDebounceTimer = null;
        }

        if (wikiSearchInputEl) wikiSearchInputEl.value = '';
        clearWikiSearchResults();
        hideWikiSearchStatus();

        if (selectedWikiTargetEl) selectedWikiTargetEl.style.display = 'none';
        if (selectedWikiTitleEl) selectedWikiTitleEl.textContent = '—';
        if (selectedWikiTypeEl) selectedWikiTypeEl.textContent = '';
        if (selectedWikiAliasEl) { selectedWikiAliasEl.textContent = ''; selectedWikiAliasEl.style.display = 'none'; }
        if (selectedWikiSummaryEl) { selectedWikiSummaryEl.textContent = ''; selectedWikiSummaryEl.style.display = 'none'; }

        if (selectionPanel) {
            delete selectionPanel.dataset.wikiTargetId;
            delete selectionPanel.dataset.wikiTargetTitle;
            delete selectionPanel.dataset.wikiTargetType;
        }
    }

    function clearWikiSearchResults() {
        if (wikiResultsListEl) {
            wikiResultsListEl.innerHTML = '';
            wikiResultsListEl.style.display = 'none';
        }
    }

    function showWikiSearchStatus(message) {
        if (wikiSearchStatusEl) {
            wikiSearchStatusEl.textContent = message;
            wikiSearchStatusEl.style.display = 'block';
        }
    }

    function hideWikiSearchStatus() {
        if (wikiSearchStatusEl) {
            wikiSearchStatusEl.textContent = '';
            wikiSearchStatusEl.style.display = 'none';
        }
    }
})();
