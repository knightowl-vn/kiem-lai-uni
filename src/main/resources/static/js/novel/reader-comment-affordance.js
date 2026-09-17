/**
 * KiemLai Universe — Wattpad-Style Novel Reader Block Comment Affordance (MS-05E5F2)
 *
 * Responsibilities:
 * - Owns exactly ONE transient floating comment button for the Reader, appended outside chapter body.
 * - Shows discussion affordance next to the active canonical Reader block on desktop hover / focus.
 * - Shows discussion affordance on mobile tap without immediately opening discussion drawer.
 * - Preserves Canonical Text Invariant: never mutates block.textContent or injects nodes into blocks.
 * - Reuses E5E1/E5E2 indicator state (data-comment-thread-count) for badge display.
 * - Dispatches 'kiemlai:block-discussion-requested' when the affordance button is activated.
 * - Listens for 'kiemlai:chapter-changed' to hide affordance and discard chapter references.
 * - Listens for 'kiemlai:comment-indicators-updated' to refresh count on the active affordance.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderCommentAffordance = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderCommentAffordance = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const READER_BODY_SELECTOR = '.novel-reader-chapter-body';
    const BLOCK_KEY_ATTR = 'data-reader-block-key';
    const THREAD_COUNT_ATTR = 'data-comment-thread-count';
    const AFFORDANCE_BTN_CLASS = 'reader-comment-affordance-btn';
    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_INDICATORS_UPDATED = 'kiemlai:comment-indicators-updated';

    // State
    let affordanceBtn = null;
    let activeBlock = null;
    let isHoveringBlock = false;
    let isHoveringButton = false;
    let hideTimer = null;
    let currentDoc = null;
    let boundDoc = null;

    /**
     * Parses thread count integer from canonical block attribute.
     * Treats non-positive, NaN, missing, or malformed counts as 0.
     *
     * @param {Element} block
     * @returns {number}
     */
    function parseThreadCount(block) {
        if (!block || typeof block.getAttribute !== 'function') {
            return 0;
        }
        const raw = block.getAttribute(THREAD_COUNT_ATTR);
        if (typeof raw !== 'string') {
            return 0;
        }
        const trimmed = raw.trim();
        if (!/^\d+$/.test(trimmed)) {
            return 0;
        }
        const num = Number(trimmed);
        if (!Number.isSafeInteger(num) || num <= 0) {
            return 0;
        }
        return num;
    }

    /**
     * Parses contentVersion positive integer strictly from raw string attribute.
     * Rejects floats, scientific notation, negative numbers, zero, or trailing garbage.
     *
     * @param {*} raw
     * @returns {number|null}
     */
    function parseContentVersion(raw) {
        if (typeof raw !== 'string') {
            return null;
        }
        const trimmed = raw.trim();
        if (!/^\d+$/.test(trimmed)) {
            return null;
        }
        const num = Number(trimmed);
        if (!Number.isSafeInteger(num) || num <= 0) {
            return null;
        }
        return num;
    }

    /**
     * Updates button DOM content and accessibility label based on count.
     *
     * @param {number} count
     */
    function updateButtonContent(count) {
        if (!affordanceBtn) {
            return;
        }

        const iconSvg = '<svg class="reader-comment-icon" aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>';

        if (count > 0) {
            affordanceBtn.setAttribute('aria-label', 'Mở ' + count + ' thảo luận của đoạn này');
            affordanceBtn.innerHTML = iconSvg + '<span class="reader-comment-badge reader-comment-badge--count" aria-hidden="true">' + count + '</span>';
            if (affordanceBtn.classList && typeof affordanceBtn.classList.add === 'function') {
                affordanceBtn.classList.add('has-comments');
            }
        } else {
            affordanceBtn.setAttribute('aria-label', 'Bình luận đoạn này');
            affordanceBtn.innerHTML = iconSvg + '<span class="reader-comment-badge reader-comment-badge--plus" aria-hidden="true">+</span>';
            if (affordanceBtn.classList && typeof affordanceBtn.classList.remove === 'function') {
                affordanceBtn.classList.remove('has-comments');
            }
        }
    }

    /**
     * Positions the single floating button next to the given block.
     *
     * @param {Element} block
     */
    function positionButton(block) {
        if (!affordanceBtn || !block || typeof block.getBoundingClientRect !== 'function') {
            return;
        }

        const rect = block.getBoundingClientRect();
        const win = (currentDoc && currentDoc.defaultView) || (typeof window !== 'undefined' ? window : null);
        const viewportWidth = win && win.innerWidth ? win.innerWidth : 1024;
        const viewportHeight = win && win.innerHeight ? win.innerHeight : 768;

        // If block is completely outside visible viewport vertically, hide
        if (rect.bottom < 0 || rect.top > viewportHeight) {
            hideAffordance();
            return;
        }

        const btnWidth = affordanceBtn.offsetWidth || 36;
        const btnHeight = affordanceBtn.offsetHeight || 30;

        // Position near right edge / margin
        let left = rect.right + 10;
        let top = rect.top + 2;

        const maxLeft = viewportWidth - btnWidth - 8;
        if (left > maxLeft) {
            left = Math.max(8, rect.right - btnWidth - 6);
        }
        if (left < 8) {
            left = 8;
        }

        const maxTop = viewportHeight - btnHeight - 8;
        if (top > maxTop) {
            top = maxTop;
        }
        if (top < 8) {
            top = 8;
        }

        affordanceBtn.style.position = 'fixed';
        affordanceBtn.style.left = Math.round(left) + 'px';
        affordanceBtn.style.top = Math.round(top) + 'px';
    }

    /**
     * Clears any scheduled hide timer.
     */
    function clearHideTimer() {
        if (hideTimer) {
            clearTimeout(hideTimer);
            hideTimer = null;
        }
    }

    /**
     * Schedules hiding the affordance after a micro-delay.
     */
    function scheduleHide() {
        clearHideTimer();
        hideTimer = setTimeout(function () {
            if (!isHoveringBlock && !isHoveringButton) {
                hideAffordance();
            }
        }, 120);
    }

    /**
     * Reveals the affordance button for the specified canonical block.
     *
     * @param {Element} block
     */
    function showAffordance(block) {
        if (!block) {
            return;
        }
        clearHideTimer();
        activeBlock = block;
        const count = parseThreadCount(block);
        updateButtonContent(count);
        positionButton(block);
        if (affordanceBtn) {
            if (affordanceBtn.classList && typeof affordanceBtn.classList.add === 'function') {
                affordanceBtn.classList.add('is-visible');
            }
            affordanceBtn.style.display = 'inline-flex';
        }
    }

    /**
     * Hides the affordance button and resets active state.
     */
    function hideAffordance() {
        clearHideTimer();
        if (affordanceBtn) {
            if (affordanceBtn.classList && typeof affordanceBtn.classList.remove === 'function') {
                affordanceBtn.classList.remove('is-visible');
            }
            affordanceBtn.style.display = 'none';
        }
        activeBlock = null;
        isHoveringBlock = false;
        isHoveringButton = false;
    }

    /**
     * Handles activation of the comment affordance button.
     * Dispatches 'kiemlai:block-discussion-requested' with target block context.
     *
     * @param {Event} [e]
     */
    function onButtonClick(e) {
        if (e && typeof e.preventDefault === 'function') {
            e.preventDefault();
        }
        if (e && typeof e.stopPropagation === 'function') {
            e.stopPropagation();
        }

        if (!activeBlock) {
            hideAffordance();
            return;
        }

        const chapterBody = activeBlock.closest ? activeBlock.closest(READER_BODY_SELECTOR) : null;
        if (!chapterBody) {
            hideAffordance();
            return;
        }

        const chapterId = (
            (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-chapter-id') : null) ||
            (chapterBody.dataset && chapterBody.dataset.chapterId) ||
            ''
        ).trim();

        const rawVersion = (
            (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-content-version') : null) ||
            (chapterBody.dataset && chapterBody.dataset.contentVersion) ||
            null
        );
        const contentVersion = parseContentVersion(rawVersion);

        const blockKey = (
            (typeof activeBlock.getAttribute === 'function' ? activeBlock.getAttribute(BLOCK_KEY_ATTR) : null) ||
            (activeBlock.dataset && activeBlock.dataset.readerBlockKey) ||
            ''
        ).trim();

        // Validate required metadata; if invalid, hide safely and ignore
        if (!chapterId || contentVersion === null || !blockKey) {
            hideAffordance();
            return;
        }

        const threadCount = parseThreadCount(activeBlock);
        const canonicalText = activeBlock.textContent || '';

        const detail = {
            chapterId: chapterId,
            contentVersion: contentVersion,
            blockKey: blockKey,
            threadCount: threadCount,
            canonicalText: canonicalText
        };

        const event = (typeof CustomEvent === 'function')
            ? new CustomEvent(EVENT_DISCUSSION_REQUESTED, { detail: detail, bubbles: true })
            : { type: EVENT_DISCUSSION_REQUESTED, detail: detail };

        if (currentDoc && typeof currentDoc.dispatchEvent === 'function') {
            currentDoc.dispatchEvent(event);
        }
    }

    /**
     * Creates or retrieves the single floating button in document.body.
     *
     * @param {Document} doc
     * @returns {HTMLButtonElement}
     */
    function getOrCreateButton(doc) {
        if (affordanceBtn && affordanceBtn.parentElement) {
            return affordanceBtn;
        }

        const existing = doc.querySelector ? doc.querySelector('.' + AFFORDANCE_BTN_CLASS) : null;
        if (existing) {
            affordanceBtn = existing;
            return affordanceBtn;
        }

        const btn = doc.createElement('button');
        btn.type = 'button';
        btn.className = AFFORDANCE_BTN_CLASS;
        if (typeof btn.setAttribute === 'function') {
            btn.setAttribute('class', AFFORDANCE_BTN_CLASS);
        }
        btn.setAttribute('aria-label', 'Bình luận đoạn này');
        btn.style.display = 'none';

        btn.addEventListener('click', onButtonClick);

        btn.addEventListener('mouseenter', function () {
            clearHideTimer();
            isHoveringButton = true;
        });

        btn.addEventListener('mouseleave', function (e) {
            isHoveringButton = false;
            const related = e && e.relatedTarget;
            if (activeBlock && related && (related === activeBlock || (activeBlock.contains && activeBlock.contains(related)))) {
                isHoveringBlock = true;
                return;
            }
            scheduleHide();
        });

        const targetParent = doc.body || (doc.documentElement || doc);
        if (targetParent && typeof targetParent.appendChild === 'function') {
            targetParent.appendChild(btn);
        }

        affordanceBtn = btn;
        return btn;
    }

    /**
     * Checks whether a non-collapsed browser text selection currently exists.
     *
     * @param {Document} doc
     * @returns {boolean}
     */
    function hasActiveTextSelection(doc) {
        const win = (doc && doc.defaultView) || (typeof window !== 'undefined' ? window : null);
        if (win && typeof win.getSelection === 'function') {
            const sel = win.getSelection();
            if (sel && !sel.isCollapsed && sel.toString && sel.toString().trim().length > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Initializes the comment affordance module for the specified document.
     *
     * @param {Document} [targetDoc]
     * @returns {HTMLButtonElement|null}
     */
    function initReaderCommentAffordance(targetDoc) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) {
            return null;
        }
        currentDoc = doc;
        const btn = getOrCreateButton(doc);
        bindAffordanceEvents(doc);
        return btn;
    }

    /**
     * Binds document-level event delegation listeners for hover, tap, resize, and chapter lifecycle.
     *
     * @param {Document} [targetDoc]
     */
    function bindAffordanceEvents(targetDoc) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.addEventListener !== 'function') {
            return;
        }
        if (boundDoc === doc) {
            return;
        }
        boundDoc = doc;
        currentDoc = doc;

        // 1. Mouse/pointer enter & movement (desktop hover)
        doc.addEventListener('mouseover', function (e) {
            const target = e.target;
            if (!target || typeof target.closest !== 'function') {
                return;
            }

            // Pointer over the floating button itself
            if (affordanceBtn && (target === affordanceBtn || (affordanceBtn.contains && affordanceBtn.contains(target)))) {
                clearHideTimer();
                isHoveringButton = true;
                return;
            }

            // Find canonical block in chapter body
            const block = target.closest('[' + BLOCK_KEY_ATTR + ']');
            if (!block) {
                isHoveringBlock = false;
                scheduleHide();
                return;
            }

            const chapterBody = block.closest ? block.closest(READER_BODY_SELECTOR) : null;
            if (!chapterBody) {
                isHoveringBlock = false;
                scheduleHide();
                return;
            }

            clearHideTimer();
            isHoveringBlock = true;
            if (activeBlock !== block) {
                showAffordance(block);
            }
        });

        // 2. Mouse/pointer leave
        doc.addEventListener('mouseout', function (e) {
            const target = e.target;
            const related = e.relatedTarget;

            if (affordanceBtn && (target === affordanceBtn || (affordanceBtn.contains && affordanceBtn.contains(target)))) {
                if (related && activeBlock && (related === activeBlock || (activeBlock.contains && activeBlock.contains(related)))) {
                    isHoveringButton = false;
                    isHoveringBlock = true;
                    return;
                }
                isHoveringButton = false;
                scheduleHide();
                return;
            }

            if (activeBlock && (target === activeBlock || (activeBlock.contains && activeBlock.contains(target)))) {
                if (related && affordanceBtn && (related === affordanceBtn || (affordanceBtn.contains && affordanceBtn.contains(related)))) {
                    isHoveringBlock = false;
                    isHoveringButton = true;
                    return;
                }
                if (related && activeBlock.contains && activeBlock.contains(related)) {
                    // Moving between child elements inside the same block
                    return;
                }
                isHoveringBlock = false;
                scheduleHide();
            }
        });

        // 3. Click / Tap handling (mobile tap + click outside)
        doc.addEventListener('click', function (e) {
            const target = e.target;
            if (!target || typeof target.closest !== 'function') {
                return;
            }

            // If clicked affordance button itself, handled by button's click listener
            if (affordanceBtn && (target === affordanceBtn || (affordanceBtn.contains && affordanceBtn.contains(target)))) {
                return;
            }

            // Ignore link clicks or form controls inside Reader
            if (target.closest('a, button, input, select, textarea')) {
                return;
            }

            // If text selection is active (e.g. for Wiki lookup), do not trigger block affordance
            if (hasActiveTextSelection(doc)) {
                return;
            }

            const block = target.closest('[' + BLOCK_KEY_ATTR + ']');
            if (!block) {
                hideAffordance();
                return;
            }

            const chapterBody = block.closest ? block.closest(READER_BODY_SELECTOR) : null;
            if (!chapterBody) {
                hideAffordance();
                return;
            }

            // Touch / tap reveals affordance without immediately opening discussion
            showAffordance(block);
        });

        // 4. Keyboard focus support
        doc.addEventListener('focusin', function (e) {
            const target = e.target;
            if (!target || typeof target.closest !== 'function') {
                return;
            }

            if (affordanceBtn && (target === affordanceBtn || (affordanceBtn.contains && affordanceBtn.contains(target)))) {
                clearHideTimer();
                isHoveringButton = true;
                return;
            }

            const block = target.closest('[' + BLOCK_KEY_ATTR + ']');
            if (block && block.closest && block.closest(READER_BODY_SELECTOR)) {
                clearHideTimer();
                showAffordance(block);
            } else {
                scheduleHide();
            }
        });

        doc.addEventListener('focusout', function (e) {
            const related = e.relatedTarget;
            if (related && affordanceBtn && (related === affordanceBtn || (affordanceBtn.contains && affordanceBtn.contains(related)))) {
                return;
            }
            if (related && activeBlock && (related === activeBlock || (activeBlock.contains && activeBlock.contains(related)))) {
                return;
            }
            scheduleHide();
        });

        // 5. Window resize / scroll
        const win = (doc && doc.defaultView) || (typeof window !== 'undefined' ? window : null);
        if (win && typeof win.addEventListener === 'function') {
            const onViewportChange = function () {
                if (activeBlock && affordanceBtn && affordanceBtn.classList && affordanceBtn.classList.contains('is-visible')) {
                    positionButton(activeBlock);
                }
            };
            win.addEventListener('scroll', onViewportChange, { passive: true });
            win.addEventListener('resize', onViewportChange, { passive: true });
        }

        // 6. Continuous reader: chapter-changed event
        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            hideAffordance();
            activeBlock = null;
        });

        // 7. Indicators updated event: refresh count on currently active affordance
        doc.addEventListener(EVENT_INDICATORS_UPDATED, function (e) {
            if (!activeBlock || !affordanceBtn || !affordanceBtn.classList || !affordanceBtn.classList.contains('is-visible')) {
                return;
            }
            const updatedChapterId = (e && e.detail && typeof e.detail.chapterId === 'string')
                ? e.detail.chapterId.trim()
                : '';
            if (!updatedChapterId) {
                return;
            }
            const activeChapterBody = activeBlock.closest ? activeBlock.closest(READER_BODY_SELECTOR) : null;
            if (!activeChapterBody) {
                return;
            }
            const currentChapterId = (
                (typeof activeChapterBody.getAttribute === 'function' ? activeChapterBody.getAttribute('data-chapter-id') : null) ||
                (activeChapterBody.dataset && activeChapterBody.dataset.chapterId) ||
                ''
            ).trim();
            if (currentChapterId !== updatedChapterId) {
                return;
            }
            const count = parseThreadCount(activeBlock);
            updateButtonContent(count);
            positionButton(activeBlock);
        });
    }

    /**
     * Resets internal module state. Useful for test isolation and teardown.
     */
    function resetAffordanceState() {
        hideAffordance();
        affordanceBtn = null;
        activeBlock = null;
        isHoveringBlock = false;
        isHoveringButton = false;
        clearHideTimer();
        boundDoc = null;
        currentDoc = null;
    }

    // Auto-initialize when document is ready in browser
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderCommentAffordance(document);
            });
        } else {
            initReaderCommentAffordance(document);
        }
    }

    return {
        READER_BODY_SELECTOR,
        BLOCK_KEY_ATTR,
        THREAD_COUNT_ATTR,
        AFFORDANCE_BTN_CLASS,
        EVENT_DISCUSSION_REQUESTED,
        EVENT_CHAPTER_CHANGED,
        EVENT_INDICATORS_UPDATED,
        parseThreadCount,
        parseContentVersion,
        updateButtonContent,
        positionButton,
        showAffordance,
        hideAffordance,
        onButtonClick,
        getOrCreateButton,
        initReaderCommentAffordance,
        bindAffordanceEvents,
        resetAffordanceState,
        getAffordanceButton: function () { return affordanceBtn; },
        getActiveBlock: function () { return activeBlock; }
    };
});
