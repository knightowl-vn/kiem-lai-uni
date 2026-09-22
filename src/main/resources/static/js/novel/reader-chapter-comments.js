/**
 * KiemLai Universe — Bottom-of-Chapter Comment Section Read UI (MS-05E5H2C)
 *
 * Responsibilities:
 * - Owns the read-only rendering of the bottom-of-chapter comment feed.
 * - Fetches initial page (page=0, size=20) from GET /api/novel/chapters/{chapterId}/comments/feed.
 * - Renders thread cards in API-delivered order (newest root first).
 * - Renders nested replies underneath each root in API-delivered order (chronological ASC).
 * - Applies Wattpad-style immediate-parent attribution (@ParentName) for nested replies.
 * - Handles deleted replies gracefully using deterministic tombstone fallbacks.
 * - Manages status transitions: loading spinner, empty state, error with retry.
 * - Enforces race safety via load tokens against out-of-order responses.
 * - Responds to 'kiemlai:chapter-changed' to refresh comments for the new chapter.
 * - Feed module owns read rendering plus Reply affordance metadata and authoritative refresh.
 * - Actual Reply mutation orchestration belongs to reader-chapter-comment-reply-composer.js.
 * - Edit/Delete affordances and passage excerpts remain strictly absent.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderChapterComments = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderChapterComments = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const SECTION_ID = 'novelChapterComments';
    const TITLE_ID = 'novelChapterCommentsTitle';
    const COUNT_ID = 'novelChapterCommentsCount';
    const STATUS_ID = 'novelChapterCommentsStatus';
    const LIST_ID = 'novelChapterCommentsList';
    const MORE_ID = 'novelChapterCommentsMore';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_FEED_REPLACING = 'kiemlai:chapter-comments-feed-replacing';
    const EVENT_FEED_RENDERED = 'kiemlai:chapter-comments-feed-rendered';

    const INITIAL_VISIBLE_REPLIES = 3;
    const REPLY_REVEAL_BATCH_SIZE = 5;

    // Internal module state
    let currentDoc = null;
    let currentChapterId = null;
    let injectedFetch = null;
    let injectedOpenDiscussionTarget = null;
    let loadToken = 0;
    let currentStatus = 'idle'; // 'idle' | 'loading' | 'empty' | 'populated' | 'error'
    let currentItems = [];
    let currentPage = 0;
    let hasNext = false;
    let isLoadingMore = false;
    let isRefreshing = false;
    let rootPageMap = Object.create(null);
    let injectedAuthenticated = null;
    let injectedReportModal = null;
    let injectedCommentPresentation = undefined;
    let chapterChangedHandler = null;
    let documentClickHandler = null;
    let highlightedElement = null;
    let highlightTimer = null;
    let pendingDeepLink = null;

    /**
     * Formats comment count label (e.g. '3 bình luận').
     *
     * @param {number} count
     * @returns {string}
     */
    function formatCommentCount(count) {
        const num = Number(count);
        if (!Number.isSafeInteger(num) || num < 0) {
            return '';
        }
        return num + ' bình luận';
    }

    /**
     * Formats ISO timestamp to localized readable string (DD/MM/YYYY HH:mm).
     * Returns empty string for invalid/missing timestamp without throwing.
     *
     * @param {string} isoString
     * @returns {string}
     */
    function formatTimestamp(isoString) {
        const pres = resolveCommentPresentation();
        if (pres && typeof pres.formatTimestamp === 'function') {
            return pres.formatTimestamp(isoString);
        }
        if (typeof RelativeTime !== 'undefined' && typeof RelativeTime.formatAbsolute === 'function') {
            return RelativeTime.formatAbsolute(isoString);
        }
        return '';
    }

    /**
     * Checks if a comment has been edited by comparing createdAt and updatedAt timestamps.
     *
     * @param {Object} comment
     * @returns {boolean}
     */
    function isCommentEdited(comment) {
        if (!comment || typeof comment !== 'object') {
            return false;
        }
        if (comment.edited === true) {
            return true;
        }
        if (!comment.createdAt || !comment.updatedAt) {
            return false;
        }
        try {
            const createdMs = new Date(comment.createdAt).getTime();
            const updatedMs = new Date(comment.updatedAt).getTime();
            return Number.isFinite(createdMs) && Number.isFinite(updatedMs) && updatedMs > createdMs;
        } catch (_) {
            return false;
        }
    }

    /**
     * Clears all children of a container element safely.
     *
     * @param {Element} container
     */
    function clearElement(container) {
        if (!container) {
            return;
        }
        if (typeof container.replaceChildren === 'function') {
            container.replaceChildren();
        } else {
            while (container.firstChild) {
                container.removeChild(container.firstChild);
            }
        }
    }

    /**
     * Sanitizes an avatar URL ensuring safe protocols (http, https, or same-origin path).
     * Disallows dangerous protocols (javascript:, data:, vbscript:, blob:, etc.)
     * and disallows protocol-relative URLs (//evil.com).
     *
     * @param {string} url
     * @returns {string|null}
     */
    function sanitizeAvatarUrl(url) {
        if (typeof url !== 'string') {
            return null;
        }
        const trimmed = url.trim();
        if (!trimmed) {
            return null;
        }
        const lower = trimmed.toLowerCase();
        if (lower.startsWith('https://') || lower.startsWith('http://')) {
            return trimmed;
        }
        if (lower.startsWith('/') && !lower.startsWith('//')) {
            return trimmed;
        }
        return null;
    }

    /**
     * Creates an avatar fallback element with the first initial of the display name.
     *
     * @param {string} displayName
     * @param {Document} doc
     * @returns {Element}
     */
    function createAvatarFallback(displayName, doc) {
        const fallback = doc.createElement('span');
        fallback.className = 'novel-comment-avatar novel-comment-avatar--fallback';
        fallback.setAttribute('aria-hidden', 'true');
        const trimmed = (typeof displayName === 'string') ? displayName.trim() : '';
        const firstChar = trimmed ? trimmed.charAt(0).toUpperCase() : 'U';
        fallback.textContent = firstChar;
        return fallback;
    }

    /**
     * Renders author presentation (avatar and displayName) into comment header.
     *
     * @param {Element} headerEl
     * @param {Object|null} author
     * @param {Document} doc
     */
    function renderAuthorPresentation(headerEl, author, doc) {
        const authorObj = (author && typeof author === 'object') ? author : null;
        const rawName = (authorObj && typeof authorObj.displayName === 'string') ? authorObj.displayName.trim() : '';
        const displayName = rawName || 'Người dùng';
        const rawAvatar = (authorObj && typeof authorObj.avatarUrl === 'string') ? authorObj.avatarUrl.trim() : '';
        const sanitizedAvatar = sanitizeAvatarUrl(rawAvatar);

        if (sanitizedAvatar) {
            const avatarImg = doc.createElement('img');
            avatarImg.className = 'novel-comment-avatar';
            avatarImg.src = sanitizedAvatar;
            avatarImg.setAttribute('src', sanitizedAvatar);
            avatarImg.alt = displayName;
            avatarImg.setAttribute('alt', displayName);
            avatarImg.setAttribute('referrerpolicy', 'no-referrer');
            avatarImg.onerror = function () {
                const parent = avatarImg.parentNode;
                if (parent) {
                    const fallback = createAvatarFallback(displayName, doc);
                    if (typeof parent.replaceChild === 'function') {
                        parent.replaceChild(fallback, avatarImg);
                    } else if (typeof parent.removeChild === 'function') {
                        parent.removeChild(avatarImg);
                        parent.appendChild(fallback);
                    }
                }
            };
            headerEl.appendChild(avatarImg);
        } else {
            headerEl.appendChild(createAvatarFallback(displayName, doc));
        }

        const authorSpan = doc.createElement('span');
        authorSpan.className = 'novel-comment-author';
        authorSpan.textContent = displayName;
        headerEl.appendChild(authorSpan);
    }

    /**
     * Resolves the single active direct child display name for contextual tombstone rendering.
     * Returns the non-blank displayName of the unique direct active child, or null if 0, >1,
     * or missing/blank child author display name.
     *
     * @param {Array} replies
     * @param {string|number} tombstoneId
     * @returns {string|null}
     */
    function resolveTombstoneContextChildDisplayName(replies, tombstoneId) {
        if (!Array.isArray(replies) || !tombstoneId) {
            return null;
        }
        const targetParentId = String(tombstoneId).trim();
        let activeDirectChild = null;
        let activeDirectChildCount = 0;

        for (let i = 0; i < replies.length; i++) {
            const r = replies[i];
            if (!r) continue;

            const isDeleted = r.tombstone === true || r.status === 'DELETED';
            if (isDeleted) continue;

            if (r.parentCommentId != null && String(r.parentCommentId).trim() === targetParentId) {
                activeDirectChildCount++;
                if (activeDirectChildCount === 1) {
                    activeDirectChild = r;
                } else {
                    return null;
                }
            }
        }

        if (activeDirectChildCount !== 1 || !activeDirectChild) {
            return null;
        }

        const author = activeDirectChild.author;
        if (!author || typeof author !== 'object') {
            return null;
        }

        if (typeof author.displayName !== 'string') {
            return null;
        }

        const trimmedName = author.displayName.trim();
        return trimmedName.length > 0 ? trimmedName : null;
    }

    /**
     * Resolves DOM elements for the chapter comments section.
     *
     * @returns {Object}
     */
    function getElements() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) {
            return { sectionEl: null, listEl: null, statusEl: null, countEl: null, moreEl: null };
        }
        return {
            sectionEl: doc.getElementById(SECTION_ID),
            listEl: doc.getElementById(LIST_ID),
            statusEl: doc.getElementById(STATUS_ID),
            countEl: doc.getElementById(COUNT_ID),
            moreEl: doc.getElementById(MORE_ID)
        };
    }

    /**
     * Resolves the window reference safely.
     *
     * @param {Document} [doc]
     * @returns {Window|Object|null}
     */
    function resolveWindow(doc) {
        if (doc && doc.defaultView) {
            return doc.defaultView;
        }
        if (typeof window !== 'undefined') {
            return window;
        }
        if (typeof globalThis !== 'undefined') {
            return globalThis;
        }
        return null;
    }

    /**
     * Extracts deep-link parameters (commentId, threadId) from options or window.location.
     * Both must be non-empty; otherwise returns null.
     *
     * @param {Document} [doc]
     * @param {Object} [options]
     * @returns {{commentId: string, threadId: string, fromOptions: boolean}|null}
     */
    function extractDeepLinkParams(doc, options) {
        let commentId = null;
        let threadId = null;
        let fromOptions = false;

        if (options && typeof options === 'object') {
            if (options.commentId && options.threadId) {
                commentId = String(options.commentId).trim();
                threadId = String(options.threadId).trim();
                fromOptions = true;
            }
        }

        if (!commentId || !threadId) {
            const win = resolveWindow(doc);
            if (win && win.location && win.location.search) {
                try {
                    if (typeof URLSearchParams === 'function') {
                        const params = new URLSearchParams(win.location.search);
                        const c = params.get('commentId');
                        const t = params.get('threadId');
                        if (c && t) {
                            commentId = String(c).trim();
                            threadId = String(t).trim();
                            fromOptions = false;
                        }
                    } else {
                        const search = win.location.search.replace(/^\?/, '');
                        const pairs = search.split('&');
                        let foundC = null;
                        let foundT = null;
                        for (let i = 0; i < pairs.length; i++) {
                            const part = pairs[i];
                            if (!part) continue;
                            const eq = part.indexOf('=');
                            const k = eq >= 0 ? decodeURIComponent(part.slice(0, eq)) : decodeURIComponent(part);
                            const v = eq >= 0 ? decodeURIComponent(part.slice(eq + 1)) : '';
                            if (k === 'commentId') foundC = v;
                            if (k === 'threadId') foundT = v;
                        }
                        if (foundC && foundT) {
                            commentId = String(foundC).trim();
                            threadId = String(foundT).trim();
                            fromOptions = false;
                        }
                    }
                } catch (_) {}
            }
        }

        if (commentId && threadId) {
            return {
                commentId: commentId,
                threadId: threadId,
                fromOptions: fromOptions
            };
        }
        return null;
    }

    /**
     * Removes commentId and threadId from URL query parameters via replaceState.
     * Preserves unrelated query parameters and hash.
     *
     * @param {Document} [doc]
     */
    function scrubDeepLinkParams(doc) {
        const win = resolveWindow(doc);
        if (!win || !win.location || !win.history || typeof win.history.replaceState !== 'function') {
            return;
        }
        try {
            const search = win.location.search || '';
            if (!search) return;

            let newSearch = '';
            if (typeof URLSearchParams === 'function') {
                const params = new URLSearchParams(search);
                const c = params.get('commentId');
                const t = params.get('threadId');
                if (!c || !t || !c.trim() || !t.trim()) {
                    return;
                }
                params.delete('commentId');
                params.delete('threadId');
                newSearch = params.toString();
            } else {
                const pairs = search.replace(/^\?/, '').split('&');
                let foundC = false;
                let foundT = false;
                for (let i = 0; i < pairs.length; i++) {
                    const part = pairs[i];
                    if (!part) continue;
                    const eq = part.indexOf('=');
                    const k = eq >= 0 ? decodeURIComponent(part.slice(0, eq)) : decodeURIComponent(part);
                    const v = eq >= 0 ? decodeURIComponent(part.slice(eq + 1)) : '';
                    if (k === 'commentId' && v.trim()) foundC = true;
                    if (k === 'threadId' && v.trim()) foundT = true;
                }
                if (!foundC || !foundT) {
                    return;
                }
                const remaining = [];
                for (let i = 0; i < pairs.length; i++) {
                    const part = pairs[i];
                    if (!part) continue;
                    const eq = part.indexOf('=');
                    const k = eq >= 0 ? decodeURIComponent(part.slice(0, eq)) : decodeURIComponent(part);
                    if (k !== 'commentId' && k !== 'threadId') {
                        remaining.push(part);
                    }
                }
                newSearch = remaining.join('&');
            }

            const pathname = win.location.pathname || '';
            const hash = win.location.hash || '';
            const newUrl = pathname + (newSearch ? '?' + newSearch : '') + hash;

            win.history.replaceState(win.history.state, '', newUrl);
        } catch (_) {}
    }

    /**
     * Clears current highlight on active comment element.
     */
    function clearHighlight() {
        if (highlightTimer) {
            clearTimeout(highlightTimer);
            highlightTimer = null;
        }
        if (highlightedElement) {
            if (highlightedElement.classList && typeof highlightedElement.classList.remove === 'function') {
                highlightedElement.classList.remove('is-restored-target');
            }
            highlightedElement = null;
        }
    }

    /**
     * Applies restored target highlight to target element.
     *
     * @param {Element} targetEl
     */
    function applyHighlight(targetEl) {
        if (!targetEl) return;
        clearHighlight();

        highlightedElement = targetEl;
        if (targetEl.classList && typeof targetEl.classList.add === 'function') {
            targetEl.classList.add('is-restored-target');
        }

        if (typeof targetEl.scrollIntoView === 'function') {
            try {
                targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
            } catch (_) {
                targetEl.scrollIntoView();
            }
        }

        const hasTabIndex = typeof targetEl.hasAttribute === 'function'
            ? targetEl.hasAttribute('tabindex')
            : (targetEl.getAttribute && targetEl.getAttribute('tabindex') !== null);

        if (!hasTabIndex) {
            if (typeof targetEl.setAttribute === 'function') {
                targetEl.setAttribute('tabindex', '-1');
            } else {
                targetEl.tabIndex = -1;
            }
        }

        if (typeof targetEl.focus === 'function') {
            try {
                targetEl.focus();
            } catch (_) {}
        }

        highlightTimer = setTimeout(function () {
            if (highlightedElement === targetEl) {
                if (targetEl.classList && typeof targetEl.classList.remove === 'function') {
                    targetEl.classList.remove('is-restored-target');
                }
                highlightedElement = null;
            }
            highlightTimer = null;
        }, 2500);
    }

    /**
     * Falls back smoothly to chapter comments discussion container.
     */
    function fallbackToContainer() {
        const { sectionEl } = getElements();
        if (sectionEl) {
            if (typeof sectionEl.scrollIntoView === 'function') {
                try {
                    sectionEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
                } catch (_) {
                    sectionEl.scrollIntoView();
                }
            }
            if (typeof sectionEl.focus === 'function') {
                try {
                    sectionEl.focus();
                } catch (_) {}
            }
        }
    }

    /**
     * Resolves and focuses exact comment or reply in bottom discussion feed.
     *
     * @param {Object} deepLink
     * @param {string} deepLink.commentId
     * @param {string} deepLink.threadId
     * @param {Document} doc
     * @param {number} [token]
     * @returns {Promise<void>}
     */
    async function resolveDeepLink(deepLink, doc, token) {
        if (!deepLink || !doc || !currentChapterId) {
            return;
        }
        const strCommentId = String(deepLink.commentId).trim();
        const strThreadId = String(deepLink.threadId).trim();
        if (!strCommentId || !strThreadId) {
            return;
        }

        const targetChapterId = currentChapterId;
        let isStale = false;

        try {
            const { listEl } = getElements();
            if (!listEl) {
                return;
            }

            // 1. Check if the thread is already in currentItems / rendered DOM
            const existingItem = currentItems.find(function (it) {
                return it && String(it.rootCommentId || it.id) === strThreadId;
            });
            const threadCard = findThreadCard(listEl, strThreadId);

            if (threadCard && existingItem) {
                if (strCommentId === strThreadId) {
                    const rootEl = threadCard.querySelector('.novel-comment--root[data-comment-id="' + strCommentId + '"]')
                        || threadCard.querySelector('[data-comment-id="' + strCommentId + '"]')
                        || threadCard;
                    applyHighlight(rootEl);
                    return;
                }

                let replyEl = threadCard.querySelector('.novel-comment--reply[data-comment-id="' + strCommentId + '"]')
                    || threadCard.querySelector('[data-reply-id="' + strCommentId + '"]');
                if (replyEl) {
                    applyHighlight(replyEl);
                    return;
                }

                const replies = Array.isArray(existingItem.replies) ? existingItem.replies : [];
                const repIdx = replies.findIndex(function (r) {
                    return r && String(r.id) === strCommentId;
                });

                if (repIdx >= 0) {
                    const targetRevealCount = Math.max(repIdx + 1, INITIAL_VISIBLE_REPLIES);
                    const newThreadCard = renderThread(existingItem, doc, targetRevealCount);
                    if (newThreadCard && threadCard.parentNode) {
                        threadCard.parentNode.replaceChild(newThreadCard, threadCard);
                        replyEl = newThreadCard.querySelector('.novel-comment--reply[data-comment-id="' + strCommentId + '"]')
                            || newThreadCard.querySelector('[data-reply-id="' + strCommentId + '"]');
                        if (replyEl) {
                            applyHighlight(replyEl);
                            return;
                        }
                    }
                }

                fallbackToContainer();
                return;
            }

            // 2. Thread is NOT in current page. Fetch from GET /api/novel/chapters/{chapterId}/comments/{threadId}/thread
            const fetchFn = (typeof injectedFetch === 'function')
                ? injectedFetch
                : (typeof window !== 'undefined' && typeof window.fetch === 'function')
                    ? window.fetch.bind(window)
                    : (typeof fetch === 'function') ? fetch : null;

            if (!fetchFn) {
                fallbackToContainer();
                return;
            }

            const threadUrl = '/api/novel/chapters/' + encodeURIComponent(currentChapterId) +
                '/comments/' + encodeURIComponent(strThreadId) + '/thread';

            try {
                const res = await fetchFn(threadUrl);
                if ((token && token !== loadToken) || targetChapterId !== currentChapterId) {
                    isStale = true;
                    return;
                }
                if (!res || !res.ok) {
                    fallbackToContainer();
                    return;
                }

                const data = await res.json();
                if ((token && token !== loadToken) || targetChapterId !== currentChapterId) {
                    isStale = true;
                    return;
                }
                if (!data || !data.root) {
                    fallbackToContainer();
                    return;
                }

                const rootComment = data.root;
                if (rootComment.tombstone === true || rootComment.status === 'DELETED') {
                    fallbackToContainer();
                    return;
                }

                const replies = Array.isArray(data.replies) ? data.replies : [];
                const threadItem = {
                    rootCommentId: rootComment.id,
                    id: rootComment.id,
                    author: rootComment.author,
                    authorUserId: rootComment.authorUserId,
                    body: rootComment.body,
                    tombstone: Boolean(rootComment.tombstone),
                    status: rootComment.tombstone ? 'DELETED' : 'ACTIVE',
                    createdAt: rootComment.createdAt,
                    updatedAt: rootComment.updatedAt,
                    edited: isCommentEdited(rootComment),
                    canEdit: Boolean(rootComment.canEdit),
                    canDelete: Boolean(rootComment.canDelete),
                    anchorStatus: rootComment.anchorStatus || 'NONE',
                    blockKey: rootComment.blockKey || null,
                    passageExcerpt: rootComment.passageExcerpt || null,
                    replyCount: replies.length,
                    replies: replies
                };

                // Duplicate safety: verify thread was not inserted during fetch
                let existingCard = findThreadCard(listEl, strThreadId);
                if (!existingCard) {
                    let initialRevealed = INITIAL_VISIBLE_REPLIES;
                    if (strCommentId !== strThreadId && replies.length > 0) {
                        const repIdx = replies.findIndex(function (r) {
                            return r && String(r.id) === strCommentId;
                        });
                        if (repIdx >= 0) {
                            initialRevealed = Math.max(INITIAL_VISIBLE_REPLIES, repIdx + 1);
                        }
                    }

                    const newCard = renderThread(threadItem, doc, initialRevealed);
                    if (newCard) {
                        const emptyEl = listEl.querySelector('.novel-chapter-comments-empty');
                        if (emptyEl && emptyEl.parentNode) {
                            emptyEl.parentNode.removeChild(emptyEl);
                        }

                        if (listEl.firstChild) {
                            listEl.insertBefore(newCard, listEl.firstChild);
                        } else {
                            listEl.appendChild(newCard);
                        }

                        currentItems.unshift(threadItem);
                        currentStatus = 'populated';
                        const { countEl } = getElements();
                        if (countEl) {
                            countEl.textContent = formatCommentCount(getActiveCommentCount(currentItems));
                        }
                        existingCard = newCard;
                    }
                }

                if (!existingCard) {
                    fallbackToContainer();
                    return;
                }

                let targetEl = null;
                if (strCommentId === strThreadId) {
                    targetEl = existingCard.querySelector('.novel-comment--root[data-comment-id="' + strCommentId + '"]')
                        || existingCard.querySelector('[data-comment-id="' + strCommentId + '"]')
                        || existingCard;
                } else {
                    targetEl = existingCard.querySelector('.novel-comment--reply[data-comment-id="' + strCommentId + '"]')
                        || existingCard.querySelector('[data-reply-id="' + strCommentId + '"]');
                }

                if (targetEl) {
                    applyHighlight(targetEl);
                } else {
                    fallbackToContainer();
                }
            } catch (_) {
                if ((token && token !== loadToken) || targetChapterId !== currentChapterId) {
                    isStale = true;
                    return;
                }
                fallbackToContainer();
            }
        } finally {
            if (!isStale && (!token || token === loadToken) && targetChapterId === currentChapterId) {
                scrubDeepLinkParams(doc);
            }
        }
    }

    /**
     * Calculates total loaded active comment count (roots + active replies).
     * Tombstones contribute 0 to the count.
     *
     * @param {Array} items
     * @returns {number}
     */
    function getActiveCommentCount(items) {
        if (!Array.isArray(items)) {
            return 0;
        }
        return items.reduce(function (sum, it) {
            if (!it) return sum;
            if (it.tombstone === true || it.status === 'DELETED') {
                return sum;
            }
            let repliesActive = 0;
            if (Number.isSafeInteger(Number(it.replyCount)) && it.replyCount !== null && it.replyCount !== undefined) {
                repliesActive = Number(it.replyCount);
            } else if (Array.isArray(it.replies)) {
                repliesActive = it.replies.filter(function (r) {
                    return r && r.tombstone !== true && r.status !== 'DELETED';
                }).length;
            }
            return sum + 1 + repliesActive;
        }, 0);
    }

    /**
     * Deduplicates incoming root items against already accepted items.
     * Preserves relative order of non-duplicate roots.
     *
     * @param {Array} existingItems
     * @param {Array} newItems
     * @returns {Array}
     */
    function deduplicateRoots(existingItems, newItems) {
        const existingKeys = new Set();
        if (Array.isArray(existingItems)) {
            for (let i = 0; i < existingItems.length; i++) {
                const it = existingItems[i];
                if (!it) continue;
                const key = it.rootCommentId || it.id;
                if (key != null) {
                    existingKeys.add(String(key));
                }
            }
        }

        const accepted = [];
        if (Array.isArray(newItems)) {
            for (let j = 0; j < newItems.length; j++) {
                const it = newItems[j];
                if (!it) continue;
                const key = it.rootCommentId || it.id;
                if (key != null && existingKeys.has(String(key))) {
                    continue;
                }
                accepted.push(it);
                if (key != null) {
                    existingKeys.add(String(key));
                }
            }
        }
        return accepted;
    }

    /**
     * Resolves chapter ID from section or DOM body.
     *
     * @param {Document} doc
     * @param {Element|null} sectionEl
     * @returns {string|null}
     */
    function resolveChapterId(doc, sectionEl) {
        if (sectionEl) {
            const id = sectionEl.getAttribute('data-chapter-id');
            if (id && id.trim()) {
                return id.trim();
            }
        }
        if (doc && typeof doc.querySelector === 'function') {
            const bodyEl = doc.querySelector('.novel-reader-chapter-body[data-chapter-id]');
            if (bodyEl) {
                const id = bodyEl.getAttribute('data-chapter-id');
                if (id && id.trim()) {
                    return id.trim();
                }
            }
            const fallbackEl = doc.querySelector('[data-chapter-id]');
            if (fallbackEl) {
                const id = fallbackEl.getAttribute('data-chapter-id');
                if (id && id.trim()) {
                    return id.trim();
                }
            }
        }
        return null;
    }

    /**
     * Resolves the CommentPresentation module.
     *
     * @returns {Object|null}
     */
    function resolveCommentPresentation() {
        if (injectedCommentPresentation !== undefined) {
            return injectedCommentPresentation;
        }
        if (typeof window !== 'undefined') {
            const pres = window.CommentPresentation || (window.KiemLai && window.KiemLai.CommentPresentation);
            if (pres) return pres;
        }
        if (typeof globalThis !== 'undefined') {
            const pres = globalThis.CommentPresentation || (globalThis.KiemLai && globalThis.KiemLai.CommentPresentation);
            if (pres) return pres;
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/comment-presentation.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Resolves the CommentReportModal module or singleton instance.
     *
     * @returns {Object|null}
     */
    function resolveReportModal() {
        if (injectedReportModal) {
            return injectedReportModal;
        }
        if (typeof window !== 'undefined') {
            const modal = window.CommentReportModal || (window.KiemLai && window.KiemLai.CommentReportModal);
            if (modal) return modal;
        }
        if (typeof globalThis !== 'undefined') {
            const modal = globalThis.CommentReportModal || (globalThis.KiemLai && globalThis.KiemLai.CommentReportModal);
            if (modal) return modal;
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/comment-report-modal.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Checks whether the current user session is authenticated.
     *
     * @param {Document} [doc]
     * @param {Element} [sectionEl]
     * @returns {boolean}
     */
    function isUserAuthenticated(doc, sectionEl) {
        if (typeof injectedAuthenticated === 'boolean') {
            return injectedAuthenticated;
        }
        const sec = sectionEl || (getElements().sectionEl);
        if (sec) {
            const authAttr = (typeof sec.getAttribute === 'function' ? sec.getAttribute('data-authenticated') : null) ||
                (sec.dataset && sec.dataset.authenticated);
            if (authAttr === 'true' || authAttr === true) {
                return true;
            }
        }
        return false;
    }

    /**
     * Redirects unauthenticated guest to login URL safely.
     *
     * @param {Document} [doc]
     * @param {Element} [sectionEl]
     */
    function redirectToLogin(doc, sectionEl) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const sec = sectionEl || (getElements().sectionEl);
        let destination = '/login';
        if (sec && typeof sec.getAttribute === 'function') {
            const customUrl = sec.getAttribute('data-login-url');
            if (customUrl && customUrl.trim()) {
                destination = customUrl.trim();
            }
        }
        const win = (d && d.defaultView) ? d.defaultView : (typeof window !== 'undefined' ? window : null);
        const currentHref = (win && win.location) ? (win.location.pathname + (win.location.search || '') + '#' + SECTION_ID) : '';
        if (destination === '/login' && currentHref) {
            destination = '/login?returnTo=' + encodeURIComponent(currentHref);
        }
        if (win && win.location) {
            win.location.href = destination;
        }
    }

    /**
     * Opens the shared CommentReportModal for a target comment.
     *
     * @param {string} commentId
     * @param {Element} [triggerEl]
     * @param {Document} [doc]
     * @returns {boolean} true if modal opened, false otherwise
     */
    function openReportModal(commentId, triggerEl, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const { sectionEl } = getElements();
        const chapterId = currentChapterId || resolveChapterId(d, sectionEl);
        if (!chapterId || !commentId) {
            return false;
        }

        if (!isUserAuthenticated(d, sectionEl)) {
            redirectToLogin(d, sectionEl);
            return false;
        }

        const modal = resolveReportModal();
        if (!modal || typeof modal.open !== 'function') {
            return false;
        }

        const cleanCommentId = String(commentId).trim();
        const cleanChapterId = String(chapterId).trim();
        const submitUrl = '/api/novel/chapters/' + encodeURIComponent(cleanChapterId) + '/comments/' + encodeURIComponent(cleanCommentId) + '/reports';

        return modal.open({
            commentId: cleanCommentId,
            submitUrl: submitUrl,
            contextLabel: 'novel-chapter',
            triggerEl: triggerEl || null,
            onSuccess: function (result) {
                if (triggerEl) {
                    triggerEl.textContent = 'Đã báo cáo';
                    triggerEl.disabled = true;
                    if (typeof triggerEl.setAttribute === 'function') {
                        triggerEl.setAttribute('title', 'Bạn đã gửi báo cáo cho bình luận này');
                    }
                }
                const { statusEl: curStatusEl } = getElements();
                if (curStatusEl) {
                    clearElement(curStatusEl);
                    const msgDiv = d.createElement('div');
                    msgDiv.className = 'novel-chapter-comments-status-success';
                    const msgP = d.createElement('p');
                    msgP.className = 'novel-chapter-comments-status-text';
                    msgP.textContent = 'Đã gửi báo cáo. Cảm ơn bạn đã phản hồi.';
                    msgDiv.appendChild(msgP);
                    curStatusEl.appendChild(msgDiv);
                }
            }
        });
    }

    /**
     * Determines whether origin navigation is available for given anchor status and block key.
     * Available for CURRENT and RELOCATED when blockKey is non-null and non-empty.
     *
     * @param {string|null} anchorStatus
     * @param {string|null} blockKey
     * @returns {boolean}
     */
    function isOriginNavigable(anchorStatus, blockKey) {
        if (!blockKey || typeof blockKey !== 'string' || !blockKey.trim()) {
            return false;
        }
        const status = (typeof anchorStatus === 'string') ? anchorStatus.trim().toUpperCase() : '';
        return status === 'CURRENT' || status === 'RELOCATED';
    }

    /**
     * Closes the active overflow menu and optionally restores focus to its trigger button.
     * Delegates entirely to the shared CommentPresentation primitive.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeActiveMenu(restoreFocus) {
        const presentation = resolveCommentPresentation();
        if (presentation && typeof presentation.closeActiveMenu === 'function') {
            presentation.closeActiveMenu(restoreFocus);
        }
    }

    /**
     * Returns the currently active open menu state descriptor.
     * Delegates entirely to the shared CommentPresentation primitive.
     *
     * @returns {Object|null}
     */
    function getActiveOpenMenu() {
        const presentation = resolveCommentPresentation();
        if (presentation && typeof presentation.getActiveOpenMenu === 'function') {
            return presentation.getActiveOpenMenu();
        }
        return null;
    }

    /**
     * Handles document click events for novel business actions (view-origin and report).
     * Generic outside-click menu dismissal is handled exclusively by CommentPresentation.
     *
     * @param {Event} e
     */
    function onDocumentClick(e) {
        const target = (e && e.target) ? e.target : null;
        if (target) {
            let originBtn = null;
            if (typeof target.closest === 'function') {
                originBtn = target.closest('[data-action="view-origin"]');
            } else if (target.getAttribute && target.getAttribute('data-action') === 'view-origin') {
                originBtn = target;
            }
            if (originBtn) {
                if (e && typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                closeActiveMenu(false);
                const blockKey = originBtn.getAttribute('data-block-key');
                const rootId = originBtn.getAttribute('data-root-id');
                if (blockKey) {
                    openOriginDiscussion(blockKey, rootId, currentDoc);
                }
                return;
            }

            let reportBtn = null;
            if (typeof target.closest === 'function') {
                reportBtn = target.closest('.novel-comment-report-btn') || target.closest('[data-action="report"]');
            } else if (target.getAttribute && target.getAttribute('data-action') === 'report') {
                reportBtn = target;
            }
            if (reportBtn) {
                closeActiveMenu(false);
                const { sectionEl } = getElements();
                if (!sectionEl || (typeof sectionEl.contains === 'function' && sectionEl.contains(reportBtn))) {
                    if (e && typeof e.preventDefault === 'function') e.preventDefault();
                    const cid = reportBtn.getAttribute('data-comment-id');
                    if (cid) {
                        if (!isUserAuthenticated(currentDoc, sectionEl)) {
                            redirectToLogin(currentDoc, sectionEl);
                        } else {
                            openReportModal(cid, reportBtn, currentDoc);
                        }
                        return;
                    }
                }
            }
        }
    }

    /**
     * Opens the canonical block discussion drawer targeting the root discussion thread.
     * Reuses reader-block-discussion-restore or injected options.openDiscussionTarget.
     * If the canonical restore bridge is unavailable, safely no-ops without weaker fallback dispatch.
     *
     * @param {string} blockKey
     * @param {string} rootCommentId
     * @param {Document} [doc]
     */
    function openOriginDiscussion(blockKey, rootCommentId, doc) {
        const documentRef = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!blockKey || !documentRef) {
            return;
        }

        // 1. Injected handler takes precedence (for isolated testing or explicit orchestrator)
        if (typeof injectedOpenDiscussionTarget === 'function') {
            injectedOpenDiscussionTarget({
                chapterId: currentChapterId,
                blockKey: blockKey,
                threadId: rootCommentId
            }, documentRef);
            return;
        }

        const win = (documentRef.defaultView) || (typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
        const restoreModule = (win && (win.NovelReaderBlockDiscussionRestore || (win.KiemLai && win.KiemLai.NovelReaderBlockDiscussionRestore))) ||
            (typeof NovelReaderBlockDiscussionRestore !== 'undefined' ? NovelReaderBlockDiscussionRestore : null);

        // 2. Canonical restore bridge
        if (restoreModule && typeof restoreModule.openDiscussionTarget === 'function') {
            restoreModule.openDiscussionTarget({
                chapterId: currentChapterId,
                blockKey: blockKey,
                threadId: rootCommentId
            }, documentRef, win);
            return;
        }

        // If the canonical restore bridge is unavailable: safely no-op
    }

    /**
     * Constructs normalized overflow action descriptors for a novel comment.
     * Consumer owns domain capabilities (originNavigable, canEdit, canDelete, canReport, isEdited).
     *
     * @param {Object} options
     * @returns {Array}
     */
    function buildOverflowActionDescriptors(options) {
        if (!options || typeof options !== 'object') {
            return [];
        }
        const hasOrigin = Boolean(options.originNavigable && options.blockKey);
        const hasHistory = Boolean(options.isEdited);
        const hasEdit = Boolean(options.canEdit);
        const hasDelete = Boolean(options.canDelete);
        const hasReport = Boolean(options.canReport);

        if (!hasOrigin && !hasHistory && !hasEdit && !hasDelete && !hasReport) {
            return [];
        }

        const descriptors = [];

        // 1. View Origin
        if (hasOrigin) {
            descriptors.push({
                key: 'view-origin',
                label: 'Xem bình luận gốc',
                className: 'novel-comment-menu-item',
                attributes: {
                    'data-action': 'view-origin',
                    'data-block-key': String(options.blockKey),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 2. View Revisions
        if (hasHistory) {
            descriptors.push({
                key: 'view-revisions',
                label: 'Xem lịch sử chỉnh sửa',
                className: 'novel-comment-menu-item',
                attributes: {
                    'data-action': 'view-revisions',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 3. Edit
        if (hasEdit) {
            descriptors.push({
                key: 'edit',
                label: 'Chỉnh sửa',
                className: 'novel-comment-menu-item novel-comment-edit-btn',
                attributes: {
                    'data-action': 'edit',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 4. Delete
        if (hasDelete) {
            descriptors.push({
                key: 'delete',
                label: 'Xóa',
                danger: true,
                className: 'novel-comment-menu-item novel-comment-delete-btn',
                attributes: {
                    'data-action': 'delete',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 5. Report (with separator if preceded by other items)
        if (hasReport) {
            const hasPreceding = hasOrigin || hasHistory || hasEdit || hasDelete;
            descriptors.push({
                key: 'report',
                label: 'Báo cáo',
                danger: true,
                separatorBefore: hasPreceding,
                className: 'novel-comment-menu-item novel-comment-report-btn',
                attributes: {
                    'data-action': 'report',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        return descriptors;
    }

    /**
     * Constructs the three-dot overflow actions menu element by delegating to CommentPresentation.
     *
     * @param {Object|Array|string} opts
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function createActionsMenu(opts, doc) {
        let options = opts;
        let documentRef = doc;
        if (typeof opts === 'string' || (opts === null && typeof doc === 'string')) {
            const legacyBlockKey = opts;
            const legacyRootId = arguments[1];
            documentRef = arguments[2];
            options = {
                originNavigable: true,
                blockKey: legacyBlockKey,
                rootCommentId: legacyRootId,
                commentId: legacyRootId,
                canEdit: false,
                canDelete: false,
                canReport: false
            };
        }
        if (!documentRef) {
            documentRef = currentDoc || (typeof document !== 'undefined' ? document : null);
        }

        const descriptors = Array.isArray(options)
            ? options
            : buildOverflowActionDescriptors(options);

        if (!descriptors || descriptors.length === 0) {
            return null;
        }

        const presentation = resolveCommentPresentation();
        if (presentation && typeof presentation.renderActionsMenu === 'function') {
            return presentation.renderActionsMenu({
                items: descriptors,
                legacyPrefix: 'novel-comment'
            }, documentRef);
        }

        return null;
    }

    /**
     * Renders a single discussion thread card (root comment + visible replies).
     *
     * @param {Object} item ChapterDiscussionFeedItemDTO
     * @param {Document} doc
     * @returns {Element}
     */
    /**
     * Renders an individual reply card (active or tombstone).
     *
     * @param {Object} reply
     * @param {Object} rootItem
     * @param {Array} allReplies
     * @param {Object} commentLookup
     * @param {Document} doc
     * @returns {Element}
     */
    function renderReply(reply, rootItem, allReplies, commentLookup, doc) {
        const presentation = resolveCommentPresentation();
        if (!presentation || typeof presentation.renderComment !== 'function') {
            return null;
        }

        const rootId = rootItem.rootCommentId || rootItem.id;
        const strRootId = rootId ? String(rootId) : '';
        const isTombstone = reply.tombstone === true || reply.status === 'DELETED';
        const repAuthorUserId = (reply.author && reply.author.userId) || reply.authorUserId;

        let tombstoneContentNodes = null;
        if (isTombstone) {
            const contextChildDisplayName = (reply.id)
                ? resolveTombstoneContextChildDisplayName(allReplies, reply.id)
                : null;

            if (contextChildDisplayName) {
                const prefixSpan = doc.createElement('span');
                prefixSpan.textContent = 'Bình luận mà ';

                const mentionSpan = doc.createElement('span');
                mentionSpan.className = 'novel-comment-reply-mention';
                mentionSpan.textContent = '@' + contextChildDisplayName;

                const suffixSpan = doc.createElement('span');
                suffixSpan.textContent = ' phản hồi đã bị xóa.';

                tombstoneContentNodes = [prefixSpan, mentionSpan, suffixSpan];
            } else {
                tombstoneContentNodes = 'Bình luận đã bị xóa.';
            }
        }

        // Resolve immediate parent for Wattpad-style nested reply mention
        let parentDisplayName = null;
        if (!isTombstone) {
            const parentId = (reply.parentCommentId != null) ? String(reply.parentCommentId).trim() : '';
            if (parentId && parentId !== strRootId) {
                const immediateParent = commentLookup ? commentLookup[parentId] : null;
                if (immediateParent) {
                    const isParentTombstone = immediateParent.tombstone === true || immediateParent.status === 'DELETED';
                    if (!isParentTombstone) {
                        const parentAuthor = (immediateParent.author && typeof immediateParent.author === 'object')
                            ? immediateParent.author
                            : null;
                        const rawParentName = (parentAuthor && typeof parentAuthor.displayName === 'string')
                            ? parentAuthor.displayName.trim()
                            : '';
                        if (rawParentName) {
                            parentDisplayName = rawParentName;
                        }
                    }
                }
            }
        }

        const isReplyEdited = !isTombstone && isCommentEdited(reply);
        const replyOriginNavigable = !isTombstone && isOriginNavigable(rootItem.anchorStatus, rootItem.blockKey);
        const canEditReply = !isTombstone && Boolean(reply.canEdit);
        const canDeleteReply = !isTombstone && Boolean(reply.canDelete);
        const canReportReply = !isTombstone && !canEditReply && !canDeleteReply;

        const repAuthorName = (reply.author && typeof reply.author.displayName === 'string')
            ? reply.author.displayName.trim()
            : '';

        const replyAttrs = {};
        if (reply.id) {
            replyAttrs['data-reply-id'] = String(reply.id);
            replyAttrs['data-comment-id'] = String(reply.id);
        }
        if (repAuthorUserId) {
            replyAttrs['data-author-user-id'] = String(repAuthorUserId);
        }

        const overflowDescriptors = !isTombstone ? buildOverflowActionDescriptors({
            originNavigable: replyOriginNavigable,
            blockKey: rootItem.blockKey,
            rootCommentId: strRootId,
            commentId: String(reply.id),
            isEdited: isReplyEdited,
            canEdit: canEditReply,
            canDelete: canDeleteReply,
            canReport: canReportReply
        }) : [];

        const descriptor = {
            id: reply.id,
            tag: 'article',
            legacyPrefix: 'novel-comment',
            className: 'novel-comment--reply',
            attributes: replyAttrs,
            tombstone: isTombstone,
            tombstoneContent: tombstoneContentNodes,
            author: reply.author,
            createdAt: reply.createdAt,
            edited: isReplyEdited,
            body: function (bodyEl, d) {
                if (parentDisplayName) {
                    const mentionSpan = d.createElement('span');
                    mentionSpan.className = 'novel-comment-reply-mention';
                    mentionSpan.textContent = '@' + parentDisplayName;

                    const bodyTextSpan = d.createElement('span');
                    bodyTextSpan.className = 'novel-comment-reply-body-text';
                    bodyTextSpan.textContent = reply.body || '';

                    bodyEl.appendChild(mentionSpan);
                    bodyEl.appendChild(bodyTextSpan);
                } else {
                    bodyEl.textContent = reply.body || '';
                }
            },
            overflowActions: overflowDescriptors.length > 0 ? overflowDescriptors : null,
            primaryActions: (!isTombstone && reply.id) ? [
                {
                    key: 'reply',
                    label: 'Phản hồi',
                    className: 'novel-comment-reply-btn',
                    attributes: {
                        'data-action': 'reply',
                        'data-comment-id': String(reply.id),
                        'data-root-id': String(strRootId),
                        ...(repAuthorName ? { 'data-author-name': repAuthorName } : {})
                    }
                }
            ] : []
        };

        const replyEl = presentation.renderComment(descriptor, doc);
        return replyEl || null;
    }

    /**
     * Renders a single discussion thread card (root comment + visible replies with progressive reveal).
     *
     * @param {Object} item ChapterDiscussionFeedItemDTO
     * @param {Document} doc
     * @param {number} [initialRevealedCount]
     * @returns {Element}
     */
    function renderThread(item, doc, initialRevealedCount) {
        if (!item || item.tombstone === true || item.status === 'DELETED') {
            return null;
        }

        const presentation = resolveCommentPresentation();
        if (!presentation || typeof presentation.renderComment !== 'function') {
            return null;
        }

        const rootId = item.rootCommentId || item.id;
        const threadCard = doc.createElement('article');
        threadCard.className = 'novel-block-discussion-thread';
        if (rootId) {
            threadCard.setAttribute('data-root-id', String(rootId));
        }

        const isRootEdited = isCommentEdited(item);
        const originNavigable = isOriginNavigable(item.anchorStatus, item.blockKey);
        const canEditRoot = Boolean(item.canEdit);
        const canDeleteRoot = Boolean(item.canDelete);
        const canReportRoot = !canEditRoot && !canDeleteRoot;
        const authorUserId = (item.author && item.author.userId) || item.authorUserId;
        const authorDisplayName = (item.author && typeof item.author.displayName === 'string')
            ? item.author.displayName.trim()
            : '';

        const rootAttrs = {};
        if (rootId) {
            rootAttrs['data-comment-id'] = String(rootId);
        }
        if (authorUserId) {
            rootAttrs['data-author-user-id'] = String(authorUserId);
        }

        const overflowDescriptors = buildOverflowActionDescriptors({
            originNavigable: originNavigable,
            blockKey: item.blockKey,
            rootCommentId: rootId,
            commentId: rootId,
            isEdited: isRootEdited,
            canEdit: canEditRoot,
            canDelete: canDeleteRoot,
            canReport: canReportRoot
        });

        const rootDescriptor = {
            id: rootId,
            tag: 'div',
            legacyPrefix: 'novel-comment',
            className: 'novel-comment--root',
            attributes: rootAttrs,
            tombstone: false,
            tombstoneContent: 'Bình luận đã bị xóa.',
            author: item.author,
            createdAt: item.createdAt,
            edited: isRootEdited,
            body: item.body || '',
            overflowActions: overflowDescriptors.length > 0 ? overflowDescriptors : null,
            primaryActions: rootId ? [
                {
                    key: 'reply',
                    label: 'Phản hồi',
                    className: 'novel-comment-reply-btn',
                    attributes: {
                        'data-action': 'reply',
                        'data-comment-id': String(rootId),
                        'data-root-id': String(rootId),
                        ...(authorDisplayName ? { 'data-author-name': authorDisplayName } : {})
                    }
                }
            ] : []
        };

        const rootEl = presentation.renderComment(rootDescriptor, doc);
        if (!rootEl) {
            return null;
        }

        threadCard.appendChild(rootEl);

        // Replies container with per-thread progressive reveal
        const replies = Array.isArray(item.replies) ? item.replies : [];
        if (replies.length > 0) {
            const repliesContainer = doc.createElement('div');
            repliesContainer.className = 'novel-comment-replies';
            repliesContainer.setAttribute('role', 'group');
            repliesContainer.setAttribute('aria-label', 'Phản hồi');

            // Construct in-memory comment lookup for immediate parent attribution
            const commentLookup = Object.create(null);
            if (rootId) {
                commentLookup[String(rootId)] = {
                    id: rootId,
                    author: item.author,
                    tombstone: false
                };
            }
            for (let k = 0; k < replies.length; k++) {
                const rep = replies[k];
                if (rep && rep.id) {
                    commentLookup[String(rep.id)] = rep;
                }
            }

            let revealedCount = typeof initialRevealedCount === 'number'
                ? Math.min(replies.length, initialRevealedCount)
                : Math.min(replies.length, INITIAL_VISIBLE_REPLIES);

            for (let j = 0; j < revealedCount; j++) {
                const reply = replies[j];
                if (!reply) continue;
                const replyEl = renderReply(reply, item, replies, commentLookup, doc);
                if (replyEl) {
                    repliesContainer.appendChild(replyEl);
                }
            }

            if (replies.length > revealedCount) {
                const moreContainer = doc.createElement('div');
                moreContainer.className = 'novel-comment-replies-more';

                const moreBtn = doc.createElement('button');
                moreBtn.type = 'button';
                moreBtn.className = 'novel-comment-replies-more-btn';

                const updateBtnLabel = function () {
                    const remaining = replies.length - revealedCount;
                    moreBtn.textContent = 'Xem thêm ' + remaining + ' phản hồi';
                };
                updateBtnLabel();

                moreBtn.addEventListener('click', function (e) {
                    if (e && typeof e.preventDefault === 'function') {
                        e.preventDefault();
                    }
                    const nextCount = Math.min(revealedCount + REPLY_REVEAL_BATCH_SIZE, replies.length);
                    for (let r = revealedCount; r < nextCount; r++) {
                        const rep = replies[r];
                        if (!rep) continue;
                        const repEl = renderReply(rep, item, replies, commentLookup, doc);
                        if (repEl) {
                            if (typeof repliesContainer.insertBefore === 'function') {
                                repliesContainer.insertBefore(repEl, moreContainer);
                            } else {
                                repliesContainer.appendChild(repEl);
                            }
                        }
                    }
                    revealedCount = nextCount;
                    if (revealedCount >= replies.length) {
                        if (moreContainer.parentNode && typeof moreContainer.parentNode.removeChild === 'function') {
                            moreContainer.parentNode.removeChild(moreContainer);
                        }
                    } else {
                        updateBtnLabel();
                    }
                });

                moreContainer.appendChild(moreBtn);
                repliesContainer.appendChild(moreContainer);
            }

            threadCard.appendChild(repliesContainer);
        }

        return threadCard;
    }

    /**
     * Renders root load-more ready state.
     *
     * @param {Element|null} moreEl
     * @param {Document} doc
     */
    function renderMoreReady(moreEl, doc) {
        if (!moreEl) return;
        clearElement(moreEl);
        moreEl.hidden = false;
        moreEl.removeAttribute('hidden');

        const btn = doc.createElement('button');
        btn.type = 'button';
        btn.className = 'novel-chapter-comments-more-btn';
        btn.disabled = false;
        btn.textContent = 'Xem thêm bình luận';
        btn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            loadMore();
        });
        moreEl.appendChild(btn);
    }

    /**
     * Renders root load-more loading state.
     *
     * @param {Element|null} moreEl
     * @param {Document} doc
     */
    function renderMoreLoading(moreEl, doc) {
        if (!moreEl) return;
        clearElement(moreEl);
        moreEl.hidden = false;
        moreEl.removeAttribute('hidden');

        const btn = doc.createElement('button');
        btn.type = 'button';
        btn.className = 'novel-chapter-comments-more-btn is-loading';
        btn.disabled = true;
        btn.setAttribute('aria-busy', 'true');

        const spinner = doc.createElement('span');
        spinner.className = 'novel-chapter-comments-more-spinner';
        spinner.setAttribute('aria-hidden', 'true');

        const text = doc.createElement('span');
        text.textContent = 'Đang tải...';

        btn.appendChild(spinner);
        btn.appendChild(text);
        moreEl.appendChild(btn);
    }

    /**
     * Renders root load-more error state with retry.
     *
     * @param {Element|null} moreEl
     * @param {Document} doc
     */
    function renderMoreError(moreEl, doc) {
        if (!moreEl) return;
        clearElement(moreEl);
        moreEl.hidden = false;
        moreEl.removeAttribute('hidden');

        const errorDiv = doc.createElement('div');
        errorDiv.className = 'novel-chapter-comments-more-error';
        errorDiv.setAttribute('role', 'alert');

        const text = doc.createElement('p');
        text.className = 'novel-chapter-comments-more-error-text';
        text.textContent = 'Không thể tải thêm bình luận.';

        const retryBtn = doc.createElement('button');
        retryBtn.type = 'button';
        retryBtn.className = 'novel-chapter-comments-more-retry-btn';
        retryBtn.textContent = 'Thử lại';
        retryBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            loadMore();
        });

        errorDiv.appendChild(text);
        errorDiv.appendChild(retryBtn);
        moreEl.appendChild(errorDiv);
    }

    /**
     * Hides and clears root load-more container.
     *
     * @param {Element|null} moreEl
     */
    function renderMoreHidden(moreEl) {
        if (!moreEl) return;
        clearElement(moreEl);
        moreEl.hidden = true;
        moreEl.setAttribute('hidden', '');
    }

    /**
     * Renders loading status.
     *
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Element|null} moreEl
     * @param {Document} doc
     */
    function renderLoading(statusEl, listEl, countEl, moreEl, doc) {
        currentStatus = 'loading';
        currentItems = [];
        currentPage = 0;
        hasNext = false;
        isLoadingMore = false;

        renderMoreHidden(moreEl);

        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'true');
        }
        if (statusEl) {
            clearElement(statusEl);
            const loadingDiv = doc.createElement('div');
            loadingDiv.className = 'novel-chapter-comments-loading';
            loadingDiv.setAttribute('role', 'status');
            loadingDiv.setAttribute('aria-live', 'polite');

            const spinner = doc.createElement('span');
            spinner.className = 'novel-chapter-comments-spinner';
            spinner.setAttribute('aria-hidden', 'true');

            const text = doc.createElement('span');
            text.className = 'novel-chapter-comments-loading-text';
            text.textContent = 'Đang tải bình luận...';

            loadingDiv.appendChild(spinner);
            loadingDiv.appendChild(text);
            statusEl.appendChild(loadingDiv);
        }
        if (countEl) {
            countEl.textContent = '';
        }
    }

    /**
     * Dispatches feed replacement lifecycle notification.
     *
     * @param {Document} doc
     */
    function notifyFeedReplacing(doc) {
        if (!doc || typeof doc.dispatchEvent !== 'function') return;
        try {
            if (typeof CustomEvent === 'function') {
                doc.dispatchEvent(new CustomEvent(EVENT_FEED_REPLACING));
            } else {
                const evt = doc.createEvent ? doc.createEvent('CustomEvent') : { type: EVENT_FEED_REPLACING };
                if (evt.initCustomEvent) {
                    evt.initCustomEvent(EVENT_FEED_REPLACING, true, true, {});
                }
                doc.dispatchEvent(evt);
            }
        } catch (_) {}
    }

    /**
     * Dispatches the post-render event kiemlai:chapter-comments-feed-rendered.
     *
     * @param {Document} doc
     * @param {string|null} [chapterId]
     */
    function notifyFeedRendered(doc, chapterId) {
        if (!doc || typeof doc.dispatchEvent !== 'function') return;
        const targetChapter = chapterId || currentChapterId || null;
        try {
            if (typeof CustomEvent === 'function') {
                doc.dispatchEvent(new CustomEvent(EVENT_FEED_RENDERED, {
                    detail: { chapterId: targetChapter }
                }));
            } else {
                const evt = doc.createEvent ? doc.createEvent('CustomEvent') : { type: EVENT_FEED_RENDERED, detail: { chapterId: targetChapter } };
                if (evt.initCustomEvent) {
                    evt.initCustomEvent(EVENT_FEED_RENDERED, true, true, { chapterId: targetChapter });
                }
                doc.dispatchEvent(evt);
            }
        } catch (_) {}
    }

    /**
     * Renders empty comments state.
     *
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Element|null} moreEl
     * @param {Document} doc
     */
    function renderEmpty(statusEl, listEl, countEl, moreEl, doc) {
        notifyFeedReplacing(doc);
        currentStatus = 'empty';
        currentItems = [];
        currentPage = 0;
        hasNext = false;
        isLoadingMore = false;

        renderMoreHidden(moreEl);

        if (statusEl) {
            clearElement(statusEl);
        }
        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');

            const emptyDiv = doc.createElement('div');
            emptyDiv.className = 'novel-chapter-comments-empty';

            const text = doc.createElement('p');
            text.className = 'novel-chapter-comments-empty-text';
            text.textContent = 'Chưa có bình luận nào.';

            emptyDiv.appendChild(text);
            listEl.appendChild(emptyDiv);
        }
        if (countEl) {
            countEl.textContent = formatCommentCount(0);
        }
        notifyFeedRendered(doc, currentChapterId);
    }

    /**
     * Renders populated comments state.
     *
     * @param {Array} items
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Document} doc
     */
    function renderPopulated(items, statusEl, listEl, countEl, doc) {
        notifyFeedReplacing(doc);
        currentStatus = 'populated';
        currentItems = Array.isArray(items) ? items.slice() : [];

        if (statusEl) {
            clearElement(statusEl);
        }
        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');

            for (let i = 0; i < items.length; i++) {
                const item = items[i];
                if (!item) continue;
                const threadCard = renderThread(item, doc);
                if (threadCard) {
                    listEl.appendChild(threadCard);
                }
            }
        }
        if (countEl) {
            countEl.textContent = formatCommentCount(getActiveCommentCount(items));
        }
        notifyFeedRendered(doc, currentChapterId);
    }

    /**
     * Renders error state with retry affordance.
     *
     * @param {string|null} message
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Element|null} moreEl
     * @param {Document} doc
     */
    function renderError(message, statusEl, listEl, countEl, moreEl, doc) {
        currentStatus = 'error';
        currentItems = [];
        currentPage = 0;
        hasNext = false;
        isLoadingMore = false;

        renderMoreHidden(moreEl);

        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');
        }
        if (statusEl) {
            clearElement(statusEl);

            const errorDiv = doc.createElement('div');
            errorDiv.className = 'novel-chapter-comments-error';
            errorDiv.setAttribute('role', 'alert');

            const text = doc.createElement('p');
            text.className = 'novel-chapter-comments-error-text';
            text.textContent = message || 'Không thể tải bình luận. Vui lòng thử lại.';

            const retryBtn = doc.createElement('button');
            retryBtn.type = 'button';
            retryBtn.className = 'novel-chapter-comments-retry-btn';
            retryBtn.textContent = 'Thử lại';
            retryBtn.addEventListener('click', function (e) {
                if (e && typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                fetchFeed();
            });

            errorDiv.appendChild(text);
            errorDiv.appendChild(retryBtn);
            statusEl.appendChild(errorDiv);
        }
        if (countEl) {
            countEl.textContent = '';
        }
    }

    /**
     * Executes the initial feed fetch with token-race protection.
     */
    function fetchFeed() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { statusEl, listEl, countEl, moreEl } = getElements();
        if (!doc || !listEl || !currentChapterId) {
            return;
        }

        const fetchFn = (typeof injectedFetch === 'function')
            ? injectedFetch
            : (typeof window !== 'undefined' && typeof window.fetch === 'function')
                ? window.fetch.bind(window)
                : (typeof fetch === 'function') ? fetch : null;

        if (!fetchFn) {
            if (pendingDeepLink) {
                pendingDeepLink = null;
                fallbackToContainer();
                scrubDeepLinkParams(doc);
            }
            renderError('Trình duyệt không hỗ trợ tải dữ liệu.', statusEl, listEl, countEl, moreEl, doc);
            return;
        }

        const token = ++loadToken;
        const targetChapterId = currentChapterId;
        currentPage = 0;
        hasNext = false;
        isLoadingMore = false;
        renderLoading(statusEl, listEl, countEl, moreEl, doc);

        const url = '/api/novel/chapters/' + encodeURIComponent(targetChapterId) + '/comments/feed?page=0&size=20';

        fetchFn(url)
            .then(function (res) {
                if (token !== loadToken || targetChapterId !== currentChapterId) {
                    return null;
                }
                if (!res || !res.ok) {
                    const status = res ? res.status : 0;
                    throw new Error('HTTP ' + status);
                }
                return res.json();
            })
            .then(async function (data) {
                if (token !== loadToken || targetChapterId !== currentChapterId || !data) {
                    return;
                }
                const items = Array.isArray(data.items) ? data.items : [];
                rootPageMap = Object.create(null);
                for (let k = 0; k < items.length; k++) {
                    const it = items[k];
                    const id = it ? (it.rootCommentId || it.id) : null;
                    if (id) {
                        rootPageMap[String(id)] = 0;
                    }
                }
                currentPage = 0;
                hasNext = Boolean(data.hasNext);
                isLoadingMore = false;

                if (items.length === 0) {
                    renderEmpty(statusEl, listEl, countEl, moreEl, doc);
                } else {
                    renderPopulated(items, statusEl, listEl, countEl, doc);
                    if (hasNext) {
                        renderMoreReady(moreEl, doc);
                    } else {
                        renderMoreHidden(moreEl);
                    }
                }

                if (pendingDeepLink) {
                    const dl = pendingDeepLink;
                    pendingDeepLink = null;
                    await resolveDeepLink(dl, doc, token);
                }
            })
            .catch(function (_) {
                if (token !== loadToken || targetChapterId !== currentChapterId) {
                    return;
                }
                if (pendingDeepLink) {
                    pendingDeepLink = null;
                    fallbackToContainer();
                    scrubDeepLinkParams(doc);
                }
                renderError('Không thể tải bình luận. Vui lòng thử lại.', statusEl, listEl, countEl, moreEl, doc);
            });
    }

    /**
     * Loads the next page of root comments with deduplication and generation-safety.
     */
    function loadMore() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { listEl, countEl, moreEl } = getElements();
        if (!doc || !listEl || !currentChapterId || isLoadingMore || isRefreshing || !hasNext) {
            return;
        }

        const fetchFn = (typeof injectedFetch === 'function')
            ? injectedFetch
            : (typeof window !== 'undefined' && typeof window.fetch === 'function')
                ? window.fetch.bind(window)
                : (typeof fetch === 'function') ? fetch : null;

        if (!fetchFn) {
            renderMoreError(moreEl, doc);
            return;
        }

        isLoadingMore = true;
        renderMoreLoading(moreEl, doc);

        const token = loadToken;
        const targetChapterId = currentChapterId;
        const requestedPage = currentPage + 1;
        const url = '/api/novel/chapters/' + encodeURIComponent(targetChapterId) + '/comments/feed?page=' + requestedPage + '&size=20';

        fetchFn(url)
            .then(function (res) {
                if (token !== loadToken || targetChapterId !== currentChapterId) {
                    return null;
                }
                if (!res || !res.ok) {
                    const status = res ? res.status : 0;
                    throw new Error('HTTP ' + status);
                }
                return res.json();
            })
            .then(function (data) {
                if (token !== loadToken || targetChapterId !== currentChapterId || !data) {
                    return;
                }
                isLoadingMore = false;

                const incomingItems = Array.isArray(data.items) ? data.items : [];
                const acceptedNewRoots = deduplicateRoots(currentItems, incomingItems);

                for (let i = 0; i < acceptedNewRoots.length; i++) {
                    const item = acceptedNewRoots[i];
                    currentItems.push(item);
                    const threadCard = renderThread(item, doc);
                    if (threadCard) {
                        listEl.appendChild(threadCard);
                    }
                    const id = item ? (item.rootCommentId || item.id) : null;
                    if (id) {
                        rootPageMap[String(id)] = requestedPage;
                    }
                }

                currentPage = requestedPage;
                hasNext = Boolean(data.hasNext);

                if (countEl) {
                    countEl.textContent = formatCommentCount(getActiveCommentCount(currentItems));
                }

                if (hasNext) {
                    renderMoreReady(moreEl, doc);
                } else {
                    renderMoreHidden(moreEl);
                }

                notifyFeedRendered(doc, targetChapterId);
            })
            .catch(function (_) {
                if (token !== loadToken || targetChapterId !== currentChapterId) {
                    return;
                }
                isLoadingMore = false;
                renderMoreError(moreEl, doc);
            });
    }

    /**
     * Handles chapter transition event.
     *
     * @param {Event|Object} evt
     */
    function handleChapterChanged(evt) {
        closeActiveMenu(false);
        clearHighlight();
        pendingDeepLink = null;
        isRefreshing = false;
        rootPageMap = Object.create(null);

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { sectionEl, moreEl } = getElements();

        const newChapterId = (evt && evt.detail && evt.detail.chapterId)
            ? String(evt.detail.chapterId).trim()
            : resolveChapterId(doc, sectionEl);

        if (newChapterId) {
            currentChapterId = newChapterId;
            if (sectionEl) {
                sectionEl.setAttribute('data-chapter-id', newChapterId);
            }
            renderMoreHidden(moreEl);
            fetchFeed();
        }
    }

    /**
     * Public retry method.
     */
    function retryFetch() {
        fetchFeed();
    }

    /**
     * Authoritative mutation refresh from page 0.
     * Preserves existing DOM and items while fetch is in-flight.
     * Invalidates prior load-more requests and sets isRefreshing = true.
     *
     * @returns {Promise<Object>}
     */
    function refreshFromPageZero() {
        clearHighlight();
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { statusEl, listEl, countEl, moreEl } = getElements();
        if (!doc || !listEl || !currentChapterId) {
            return Promise.reject(new Error('Comments module not initialized.'));
        }

        const fetchFn = (typeof injectedFetch === 'function')
            ? injectedFetch
            : (typeof window !== 'undefined' && typeof window.fetch === 'function')
                ? window.fetch.bind(window)
                : (typeof fetch === 'function') ? fetch : null;

        if (!fetchFn) {
            return Promise.reject(new Error('Fetch implementation not available.'));
        }

        const token = ++loadToken;
        const targetChapterId = currentChapterId;
        isRefreshing = true;
        isLoadingMore = false;

        const url = '/api/novel/chapters/' + encodeURIComponent(targetChapterId) + '/comments/feed?page=0&size=20';

        return fetchFn(url)
            .then(function (res) {
                if (token !== loadToken || targetChapterId !== currentChapterId) {
                    return null;
                }
                if (!res || !res.ok) {
                    const status = res ? res.status : 0;
                    throw new Error('HTTP ' + status);
                }
                return res.json();
            })
            .then(function (data) {
                if (token !== loadToken || targetChapterId !== currentChapterId) {
                    return null;
                }
                if (!data) {
                    throw new Error('Empty response data');
                }
                isRefreshing = false;
                const items = Array.isArray(data.items) ? data.items : [];
                rootPageMap = Object.create(null);
                for (let k = 0; k < items.length; k++) {
                    const it = items[k];
                    const id = it ? (it.rootCommentId || it.id) : null;
                    if (id) {
                        rootPageMap[String(id)] = 0;
                    }
                }
                currentPage = 0;
                hasNext = Boolean(data.hasNext);

                if (items.length === 0) {
                    renderEmpty(statusEl, listEl, countEl, moreEl, doc);
                } else {
                    renderPopulated(items, statusEl, listEl, countEl, doc);
                    if (hasNext) {
                        renderMoreReady(moreEl, doc);
                    } else {
                        renderMoreHidden(moreEl);
                    }
                }
                return data;
            })
            .catch(function (err) {
                if (token === loadToken && targetChapterId === currentChapterId) {
                    isRefreshing = false;
                }
                throw err;
            });
    }

    /**
     * Locates a thread card in the list element by root comment ID.
     *
     * @param {Element} listEl
     * @param {string} rootId
     * @returns {Element|null}
     */
    function findThreadCard(listEl, rootId) {
        if (!listEl || !rootId) return null;
        if (typeof listEl.querySelector === 'function') {
            try {
                const found = listEl.querySelector('.novel-block-discussion-thread[data-root-id="' + String(rootId).replace(/"/g, '\\"') + '"]');
                if (found) return found;
            } catch (_) {}
        }
        const children = listEl.children || listEl.childNodes || [];
        for (let i = 0; i < children.length; i++) {
            const child = children[i];
            if (child && typeof child.getAttribute === 'function') {
                if (child.getAttribute('data-root-id') === String(rootId)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * Authoritatively refreshes a single root thread after a mutation.
     * Preserves other root DOM elements, currentPage, hasNext, and loaded pages.
     *
     * @param {string} rootCommentId
     * @param {Object} [options]
     * @param {string} [options.revealCommentId]
     * @returns {Promise<Object>}
     */
    async function refreshRootThread(rootCommentId, options) {
        if (!rootCommentId) {
            return Promise.reject(new Error('rootCommentId is required.'));
        }

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { listEl, countEl } = getElements();
        if (!doc || !currentChapterId || !listEl) {
            return Promise.reject(new Error('Comments module not initialized.'));
        }

        const fetchFn = (typeof injectedFetch === 'function')
            ? injectedFetch
            : (typeof window !== 'undefined' && typeof window.fetch === 'function')
                ? window.fetch.bind(window)
                : (typeof fetch === 'function') ? fetch : null;

        if (!fetchFn) {
            return Promise.reject(new Error('Fetch implementation not available.'));
        }

        const strRootId = String(rootCommentId).trim();
        const sourcePage = (rootPageMap && rootPageMap[strRootId] !== undefined)
            ? rootPageMap[strRootId]
            : 0;

        const token = ++loadToken;
        const targetChapterId = currentChapterId;
        isRefreshing = true;

        const url = '/api/novel/chapters/' + encodeURIComponent(targetChapterId) +
            '/comments/feed?page=' + encodeURIComponent(sourcePage) + '&size=20';

        try {
            const res = await fetchFn(url);
            if (token !== loadToken || targetChapterId !== currentChapterId) {
                return null;
            }
            if (!res || !res.ok) {
                const status = res ? res.status : 0;
                throw new Error('HTTP ' + status);
            }

            const data = await res.json();
            if (token !== loadToken || targetChapterId !== currentChapterId) {
                return null;
            }
            if (!data) {
                throw new Error('Empty response data');
            }

            const items = Array.isArray(data.items) ? data.items : [];
            const foundItem = items.find(function (it) {
                return it && String(it.rootCommentId || it.id) === strRootId;
            });

            if (!foundItem) {
                // Target root missing from expected source page (e.g. concurrent shifts)
                // Fallback to authoritative page-0 refresh
                isRefreshing = false;
                return refreshFromPageZero();
            }

            // Replace in currentItems
            const itemIdx = currentItems.findIndex(function (it) {
                return it && String(it.rootCommentId || it.id) === strRootId;
            });
            if (itemIdx >= 0) {
                currentItems[itemIdx] = foundItem;
            } else {
                currentItems.push(foundItem);
            }

            // Update rootPageMap
            if (!rootPageMap) {
                rootPageMap = Object.create(null);
            }
            rootPageMap[strRootId] = sourcePage;

            // Preserve reply expansion depth & reveal newly created reply if requested
            const oldThreadCard = findThreadCard(listEl, strRootId);
            let oldRenderedCount = INITIAL_VISIBLE_REPLIES;
            if (oldThreadCard && typeof oldThreadCard.querySelectorAll === 'function') {
                const renderedReplies = oldThreadCard.querySelectorAll('.novel-comment--reply');
                if (renderedReplies && renderedReplies.length > 0) {
                    oldRenderedCount = renderedReplies.length;
                }
            }

            let targetRevealCount = Math.max(oldRenderedCount, INITIAL_VISIBLE_REPLIES);
            const replies = Array.isArray(foundItem.replies) ? foundItem.replies : [];
            if (options && options.revealCommentId && replies.length > 0) {
                const strRevealId = String(options.revealCommentId).trim();
                const repIdx = replies.findIndex(function (r) {
                    return r && String(r.id) === strRevealId;
                });
                if (repIdx >= 0) {
                    targetRevealCount = Math.max(targetRevealCount, repIdx + 1);
                }
            }
            targetRevealCount = Math.min(targetRevealCount, replies.length);

            // Re-render target root card and swap in DOM (or remove if authoritative root is deleted)
            const isHiddenRoot = Boolean(foundItem && (foundItem.tombstone === true || foundItem.status === 'DELETED'));
            const newThreadCard = renderThread(foundItem, doc, targetRevealCount);
            if (newThreadCard && oldThreadCard && oldThreadCard.parentNode) {
                oldThreadCard.parentNode.replaceChild(newThreadCard, oldThreadCard);
            } else if (!newThreadCard && isHiddenRoot && oldThreadCard) {
                if (oldThreadCard.parentNode && typeof oldThreadCard.parentNode.removeChild === 'function') {
                    oldThreadCard.parentNode.removeChild(oldThreadCard);
                } else if (typeof oldThreadCard.remove === 'function') {
                    oldThreadCard.remove();
                }
            }

            // Recalculate loaded active-comment header count
            if (countEl) {
                countEl.textContent = formatCommentCount(getActiveCommentCount(currentItems));
            }

            isRefreshing = false;
            return foundItem;
        } catch (err) {
            if (token === loadToken && targetChapterId === currentChapterId) {
                isRefreshing = false;
            }
            throw err;
        }
    }

    /**
     * Initializes the chapter comments module.
     *
     * @param {Document} doc
     * @param {Object} [options]
     */
    function initReaderChapterComments(doc, options) {
        const documentRef = doc || (typeof document !== 'undefined' ? document : null);
        if (!documentRef) {
            return;
        }

        // Clean up any existing listeners on prior document
        if (currentDoc) {
            if (chapterChangedHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            }
            if (documentClickHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('click', documentClickHandler);
            }
        }
        closeActiveMenu(false);

        currentDoc = documentRef;
        const opts = (options && typeof options === 'object') ? options : {};

        if (typeof opts.fetch === 'function') {
            injectedFetch = opts.fetch;
        }

        if (typeof opts.openDiscussionTarget === 'function') {
            injectedOpenDiscussionTarget = opts.openDiscussionTarget;
        } else {
            injectedOpenDiscussionTarget = null;
        }

        if (typeof opts.authenticated === 'boolean') {
            injectedAuthenticated = opts.authenticated;
        }
        if (opts.reportModal) {
            injectedReportModal = opts.reportModal;
        }

        const { sectionEl, listEl } = getElements();
        if (!sectionEl || !listEl) {
            // Missing DOM requirements - exit gracefully without error
            return;
        }

        currentChapterId = (opts.chapterId && String(opts.chapterId).trim())
            ? String(opts.chapterId).trim()
            : resolveChapterId(currentDoc, sectionEl);

        if (!currentChapterId) {
            return;
        }

        chapterChangedHandler = function (evt) {
            handleChapterChanged(evt);
        };
        documentClickHandler = function (e) {
            onDocumentClick(e);
        };

        if (typeof currentDoc.addEventListener === 'function') {
            currentDoc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            currentDoc.addEventListener('click', documentClickHandler);
        }

        const deepLink = extractDeepLinkParams(currentDoc, opts);
        if (deepLink) {
            pendingDeepLink = deepLink;
        }

        fetchFeed();
    }

    /**
     * Destroys module state, clears DOM, and detaches listeners.
     */
    function destroyReaderChapterComments() {
        loadToken++; // Invalidate any in-flight request
        clearHighlight();
        pendingDeepLink = null;
        closeActiveMenu(false);
        const presentation = resolveCommentPresentation();
        if (presentation && typeof presentation.unbindDocument === 'function') {
            presentation.unbindDocument(currentDoc);
        }

        if (currentDoc) {
            if (chapterChangedHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            }
            if (documentClickHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('click', documentClickHandler);
            }
        }

        const { statusEl, listEl, countEl, moreEl } = getElements();
        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');
        }
        if (statusEl) {
            clearElement(statusEl);
        }
        if (countEl) {
            countEl.textContent = '';
        }
        if (moreEl) {
            renderMoreHidden(moreEl);
        }

        currentDoc = null;
        currentChapterId = null;
        injectedFetch = null;
        injectedOpenDiscussionTarget = null;
        injectedCommentPresentation = undefined;
        chapterChangedHandler = null;
        documentClickHandler = null;
        currentStatus = 'idle';
        currentItems = [];
        currentPage = 0;
        hasNext = false;
        isLoadingMore = false;
        isRefreshing = false;
        rootPageMap = Object.create(null);
    }

    /**
     * Returns current state snapshot (useful for testing/diagnostics).
     *
     * @returns {Object}
     */
    function getState() {
        return {
            chapterId: currentChapterId,
            status: currentStatus,
            items: currentItems,
            loadToken: loadToken,
            currentPage: currentPage,
            hasNext: hasNext,
            isLoadingMore: isLoadingMore,
            isRefreshing: isRefreshing,
            rootPageMap: Object.assign({}, rootPageMap)
        };
    }

    // Auto-init in browser if DOM is ready
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderChapterComments(document);
            });
        } else {
            initReaderChapterComments(document);
        }
    }

    return {
        SECTION_ID: SECTION_ID,
        TITLE_ID: TITLE_ID,
        COUNT_ID: COUNT_ID,
        STATUS_ID: STATUS_ID,
        LIST_ID: LIST_ID,
        MORE_ID: MORE_ID,
        INITIAL_VISIBLE_REPLIES: INITIAL_VISIBLE_REPLIES,
        REPLY_REVEAL_BATCH_SIZE: REPLY_REVEAL_BATCH_SIZE,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        EVENT_FEED_REPLACING: EVENT_FEED_REPLACING,
        EVENT_FEED_RENDERED: EVENT_FEED_RENDERED,
        init: initReaderChapterComments,
        destroy: destroyReaderChapterComments,
        retry: retryFetch,
        refreshFromPageZero: refreshFromPageZero,
        refreshRootThread: refreshRootThread,
        findThreadCard: findThreadCard,
        loadMore: loadMore,
        getState: getState,
        formatCommentCount: formatCommentCount,
        formatTimestamp: formatTimestamp,
        isCommentEdited: isCommentEdited,
        resolveTombstoneContextChildDisplayName: resolveTombstoneContextChildDisplayName,
        sanitizeAvatarUrl: sanitizeAvatarUrl,
        createAvatarFallback: createAvatarFallback,
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        isOriginNavigable: isOriginNavigable,
        openOriginDiscussion: openOriginDiscussion,
        closeActiveMenu: closeActiveMenu,
        getActiveOpenMenu: getActiveOpenMenu,
        getHighlightedElement: function () { return highlightedElement; },
        clearHighlight: clearHighlight,
        resolveDeepLink: resolveDeepLink,
        extractDeepLinkParams: extractDeepLinkParams,
        scrubDeepLinkParams: scrubDeepLinkParams,
        deduplicateRoots: deduplicateRoots,
        getActiveCommentCount: getActiveCommentCount,
        openReportModal: openReportModal,
        setReportModal: function (fn) { injectedReportModal = fn; },
        setCommentPresentationImplementation: function (fn) { injectedCommentPresentation = fn; },
        getCommentPresentation: resolveCommentPresentation,
        setAuthenticatedImplementation: function (val) { injectedAuthenticated = val; },
        isUserAuthenticated: isUserAuthenticated,
        buildOverflowActionDescriptors: buildOverflowActionDescriptors,
        createActionsMenu: createActionsMenu
    };
});
