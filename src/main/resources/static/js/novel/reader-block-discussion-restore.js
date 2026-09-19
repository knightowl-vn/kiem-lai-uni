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
    let completedDraftRootContext = null;

    let injectedDraftAdapter = null;
    let injectedDraftStore = null;

    let nodeDraftAdapter = null;
    if (typeof require === 'function') {
        try {
            nodeDraftAdapter = require('./reader-comment-drafts.js');
        } catch (_) {}
    }

    /**
     * Resolves NovelReaderCommentDrafts adapter instance.
     *
     * @returns {Object|null}
     */
    function resolveDraftAdapter() {
        if (injectedDraftAdapter) return injectedDraftAdapter;
        if (typeof window !== 'undefined') {
            if (window.NovelReaderCommentDrafts) return window.NovelReaderCommentDrafts;
            if (window.KiemLai && window.KiemLai.NovelReaderCommentDrafts) return window.KiemLai.NovelReaderCommentDrafts;
        }
        if (typeof globalThis !== 'undefined') {
            if (globalThis.NovelReaderCommentDrafts) return globalThis.NovelReaderCommentDrafts;
            if (globalThis.KiemLai && globalThis.KiemLai.NovelReaderCommentDrafts) return globalThis.KiemLai.NovelReaderCommentDrafts;
        }
        return nodeDraftAdapter;
    }

    /**
     * Resolves EphemeralDraftStore instance across multiple runtime contexts.
     *
     * @returns {Object|null}
     */
    function resolveDraftStore() {
        if (injectedDraftStore) return injectedDraftStore;
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.resolveDraftStore === 'function') {
            const resolved = adapter.resolveDraftStore();
            if (resolved) return resolved;
        }
        if (typeof window !== 'undefined') {
            if (window.EphemeralDraftStore) return window.EphemeralDraftStore;
            if (window.KiemLai && window.KiemLai.EphemeralDraftStore) return window.KiemLai.EphemeralDraftStore;
        }
        if (typeof globalThis !== 'undefined') {
            if (globalThis.EphemeralDraftStore) return globalThis.EphemeralDraftStore;
            if (globalThis.KiemLai && globalThis.KiemLai.EphemeralDraftStore) return globalThis.KiemLai.EphemeralDraftStore;
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/ephemeral-draft-store.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Validates an active-block Root marker string.
     * Removes corrupted JSON or malformed non-root markers safely from store.
     * Known non-root markers (type 'reply' or 'edit') are ignored without removal.
     *
     * @param {string} rawMarker
     * @param {string} markerKey
     * @param {Object} [store]
     * @returns {{type: 'root', blockKey: string}|null}
     */
    function validateRootMarker(rawMarker, markerKey, store) {
        if (!rawMarker || typeof rawMarker !== 'string') return null;
        let parsed;
        try {
            parsed = JSON.parse(rawMarker);
        } catch (_) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        // Known non-root markers (reply, edit) must be IGNORED WITHOUT REMOVAL
        if (parsed.type === 'reply' || parsed.type === 'edit') {
            return null;
        }
        if (parsed.type !== 'root') {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        if (typeof parsed.blockKey !== 'string' || !parsed.blockKey.trim()) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        return {
            type: 'root',
            blockKey: parsed.blockKey.trim()
        };
    }

    /**
     * Removes the chapter-scoped active-block marker only if it matches type 'root' and the given blockKey.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     */
    function removeRootMarkerIfMatching(chapterId, blockKey) {
        if (!chapterId || !blockKey) return;
        const adapter = resolveDraftAdapter();
        const store = resolveDraftStore();
        if (!adapter || !store) return;
        const markerKey = adapter.getBlockActiveMarkerKey(chapterId);
        if (!markerKey) return;
        const raw = store.load(markerKey);
        if (!raw || typeof raw !== 'string') return;
        try {
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                if (parsed.type === 'reply' || parsed.type === 'edit') {
                    return; // Known future markers must not be touched
                }
                if (parsed.type === 'root' && typeof parsed.blockKey === 'string' && parsed.blockKey.trim() === String(blockKey).trim()) {
                    store.remove(markerKey);
                }
            } else {
                store.remove(markerKey);
            }
        } catch (_) {
            store.remove(markerKey);
        }
    }

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
        if (restoreContext.source === 'draft-root') {
            completedDraftRootContext = {
                doc: doc,
                chapterId: restoreContext.chapterId,
                blockKey: restoreContext.blockKey
            };
        } else {
            completedDraftRootContext = null;
        }

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

        // Terminal success: remove transient query parameters if URL restore
        if (restoreContext.source !== 'draft-root') {
            cleanRestoreParameters(win);
        }
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
        const failedContext = activeRestore;
        activeRestore = null;
        clearHighlight();

        if (failedContext.source === 'draft-root') {
            removeRootMarkerIfMatching(failedContext.chapterId, failedContext.blockKey);
        } else {
            cleanRestoreParameters(win);
        }
        completedDraftRootContext = null;
    }

    /**
     * Handles manual drawer close or error fallback.
     *
     * @param {Window} win
     */
    function onDrawerClosed(win) {
        if (activeRestore) {
            const closedContext = activeRestore;
            activeRestore = null;
            if (closedContext.source === 'draft-root') {
                removeRootMarkerIfMatching(closedContext.chapterId, closedContext.blockKey);
            } else {
                cleanRestoreParameters(win);
            }
        }
        clearHighlight();
        completedDraftRootContext = null;
    }

    /**
     * Handles chapter navigation transition.
     *
     * @param {Window} [win]
     */
    function onChapterChanged(win) {
        restoreToken++;
        const prevContext = activeRestore;
        activeRestore = null;
        clearHighlight();
        if (prevContext) {
            if (prevContext.source === 'draft-root') {
                removeRootMarkerIfMatching(prevContext.chapterId, prevContext.blockKey);
            } else {
                cleanRestoreParameters(win);
            }
        }
        completedDraftRootContext = null;
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
     * Programmatically opens a block discussion drawer and targets a thread/comment.
     * Reusable by external UI triggers (e.g. Chapter Comments "Xem đoạn gốc").
     *
     * @param {Object} target { blockKey, threadId, replyTo, intent, chapterId, contentVersion, source }
     * @param {Document} [documentRef]
     * @param {Window} [windowRef]
     * @returns {boolean} true if request was successfully dispatched, false otherwise
     */
    function openDiscussionTarget(target, documentRef, windowRef) {
        const doc = documentRef || (typeof document !== 'undefined' ? document : null);
        const win = windowRef || (typeof window !== 'undefined' ? window : null);

        if (!doc || !target || typeof target !== 'object') {
            return false;
        }

        const rawBlock = target.blockKey;
        if (!rawBlock || typeof rawBlock !== 'string') {
            return false;
        }
        const blockKey = rawBlock.trim();
        if (!blockKey) {
            return false;
        }

        let threadId = null;
        if (target.threadId) {
            const rawThread = String(target.threadId).trim();
            if (rawThread) {
                threadId = rawThread;
            }
        }

        let replyTo = null;
        if (target.replyTo) {
            const rawReply = String(target.replyTo).trim();
            if (rawReply) {
                replyTo = rawReply;
            }
        }

        let intent = 'open';
        if (target.intent) {
            const rawIntent = String(target.intent).trim().toLowerCase();
            if (rawIntent === 'open' || rawIntent === 'reply') {
                intent = rawIntent;
            }
        }

        bindDocumentListeners(doc, win);

        const chapterBody = doc.querySelector ? doc.querySelector('.novel-reader-chapter-body') : null;
        if (!chapterBody) {
            return false;
        }

        const blockEl = findReaderBlock(chapterBody, blockKey);
        if (!blockEl) {
            return false;
        }

        const chapterId = (
            (typeof target.chapterId === 'string' && target.chapterId.trim()) ||
            (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-chapter-id') : null) ||
            (chapterBody.dataset && chapterBody.dataset.chapterId) ||
            ''
        ).trim();

        const rawVersion = (
            (target.contentVersion != null ? String(target.contentVersion) : null) ||
            (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-content-version') : null) ||
            (chapterBody.dataset && chapterBody.dataset.contentVersion) ||
            null
        );
        let contentVersion = parseContentVersion(rawVersion);
        if (contentVersion === null && target.contentVersion === undefined && !chapterBody.hasAttribute('data-content-version')) {
            contentVersion = 1;
        }

        if (!chapterId || contentVersion === null) {
            return false;
        }

        if (completedDraftRootContext && (target.source !== 'draft-root' || completedDraftRootContext.blockKey !== blockKey || completedDraftRootContext.chapterId !== chapterId)) {
            completedDraftRootContext = null;
        }

        const currentToken = ++restoreToken;
        activeRestore = {
            token: currentToken,
            chapterId: chapterId,
            blockKey: blockKey,
            threadId: threadId,
            replyTo: replyTo,
            intent: intent,
            source: target.source || 'url'
        };

        const threadCount = parseThreadCount(blockEl);
        const canonicalText = blockEl.textContent || '';

        const detail = {
            chapterId: chapterId,
            contentVersion: contentVersion,
            blockKey: blockKey,
            threadCount: threadCount,
            canonicalText: canonicalText
        };

        const openEvent = (typeof CustomEvent === 'function')
            ? new CustomEvent(EVENT_DISCUSSION_REQUESTED, { detail: detail, bubbles: true })
            : { type: EVENT_DISCUSSION_REQUESTED, detail: detail };

        doc.dispatchEvent(openEvent);
        return true;
    }

    /**
     * Attempts active-block draft marker restore when no URL restore parameters own the init turn.
     *
     * @param {Document} doc
     * @param {Window} win
     * @returns {boolean}
     */
    function attemptActiveBlockDraftRestore(doc, win) {
        if (activeRestore !== null) {
            return false;
        }
        const chapterBody = doc.querySelector ? doc.querySelector('.novel-reader-chapter-body') : null;
        if (!chapterBody) {
            return false;
        }
        const currentChapterId = (
            (typeof chapterBody.getAttribute === 'function' ? chapterBody.getAttribute('data-chapter-id') : null) ||
            (chapterBody.dataset && chapterBody.dataset.chapterId) ||
            ''
        ).trim();
        if (!currentChapterId) {
            return false;
        }

        const adapter = resolveDraftAdapter();
        const store = resolveDraftStore();
        if (!adapter || !store) {
            return false;
        }

        const markerKey = adapter.getBlockActiveMarkerKey(currentChapterId);
        if (!markerKey) {
            return false;
        }

        const rawMarker = store.load(markerKey);
        if (!rawMarker) {
            return false;
        }

        const validMarker = validateRootMarker(rawMarker, markerKey, store);
        if (!validMarker) {
            return false;
        }

        if (completedDraftRootContext &&
            completedDraftRootContext.doc === doc &&
            completedDraftRootContext.chapterId === currentChapterId &&
            completedDraftRootContext.blockKey === validMarker.blockKey) {
            return false;
        }

        const blockEl = findReaderBlock(chapterBody, validMarker.blockKey);
        if (!blockEl) {
            // Missing or stale Reader block: do not fabricate drawer context, remove ONLY Root active marker, leave draft untouched
            removeRootMarkerIfMatching(currentChapterId, validMarker.blockKey);
            return false;
        }

        const success = openDiscussionTarget({
            chapterId: currentChapterId,
            blockKey: validMarker.blockKey,
            intent: 'open',
            source: 'draft-root'
        }, doc, win);

        if (!success) {
            removeRootMarkerIfMatching(currentChapterId, validMarker.blockKey);
        }

        return success;
    }

    /**
     * Initiates the post-auth discussion restoration flow or active-block draft restoration.
     *
     * @param {Document} [documentRef]
     * @param {Window} [windowRef]
     * @param {Object} [options]
     */
    function init(documentRef, windowRef, options) {
        const doc = documentRef || (typeof document !== 'undefined' ? document : null);
        const win = windowRef || (typeof window !== 'undefined' ? window : null);

        if (!doc || !win) {
            return;
        }

        if (options) {
            if (options.draftAdapter) injectedDraftAdapter = options.draftAdapter;
            if (options.draftStore) injectedDraftStore = options.draftStore;
        }

        bindDocumentListeners(doc, win);

        const search = (win.location && win.location.search) ? win.location.search : '';
        const params = new URLSearchParams(search);
        const hasTransient = params.has('discussionBlock') || params.has('threadId') || params.has('replyTo') || params.has('intent');

        const restoreContext = parseRestoreContext(win.location);
        if (!restoreContext) {
            if (hasTransient) {
                cleanRestoreParameters(win);
            } else {
                attemptActiveBlockDraftRestore(doc, win);
            }
            return;
        }

        const success = openDiscussionTarget(restoreContext, doc, win);
        if (!success) {
            cleanRestoreParameters(win);
        }
    }

    /**
     * Resets module state for isolated test execution.
     */
    function resetRestoreState() {
        boundDoc = null;
        restoreToken = 0;
        activeRestore = null;
        completedDraftRootContext = null;
        clearHighlight();
        injectedDraftAdapter = null;
        injectedDraftStore = null;
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
        openDiscussionTarget: openDiscussionTarget,
        parseRestoreContext: parseRestoreContext,
        cleanRestoreParameters: cleanRestoreParameters,
        getActiveRestore: getActiveRestore,
        resetRestoreState: resetRestoreState,
        validateRootMarker: validateRootMarker,
        removeRootMarkerIfMatching: removeRootMarkerIfMatching,
        attemptActiveBlockDraftRestore: attemptActiveBlockDraftRestore,
        setDraftAdapter: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftAdapterImplementation: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftStore: function (store) { injectedDraftStore = store; },
        setDraftStoreImplementation: function (store) { injectedDraftStore = store; },
        EVENT_DISCUSSION_REQUESTED: EVENT_DISCUSSION_REQUESTED,
        EVENT_DISCUSSION_LOADED: EVENT_DISCUSSION_LOADED,
        EVENT_DISCUSSION_LOAD_FAILED: EVENT_DISCUSSION_LOAD_FAILED,
        EVENT_DISCUSSION_CLOSED: EVENT_DISCUSSION_CLOSED,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        EVENT_REPLY_RESUME_REQUESTED: EVENT_REPLY_RESUME_REQUESTED
    };
});
