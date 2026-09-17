/**
 * KiemLai Universe — Novel Post-Auth Interaction Restore (MS-05E5G3A3)
 *
 * Responsibilities:
 * - Parses and structurally validates transient restore query parameters:
 *   discussionBlock, threadId, replyTo, intent.
 * - Reuses existing drawer open path by locating canonical Reader block in DOM
 *   and dispatching 'kiemlai:block-discussion-requested'.
 * - Waits for authoritative 'kiemlai:block-discussion-loaded' with token-based
 *   race safety before targeting comments.
 * - Resolves exact scroll target (replyTo -> threadId -> block drawer).
 * - Applies semantic scrollIntoView and temporary subtle highlight (.is-restored-target).
 * - Emits 'kiemlai:comment-reply-resume-requested' handoff for intent=reply on live comments.
 * - Handles tombstone/deleted targets gracefully (scrolls to tombstone, no reply handoff).
 * - Idempotently removes transient query parameters from URL via history.replaceState.
 * - Responds to 'kiemlai:block-discussion-closed' and 'kiemlai:chapter-changed'.
 * - Safe against selector injection (iterates nodes and checks dataset/attributes directly).
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderBlockDiscussionRestore = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderBlockDiscussionRestore = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const BLOCK_KEY_REGEX = /^blk-[0-9a-f]{16}-[1-9]\d*$/;
    const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_LOADED = 'kiemlai:block-discussion-loaded';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_DISCUSSION_LOAD_FAILED = 'kiemlai:block-discussion-load-failed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_REPLY_RESUME_REQUESTED = 'kiemlai:comment-reply-resume-requested';

    const RESTORED_TARGET_CLASS = 'is-restored-target';
    const HIGHLIGHT_DURATION_MS = 2500;

    // Module state
    let boundDoc = null;
    let restoreToken = 0;
    let activeRestore = null;
    let highlightTimer = null;
    let highlightedElement = null;

    /**
     * Parses and structurally validates transient restore parameters from search query or location.
     *
     * @param {string|Object} searchOrLocation
     * @returns {Object|null}
     */
    function parseRestoreContext(searchOrLocation) {
        let searchString = '';
        if (typeof searchOrLocation === 'string') {
            searchString = searchOrLocation;
        } else if (searchOrLocation && typeof searchOrLocation.search === 'string') {
            searchString = searchOrLocation.search;
        } else if (typeof window !== 'undefined' && window.location) {
            searchString = window.location.search || '';
        }

        if (!searchString) {
            return null;
        }

        const params = new URLSearchParams(searchString);
        const rawBlock = params.get('discussionBlock');
        if (!rawBlock || typeof rawBlock !== 'string') {
            return null;
        }

        const blockKey = rawBlock.trim();
        if (!BLOCK_KEY_REGEX.test(blockKey)) {
            return null;
        }

        let threadId = null;
        if (params.has('threadId')) {
            const rawThread = params.get('threadId');
            const trimmedThread = (typeof rawThread === 'string') ? rawThread.trim() : '';
            if (!UUID_REGEX.test(trimmedThread)) {
                return null;
            }
            threadId = trimmedThread;
        }

        let replyTo = null;
        if (params.has('replyTo')) {
            const rawReply = params.get('replyTo');
            const trimmedReply = (typeof rawReply === 'string') ? rawReply.trim() : '';
            if (!UUID_REGEX.test(trimmedReply)) {
                return null;
            }
            replyTo = trimmedReply;
        }

        let intent = 'open';
        if (params.has('intent')) {
            const rawIntent = (params.get('intent') || '').trim().toLowerCase();
            if (rawIntent !== 'open' && rawIntent !== 'reply') {
                return null;
            }
            intent = rawIntent;
        }

        return {
            blockKey: blockKey,
            threadId: threadId,
            replyTo: replyTo,
            intent: intent
        };
    }

    /**
     * Removes transient restore query parameters from browser URL via history.replaceState,
     * preserving pathname, unrelated query parameters, hash fragment, and history.state.
     *
     * @param {Window|null} win
     */
    function cleanRestoreParameters(win) {
        const w = win || (typeof window !== 'undefined' ? window : null);
        if (!w || !w.location || !w.history || typeof w.history.replaceState !== 'function') {
            return;
        }

        const search = w.location.search || '';
        if (!search) {
            return;
        }

        const params = new URLSearchParams(search);
        let modified = false;
        ['discussionBlock', 'threadId', 'replyTo', 'intent'].forEach(function (key) {
            if (params.has(key)) {
                params.delete(key);
                modified = true;
            }
        });

        if (!modified) {
            return;
        }

        const remainingQuery = params.toString();
        const newSearch = remainingQuery ? '?' + remainingQuery : '';
        const newUrl = (w.location.pathname || '') + newSearch + (w.location.hash || '');

        try {
            w.history.replaceState(w.history.state, '', newUrl);
        } catch (_) {}
    }

    /**
     * Clears any active highlight timer and removes the visual class.
     */
    function clearHighlight() {
        if (highlightTimer) {
            clearTimeout(highlightTimer);
            highlightTimer = null;
        }
        if (highlightedElement) {
            if (highlightedElement.classList && typeof highlightedElement.classList.remove === 'function') {
                highlightedElement.classList.remove(RESTORED_TARGET_CLASS);
            }
            highlightedElement = null;
        }
    }

    /**
     * Locates the canonical Reader block element by blockKey without CSS selector injection.
     *
     * @param {Element} chapterBody
     * @param {string} blockKey
     * @returns {Element|null}
     */
    function findReaderBlock(chapterBody, blockKey) {
        if (!chapterBody || typeof chapterBody.querySelectorAll !== 'function') {
            return null;
        }

        const candidates = chapterBody.querySelectorAll('[data-reader-block-key]');
        for (let i = 0; i < candidates.length; i++) {
            const el = candidates[i];
            const key = (
                (typeof el.getAttribute === 'function' ? el.getAttribute('data-reader-block-key') : null) ||
                (el.dataset && el.dataset.readerBlockKey) ||
                ''
            ).trim();
            if (key === blockKey) {
                return el;
            }
        }
        return null;
    }

    /**
     * Parses integer contentVersion strictly.
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
     * Parses thread count from block element.
     *
     * @param {Element} block
     * @returns {number}
     */
    function parseThreadCount(block) {
        if (!block || typeof block.getAttribute !== 'function') {
            return 0;
        }
        const raw = block.getAttribute('data-comment-thread-count');
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
     * Recursively collects all descendant elements of a root element.
     *
     * @param {Element} root
     * @returns {Array<Element>}
     */
    function getAllDescendants(root) {
        if (!root) {
            return [];
        }
        const results = [];
        function traverse(node) {
            if (!node || !node.childNodes) {
                return;
            }
            for (let i = 0; i < node.childNodes.length; i++) {
                const child = node.childNodes[i];
                results.push(child);
                traverse(child);
            }
        }
        traverse(root);
        return results;
    }

    /**
     * Locates the target comment or thread element inside the drawer content container.
     *
     * @param {Element} contentEl
     * @param {string|null} threadId
     * @param {string|null} replyTo
     * @returns {Object} { targetEl, resolvedThreadId, resolvedCommentId, isTombstone }
     */
    function resolveTargetElement(contentEl, threadId, replyTo) {
        if (!contentEl) {
            return { targetEl: null, resolvedThreadId: null, resolvedCommentId: null, isTombstone: false };
        }

        let targetEl = null;
        let resolvedThreadId = threadId;
        let resolvedCommentId = null;

        const allThreads = contentEl.querySelectorAll ? contentEl.querySelectorAll('.novel-block-discussion-thread') : [];

        if (threadId && replyTo) {
            let matchedThread = null;
            for (let i = 0; i < allThreads.length; i++) {
                const th = allThreads[i];
                const rootId = (
                    (typeof th.getAttribute === 'function' ? th.getAttribute('data-root-id') : null) ||
                    (typeof th.getAttribute === 'function' ? th.getAttribute('data-comment-id') : null)
                );
                if (rootId === threadId) {
                    matchedThread = th;
                    break;
                }
            }

            if (matchedThread) {
                if (replyTo === threadId) {
                    // Exact target is the .novel-comment--root element whose data-comment-id === replyTo
                    let rootEl = null;
                    const descendants = getAllDescendants(matchedThread);
                    for (let r = 0; r < descendants.length; r++) {
                        const el = descendants[r];
                        if (el.classList && el.classList.contains('novel-comment--root')) {
                            const cId = (typeof el.getAttribute === 'function') ? el.getAttribute('data-comment-id') : null;
                            if (cId === replyTo) {
                                rootEl = el;
                                break;
                            }
                        }
                    }

                    if (rootEl) {
                        targetEl = rootEl;
                        resolvedCommentId = replyTo;
                    } else {
                        // Degrade visually to matching thread card for context, but do NOT dispatch reply
                        targetEl = matchedThread;
                        resolvedCommentId = null;
                    }
                } else {
                    const commentsInThread = getAllDescendants(matchedThread);
                    for (let j = 0; j < commentsInThread.length; j++) {
                        const c = commentsInThread[j];
                        const cId = (
                            (typeof c.getAttribute === 'function' ? c.getAttribute('data-comment-id') : null) ||
                            (typeof c.getAttribute === 'function' ? c.getAttribute('data-reply-id') : null)
                        );
                        if (cId === replyTo) {
                            targetEl = c;
                            resolvedCommentId = replyTo;
                            break;
                        }
                    }

                    // If replyTo was not found inside the expected thread, degrade to root thread
                    if (!targetEl) {
                        targetEl = matchedThread;
                        resolvedCommentId = null; // Do not dispatch reply intent to wrong target
                    }
                }
            } else {
                // Missing thread degrades to block-level drawer
                targetEl = null;
                resolvedCommentId = null;
            }
        } else if (replyTo) {
            const allDescendants = getAllDescendants(contentEl);
            for (let i = 0; i < allDescendants.length; i++) {
                const c = allDescendants[i];
                if (!c.classList) {
                    continue;
                }
                const isComment = c.classList.contains('novel-comment--root') ||
                    c.classList.contains('novel-comment--reply') ||
                    (c.classList.contains('novel-comment') && !c.classList.contains('novel-block-discussion-thread'));
                if (!isComment) {
                    continue;
                }
                const cId = (
                    (typeof c.getAttribute === 'function' ? c.getAttribute('data-comment-id') : null) ||
                    (typeof c.getAttribute === 'function' ? c.getAttribute('data-reply-id') : null)
                );
                if (cId === replyTo) {
                    targetEl = c;
                    resolvedCommentId = replyTo;
                    let parentThread = c.closest ? c.closest('.novel-block-discussion-thread') : null;
                    if (!parentThread) {
                        let cur = c.parentElement;
                        while (cur) {
                            if (cur.classList && cur.classList.contains('novel-block-discussion-thread')) {
                                parentThread = cur;
                                break;
                            }
                            cur = cur.parentElement;
                        }
                    }
                    if (parentThread && typeof parentThread.getAttribute === 'function') {
                        resolvedThreadId = parentThread.getAttribute('data-root-id') || parentThread.getAttribute('data-comment-id') || replyTo;
                    } else {
                        resolvedThreadId = replyTo;
                    }
                    break;
                }
            }
        } else if (threadId) {
            for (let i = 0; i < allThreads.length; i++) {
                const th = allThreads[i];
                const rootId = (
                    (typeof th.getAttribute === 'function' ? th.getAttribute('data-root-id') : null) ||
                    (typeof th.getAttribute === 'function' ? th.getAttribute('data-comment-id') : null)
                );
                if (rootId === threadId) {
                    targetEl = th;
                    resolvedThreadId = threadId;
                    resolvedCommentId = null; // Thread card is target; replyTo is not inferred
                    break;
                }
            }
        }

        let isTombstone = false;
        if (targetEl) {
            if (targetEl.classList && typeof targetEl.classList.contains === 'function') {
                isTombstone = targetEl.classList.contains('is-tombstone');
            }
            if (!isTombstone && targetEl.querySelector) {
                const tombstoneBody = targetEl.querySelector('.novel-comment-body--tombstone');
                if (tombstoneBody) {
                    if (typeof tombstoneBody.closest === 'function') {
                        isTombstone = (tombstoneBody.closest('.novel-comment') === targetEl);
                    } else {
                        isTombstone = (tombstoneBody.parentNode === targetEl);
                    }
                }
            }
        }

        return {
            targetEl: targetEl,
            resolvedThreadId: resolvedThreadId,
            resolvedCommentId: resolvedCommentId,
            isTombstone: isTombstone
        };
    }

    /**
     * Handles 'kiemlai:block-discussion-loaded' event.
     *
     * @param {Event} e
     * @param {Document} doc
     * @param {Window} win
     */
    function onBlockDiscussionLoaded(e, doc, win) {
        if (!activeRestore) {
            return;
        }

        const detail = (e && e.detail) ? e.detail : {};
        if (detail.chapterId !== activeRestore.chapterId || detail.blockKey !== activeRestore.blockKey) {
            return; // Stale or mismatched loaded event
        }

        const restoreContext = activeRestore;
        activeRestore = null;

        const contentEl = doc.getElementById ? doc.getElementById('novelBlockDiscussionContent') : doc.querySelector('.novel-block-discussion-content');
        const resolved = resolveTargetElement(contentEl, restoreContext.threadId, restoreContext.replyTo);

        if (resolved.targetEl) {
            if (typeof resolved.targetEl.scrollIntoView === 'function') {
                resolved.targetEl.scrollIntoView({ block: 'nearest' });
            }

            clearHighlight();
            if (resolved.targetEl.classList && typeof resolved.targetEl.classList.add === 'function') {
                resolved.targetEl.classList.add(RESTORED_TARGET_CLASS);
                highlightedElement = resolved.targetEl;
                highlightTimer = setTimeout(function () {
                    clearHighlight();
                }, HIGHLIGHT_DURATION_MS);
            }

            if (restoreContext.intent === 'reply' && !resolved.isTombstone && resolved.resolvedCommentId) {
                const replyDetail = {
                    chapterId: restoreContext.chapterId,
                    blockKey: restoreContext.blockKey,
                    threadId: resolved.resolvedThreadId || resolved.resolvedCommentId,
                    commentId: resolved.resolvedCommentId
                };
                const replyEvent = (typeof CustomEvent === 'function')
                    ? new CustomEvent(EVENT_REPLY_RESUME_REQUESTED, { detail: replyDetail, bubbles: true })
                    : { type: EVENT_REPLY_RESUME_REQUESTED, detail: replyDetail };
                doc.dispatchEvent(replyEvent);
            }
        }

        // Terminal success: remove transient query parameters
        cleanRestoreParameters(win);
    }

    /**
     * Handles terminal load failure of block discussion drawer.
     *
     * @param {Event} e
     * @param {Window} win
     */
    function onBlockDiscussionLoadFailed(e, win) {
        if (!activeRestore) {
            return;
        }
        const detail = (e && e.detail) ? e.detail : {};
        if (detail.chapterId !== activeRestore.chapterId || detail.blockKey !== activeRestore.blockKey) {
            return; // Mismatched context
        }
        activeRestore = null;
        clearHighlight();
        cleanRestoreParameters(win);
    }

    /**
     * Handles manual drawer close or error fallback.
     *
     * @param {Window} win
     */
    function onDrawerClosed(win) {
        if (activeRestore) {
            activeRestore = null;
            cleanRestoreParameters(win);
        }
        clearHighlight();
    }

    /**
     * Handles chapter navigation transition.
     *
     * @param {Window} [win]
     */
    function onChapterChanged(win) {
        restoreToken++;
        const hadActiveRestore = (activeRestore !== null);
        activeRestore = null;
        clearHighlight();
        if (hadActiveRestore) {
            cleanRestoreParameters(win);
        }
    }

    /**
     * Binds event listeners to document once.
     *
     * @param {Document} doc
     * @param {Window} win
     */
    function bindDocumentListeners(doc, win) {
        if (boundDoc === doc) {
            return;
        }
        boundDoc = doc;

        doc.addEventListener(EVENT_DISCUSSION_LOADED, function (e) {
            onBlockDiscussionLoaded(e, doc, win);
        });

        doc.addEventListener(EVENT_DISCUSSION_LOAD_FAILED, function (e) {
            onBlockDiscussionLoadFailed(e, win);
        });

        doc.addEventListener(EVENT_DISCUSSION_CLOSED, function () {
            onDrawerClosed(win);
        });

        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            onChapterChanged(win);
        });
    }

    /**
     * Initiates the post-auth discussion restoration flow.
     *
     * @param {Document} [documentRef]
     * @param {Window} [windowRef]
     */
    function init(documentRef, windowRef) {
        const doc = documentRef || (typeof document !== 'undefined' ? document : null);
        const win = windowRef || (typeof window !== 'undefined' ? window : null);

        if (!doc || !win) {
            return;
        }

        bindDocumentListeners(doc, win);

        const search = (win.location && win.location.search) ? win.location.search : '';
        const params = new URLSearchParams(search);
        const hasTransient = params.has('discussionBlock') || params.has('threadId') || params.has('replyTo') || params.has('intent');

        const restoreContext = parseRestoreContext(win.location);
        if (!restoreContext) {
            if (hasTransient) {
                cleanRestoreParameters(win);
            }
            return;
        }

        const chapterBody = doc.querySelector ? doc.querySelector('.novel-reader-chapter-body') : null;
        if (!chapterBody) {
            cleanRestoreParameters(win);
            return;
        }

        const blockEl = findReaderBlock(chapterBody, restoreContext.blockKey);
        if (!blockEl) {
            cleanRestoreParameters(win);
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

        if (!chapterId || contentVersion === null) {
            cleanRestoreParameters(win);
            return;
        }

        const currentToken = ++restoreToken;
        activeRestore = {
            token: currentToken,
            chapterId: chapterId,
            blockKey: restoreContext.blockKey,
            threadId: restoreContext.threadId,
            replyTo: restoreContext.replyTo,
            intent: restoreContext.intent
        };

        const threadCount = parseThreadCount(blockEl);
        const canonicalText = blockEl.textContent || '';

        const detail = {
            chapterId: chapterId,
            contentVersion: contentVersion,
            blockKey: restoreContext.blockKey,
            threadCount: threadCount,
            canonicalText: canonicalText
        };

        const openEvent = (typeof CustomEvent === 'function')
            ? new CustomEvent(EVENT_DISCUSSION_REQUESTED, { detail: detail, bubbles: true })
            : { type: EVENT_DISCUSSION_REQUESTED, detail: detail };

        doc.dispatchEvent(openEvent);
    }

    /**
     * Resets module state for isolated test execution.
     */
    function resetRestoreState() {
        boundDoc = null;
        restoreToken = 0;
        activeRestore = null;
        clearHighlight();
    }

    function getActiveRestore() {
        return activeRestore;
    }

    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                init(document, window);
            });
        } else {
            init(document, window);
        }
    }

    return {
        init: init,
        parseRestoreContext: parseRestoreContext,
        cleanRestoreParameters: cleanRestoreParameters,
        getActiveRestore: getActiveRestore,
        resetRestoreState: resetRestoreState,
        EVENT_DISCUSSION_REQUESTED: EVENT_DISCUSSION_REQUESTED,
        EVENT_DISCUSSION_LOADED: EVENT_DISCUSSION_LOADED,
        EVENT_DISCUSSION_LOAD_FAILED: EVENT_DISCUSSION_LOAD_FAILED,
        EVENT_DISCUSSION_CLOSED: EVENT_DISCUSSION_CLOSED,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        EVENT_REPLY_RESUME_REQUESTED: EVENT_REPLY_RESUME_REQUESTED
    };
});
