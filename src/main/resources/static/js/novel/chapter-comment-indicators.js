/**
 * KiemLai Universe — Novel Reader Block Comment Indicator UI (MS-05E5E2)
 *
 * Responsibilities:
 * - Query inline discussion indicators from GET /api/novel/chapters/{chapterId}/comments/indicators.
 * - Manage chapter-scoped indicator loading, idempotency, and fetch race safety.
 * - Apply indicator data to canonical blocks via data-comment-thread-count and reader-block-has-comments.
 * - Dispatch 'kiemlai:comment-indicators-updated' so consumer affordances (e.g. reader-comment-affordance.js)
 *   can reflect real-time counts.
 * - Preserve Canonical Text Invariant: blockElement.textContent for elements with
 *   data-reader-block-key must NEVER change! Never inject DOM nodes into canonical blocks.
 * - Visual interactive button and hover/tap affordance are owned separately by reader-comment-affordance.js.
 * - Listens for 'kiemlai:chapter-changed' for continuous reader chapter transitions.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelChapterCommentIndicators = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelChapterCommentIndicators = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const READER_BODY_SELECTOR = '.novel-reader-chapter-body';
    const BLOCK_KEY_ATTR = 'data-reader-block-key';
    const THREAD_COUNT_ATTR = 'data-comment-thread-count';
    const INDICATOR_CLASS = 'reader-block-has-comments';
    const INDICATORS_API_BASE = '/api/novel/chapters';

    /**
     * Builds the indicators API URL for a given chapter ID.
     *
     * @param {string} chapterId
     * @returns {string}
     */
    function buildIndicatorsUrl(chapterId) {
        return INDICATORS_API_BASE + '/' + encodeURIComponent(chapterId) + '/comments/indicators';
    }

    /**
     * Clears indicator attributes and classes from all blocks within a chapter element.
     *
     * @param {Element} chapterBody
     */
    function clearIndicators(chapterBody) {
        if (!chapterBody || typeof chapterBody.querySelectorAll !== 'function') {
            return;
        }
        const decorated = chapterBody.querySelectorAll('.' + INDICATOR_CLASS + ', [' + THREAD_COUNT_ATTR + ']');
        for (let i = 0; i < decorated.length; i++) {
            const el = decorated[i];
            if (el.classList && typeof el.classList.remove === 'function') {
                el.classList.remove(INDICATOR_CLASS);
            }
            if (typeof el.removeAttribute === 'function') {
                el.removeAttribute(THREAD_COUNT_ATTR);
            }
        }
    }

    /**
     * Dispatches custom event to notify listeners (e.g. comment affordance) that indicators were updated.
     *
     * @param {Element} chapterBody
     */
    function notifyIndicatorsUpdated(chapterBody) {
        const targetDoc = (chapterBody && chapterBody.ownerDocument) || (typeof document !== 'undefined' ? document : null);
        if (targetDoc && typeof targetDoc.dispatchEvent === 'function') {
            const chapterId = (
                (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-chapter-id') : null) ||
                (chapterBody.dataset && chapterBody.dataset.chapterId) ||
                ''
            ).trim();
            const event = (typeof CustomEvent === 'function')
                ? new CustomEvent('kiemlai:comment-indicators-updated', { detail: { chapterId: chapterId } })
                : { type: 'kiemlai:comment-indicators-updated', detail: { chapterId: chapterId } };
            targetDoc.dispatchEvent(event);
        }
    }

    /**
     * Applies indicator counts to matching canonical blocks inside a chapter element.
     * Crucial invariant: never appends DOM nodes or text nodes inside canonical blocks.
     *
     * @param {Element} chapterBody
     * @param {Array<{blockKey: string, threadCount: number}>} indicators
     */
    function applyIndicators(chapterBody, indicators) {
        if (!chapterBody || !Array.isArray(indicators)) {
            return;
        }

        clearIndicators(chapterBody);

        if (indicators.length === 0) {
            notifyIndicatorsUpdated(chapterBody);
            return;
        }

        // Map blocks within this chapter container by blockKey for efficient, safe lookup
        const blocks = chapterBody.querySelectorAll('[' + BLOCK_KEY_ATTR + ']');
        const blockMap = new Map();
        for (let i = 0; i < blocks.length; i++) {
            const block = blocks[i];
            const key = (
                (typeof block.getAttribute === 'function' ? block.getAttribute(BLOCK_KEY_ATTR) : null) ||
                (block.dataset && block.dataset.readerBlockKey) ||
                ''
            ).trim();
            if (key && !blockMap.has(key)) {
                blockMap.set(key, block);
            }
        }

        for (let i = 0; i < indicators.length; i++) {
            const item = indicators[i];
            if (!item || typeof item !== 'object') {
                continue;
            }

            const rawBlockKey = item.blockKey;
            if (typeof rawBlockKey !== 'string') {
                continue;
            }
            const blockKey = rawBlockKey.trim();
            if (blockKey.length === 0) {
                continue;
            }

            const threadCount = item.threadCount;
            if (typeof threadCount !== 'number' ||
                !Number.isFinite(threadCount) ||
                !Number.isInteger(threadCount) ||
                threadCount <= 0) {
                continue;
            }

            const targetBlock = blockMap.get(blockKey);
            if (targetBlock) {
                if (targetBlock.classList && typeof targetBlock.classList.add === 'function') {
                    targetBlock.classList.add(INDICATOR_CLASS);
                }
                if (typeof targetBlock.setAttribute === 'function') {
                    targetBlock.setAttribute(THREAD_COUNT_ATTR, String(threadCount));
                }
            }
        }

        notifyIndicatorsUpdated(chapterBody);
    }

    /**
     * Module-owned in-memory state tracking chapter initialization per chapterBody element.
     * Maps chapterBody -> { chapterId: string, promise: Promise<void> }
     */
    const chapterInitializationState = new WeakMap();

    /**
     * Loads and applies comment indicators for a single chapter container element.
     *
     * Contract:
     * - Same chapterBody + same chapterId: at most one request (both in-flight and completed).
     * - Same chapterBody + different chapterId: starts exactly one new request for the new chapter.
     *
     * @param {Element} chapterBody
     * @param {Object} [options]
     * @returns {Promise<void>}
     */
    function loadChapterIndicators(chapterBody, options) {
        if (!chapterBody) {
            return Promise.resolve();
        }

        const chapterId = (
            (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-chapter-id') : null) ||
            (chapterBody.dataset && chapterBody.dataset.chapterId) ||
            ''
        ).trim();

        if (!chapterId) {
            return Promise.resolve();
        }

        const existingState = chapterInitializationState.get(chapterBody);
        if (existingState && existingState.chapterId === chapterId) {
            return existingState.promise;
        }

        const fetchFn = (options && options.fetchFn) || (typeof fetch === 'function' ? fetch : null);
        if (!fetchFn) {
            return Promise.resolve();
        }

        // When switching chapters on the same body, clear old indicators immediately
        if (existingState && existingState.chapterId !== chapterId) {
            clearIndicators(chapterBody);
        }

        const url = buildIndicatorsUrl(chapterId);

        const promise = fetchFn(url, {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            }
        })
            .then(function (response) {
                if (!response || !response.ok) {
                    return null;
                }
                return response.json();
            })
            .then(function (data) {
                // Race safety: verify chapterId on chapterBody has not changed while fetch was in flight
                const currentChapterId = (
                    (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-chapter-id') : null) ||
                    (chapterBody.dataset && chapterBody.dataset.chapterId) ||
                    ''
                ).trim();

                if (currentChapterId !== chapterId) {
                    return;
                }

                if (Array.isArray(data)) {
                    applyIndicators(chapterBody, data);
                }
            })
            .catch(function (error) {
                if (typeof console !== 'undefined' && typeof console.debug === 'function') {
                    console.debug('Failed to load chapter comment indicators for chapter ' + chapterId + ':', error);
                }
            });

        chapterInitializationState.set(chapterBody, {
            chapterId: chapterId,
            promise: promise
        });

        return promise;
    }

    /**
     * Finds chapter bodies under the provided root and initializes indicators.
     *
     * @param {Node|Element|Document} [scopeNode]
     * @param {Object} [options]
     * @returns {Promise<void>}
     */
    function initChapterCommentIndicators(scopeNode, options) {
        const rootNode = scopeNode || (typeof document !== 'undefined' ? document : null);
        if (!rootNode) {
            return Promise.resolve();
        }

        const chapterBodies = [];
        if (rootNode.nodeType === 1 && typeof rootNode.matches === 'function' && rootNode.matches(READER_BODY_SELECTOR)) {
            chapterBodies.push(rootNode);
        } else if (typeof rootNode.querySelectorAll === 'function') {
            const list = rootNode.querySelectorAll(READER_BODY_SELECTOR);
            for (let i = 0; i < list.length; i++) {
                chapterBodies.push(list[i]);
            }
        }

        if (chapterBodies.length === 0) {
            return Promise.resolve();
        }

        const promises = [];
        for (let i = 0; i < chapterBodies.length; i++) {
            promises.push(loadChapterIndicators(chapterBodies[i], options));
        }
        return Promise.all(promises).then(function () {});
    }

    /**
     * Binds lifecycle events (DOMContentLoaded, kiemlai:chapter-changed).
     *
     * @param {Document} [doc]
     * @param {Object} [options]
     */
    function bindChapterEvents(doc, options) {
        const targetDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (!targetDoc || typeof targetDoc.addEventListener !== 'function') {
            return;
        }

        function onReady() {
            initChapterCommentIndicators(targetDoc, options);
        }

        if (targetDoc.readyState === 'loading') {
            targetDoc.addEventListener('DOMContentLoaded', onReady);
        } else {
            onReady();
        }

        targetDoc.addEventListener('kiemlai:chapter-changed', function () {
            initChapterCommentIndicators(targetDoc, options);
        });
    }

    // Auto-bind in browser environment
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        bindChapterEvents(document);
    }

    return {
        READER_BODY_SELECTOR,
        BLOCK_KEY_ATTR,
        THREAD_COUNT_ATTR,
        INDICATOR_CLASS,
        EVENT_INDICATORS_UPDATED: 'kiemlai:comment-indicators-updated',
        buildIndicatorsUrl,
        clearIndicators,
        applyIndicators,
        notifyIndicatorsUpdated,
        loadChapterIndicators,
        initChapterCommentIndicators,
        bindChapterEvents
    };
});
