/**
 * Kiem Lai Novel Admin — Chapter Wiki References Selection & Occurrence Detection Script (MS-02.8.1 Step 6C)
 *
 * Requirements & Behavior:
 * 1. Restricts text selection exclusively to the rendered chapter preview (.novel-reader-chapter-body).
 * 2. Normalizes selected text (Unicode NFC, trim, collapse consecutive whitespaces, 1..100 characters).
 * 3. Calculates 1-based occurrence index strictly using rendered DOM Range (MUST match Reader semantics in reader-wiki-lookup.js).
 * 4. Extracts a bounded plain-text context snippet (<= 255 chars, VARCHAR(255) DB limit, no HTML/markup).
 * 5. Updates the Admin selection panel UI state (selected term, occurrence #n / cannot determine fallback, snippet, chapter ID, clear button).
 * 6. Does NOT send any HTTP request (pure client-side evaluation).
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

    let currentChapterId = null;
    let selectionTimeout = null;

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

        currentChapterId = chapterBodyEl.dataset.chapterId || chapterBodyEl.getAttribute('data-chapter-id') || null;

        // Selection listeners
        document.addEventListener('selectionchange', handleSelectionChange);
        document.addEventListener('mouseup', handlePointerEnd);
        document.addEventListener('touchend', handlePointerEnd);

        // Clear button listener
        if (clearBtnEl) {
            clearBtnEl.addEventListener('click', handleClearSelection);
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

    function renderSelectionState(displayTerm, occurrenceIndex, contextSnippet) {
        if (!selectionPanel) {
            return;
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

        if (emptyStateEl) emptyStateEl.style.display = 'block';
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
    }
})();
