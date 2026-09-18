/**
 * KiemLai Universe — Wattpad-Style Novel Block Discussion Drawer (MS-05E5G2)
 *
 * Responsibilities:
 * - Owns the lifecycle, display, and read-only thread rendering of the block discussion drawer.
 * - Listens for 'kiemlai:block-discussion-requested' from reader-comment-affordance.js.
 * - Instantly opens drawer displaying provisional passage text and thread count.
 * - Issues GET /api/novel/chapters/{chapterId}/comments/blocks/{blockKey} to fetch authoritative threads.
 * - Race-safe request token tracking (late response from Block A never overwrites Block B).
 * - Listens for 'kiemlai:chapter-changed' to close drawer, abort requests, and clear context.
 * - Closes on backdrop click, close button, Escape key, or chapter transition.
 * - Manages accessibility (role="dialog", aria-modal="true", focus movement, aria-hidden).
 * - Manages body scroll locking with 'has-block-discussion-open'.
 * - Secure rendering: passage and comment bodies are ALWAYS rendered via textContent.
 * - Displays visible flat replies and handles deleted tombstones gracefully.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderBlockDiscussionDrawer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderBlockDiscussionDrawer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const DRAWER_ID = 'novelBlockDiscussionDrawer';
    const BACKDROP_ID = 'novelBlockDiscussionBackdrop';
    const CLOSE_BTN_ID = 'novelBlockDiscussionCloseBtn';
    const TITLE_ID = 'novelBlockDiscussionTitle';
    const COUNT_ID = 'novelBlockDiscussionCount';
    const PASSAGE_ID = 'novelBlockDiscussionPassage';
    const CONTENT_ID = 'novelBlockDiscussionContent';

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_LOADED = 'kiemlai:block-discussion-loaded';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_DISCUSSION_LOAD_FAILED = 'kiemlai:block-discussion-load-failed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const BODY_OPEN_CLASS = 'has-block-discussion-open';

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let injectedFetch = null;
    let activeContext = null;
    let isDrawerOpen = false;
    let priorFocusedElement = null;
    let currentRequestId = 0;
    let currentAbortController = null;

    /**
     * DOM element lookup helper.
     *
     * @returns {Object}
     */
    function getElements() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.getElementById !== 'function') {
            return {
                drawer: null,
                backdrop: null,
                closeBtn: null,
                titleEl: null,
                countEl: null,
                passageEl: null,
                contentEl: null
            };
        }
        return {
            drawer: doc.getElementById(DRAWER_ID),
            backdrop: doc.getElementById(BACKDROP_ID),
            closeBtn: doc.getElementById(CLOSE_BTN_ID),
            titleEl: doc.getElementById(TITLE_ID),
            countEl: doc.getElementById(COUNT_ID),
            passageEl: doc.getElementById(PASSAGE_ID),
            contentEl: doc.getElementById(CONTENT_ID)
        };
    }

    /**
     * Validates event.detail for 'kiemlai:block-discussion-requested'.
     *
     * Requirements:
     * - nonblank chapterId
     * - positive integer contentVersion
     * - nonblank blockKey
     * - canonicalText is a string
     * - threadCount is a non-negative integer (>= 0)
     *
     * @param {*} detail
     * @returns {boolean}
     */
    function isValidDiscussionDetail(detail) {
        if (!detail || typeof detail !== 'object') {
            return false;
        }

        const chapterId = typeof detail.chapterId === 'string' ? detail.chapterId.trim() : '';
        if (!chapterId) {
            return false;
        }

        const blockKey = typeof detail.blockKey === 'string' ? detail.blockKey.trim() : '';
        if (!blockKey) {
            return false;
        }

        const rawVersion = detail.contentVersion;
        let contentVersion = null;
        if (typeof rawVersion === 'number') {
            if (Number.isSafeInteger(rawVersion) && rawVersion > 0) {
                contentVersion = rawVersion;
            }
        } else if (typeof rawVersion === 'string') {
            const trimmed = rawVersion.trim();
            if (/^\d+$/.test(trimmed)) {
                const num = Number(trimmed);
                if (Number.isSafeInteger(num) && num > 0) {
                    contentVersion = num;
                }
            }
        }
        if (contentVersion === null) {
            return false;
        }

        if (typeof detail.canonicalText !== 'string') {
            return false;
        }

        const rawCount = detail.threadCount;
        let threadCount = null;
        if (typeof rawCount === 'number') {
            if (Number.isSafeInteger(rawCount) && rawCount >= 0) {
                threadCount = rawCount;
            }
        } else if (typeof rawCount === 'string') {
            const trimmed = rawCount.trim();
            if (/^\d+$/.test(trimmed)) {
                const num = Number(trimmed);
                if (Number.isSafeInteger(num) && num >= 0) {
                    threadCount = num;
                }
            }
        }
        if (threadCount === null) {
            return false;
        }

        return true;
    }

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
     * Formats thread count label (backward compatibility alias for formatCommentCount).
     *
     * @param {number} count
     * @returns {string}
     */
    function formatThreadCount(count) {
        return formatCommentCount(count);
    }

    /**
     * Formats ISO timestamp to localized readable string.
     * Returns empty string for invalid/missing timestamp without throwing.
     *
     * @param {string} isoString
     * @returns {string}
     */
    function formatTimestamp(isoString) {
        if (!isoString || typeof isoString !== 'string') {
            return '';
        }
        try {
            const date = new Date(isoString);
            if (isNaN(date.getTime())) {
                return '';
            }
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            return day + '/' + month + '/' + year + ' ' + hours + ':' + minutes;
        } catch (_) {
            return '';
        }
    }

    /**
     * Checks if an active comment has been edited by comparing createdAt and updatedAt timestamps.
     *
     * @param {Object} comment
     * @returns {boolean}
     */
    function isCommentEdited(comment) {
        if (!comment || typeof comment !== 'object') {
            return false;
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
     * Opens the drawer UI and sets appropriate accessibility attributes.
     */
    function showDrawerUI() {
        isDrawerOpen = true;
        const { drawer, backdrop, closeBtn } = getElements();
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);

        if (drawer) {
            drawer.hidden = false;
            drawer.setAttribute('aria-hidden', 'false');
            if (drawer.classList && typeof drawer.classList.add === 'function') {
                drawer.classList.add('is-open');
            }
        }

        if (backdrop) {
            backdrop.hidden = false;
            backdrop.setAttribute('aria-hidden', 'false');
            if (backdrop.classList && typeof backdrop.classList.add === 'function') {
                backdrop.classList.add('is-open');
            }
        }

        if (doc && doc.body && doc.body.classList && typeof doc.body.classList.add === 'function') {
            doc.body.classList.add(BODY_OPEN_CLASS);
        }

        if (closeBtn && typeof closeBtn.focus === 'function') {
            try {
                closeBtn.focus();
            } catch (_) {}
        }
    }

    /**
     * Determines whether an element is currently a usable keyboard focus target.
     * Guards against detached, hidden, disabled, and display:none elements.
     *
     * @param {Element|null} el
     * @param {Document|null} doc
     * @returns {boolean}
     */
    function isUsableFocusTarget(el, doc) {
        if (!el || typeof el.focus !== 'function') {
            return false;
        }

        // 1. Detached element checks
        if (typeof el.isConnected === 'boolean' && !el.isConnected) {
            return false;
        }
        if (doc) {
            if (typeof doc.contains === 'function' && !doc.contains(el)) {
                return false;
            }
            if (doc.documentElement && typeof doc.documentElement.contains === 'function' && !doc.documentElement.contains(el)) {
                return false;
            }
        }

        // 2. Disabled check
        if (el.disabled === true) {
            return false;
        }
        if (typeof el.hasAttribute === 'function' && el.hasAttribute('disabled')) {
            return false;
        }

        // 3. Computed style check if window is available
        const win = (doc && doc.defaultView) || (typeof window !== 'undefined' ? window : null);
        if (win && typeof win.getComputedStyle === 'function') {
            try {
                const computed = win.getComputedStyle(el);
                if (computed && (computed.display === 'none' || computed.visibility === 'hidden')) {
                    return false;
                }
            } catch (_) {}
        }

        // 4. Element and ancestor visibility / hidden / display checks
        let current = el;
        while (current && (current.nodeType === 1 || !current.nodeType)) {
            if (current.hidden === true) {
                return false;
            }
            if (typeof current.hasAttribute === 'function' && current.hasAttribute('hidden')) {
                return false;
            }
            if (current.style && (current.style.display === 'none' || current.style.visibility === 'hidden')) {
                return false;
            }
            current = current.parentElement || current.parentNode;
            if (doc && current === doc) {
                break;
            }
        }

        return true;
    }

    /**
     * Closes the drawer UI, clears content, and restores keyboard focus.
     */
    function closeDrawer() {
        const wasOpen = isDrawerOpen;
        const closingContext = activeContext ? {
            chapterId: activeContext.chapterId,
            blockKey: activeContext.blockKey
        } : null;

        isDrawerOpen = false;
        cancelInFlightFetch();
        activeContext = null;

        const { drawer, backdrop, passageEl, contentEl, countEl } = getElements();
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);

        if (drawer) {
            if (drawer.classList && typeof drawer.classList.remove === 'function') {
                drawer.classList.remove('is-open');
            }
            drawer.setAttribute('aria-hidden', 'true');
            drawer.hidden = true;
        }

        if (backdrop) {
            if (backdrop.classList && typeof backdrop.classList.remove === 'function') {
                backdrop.classList.remove('is-open');
            }
            backdrop.setAttribute('aria-hidden', 'true');
            backdrop.hidden = true;
        }

        if (doc && doc.body && doc.body.classList && typeof doc.body.classList.remove === 'function') {
            doc.body.classList.remove(BODY_OPEN_CLASS);
        }

        if (passageEl) {
            passageEl.textContent = '';
        }

        if (countEl) {
            countEl.textContent = '';
        }

        if (contentEl) {
            clearElement(contentEl);
        }

        // Restore keyboard focus safely if prior element is still a usable target
        if (isUsableFocusTarget(priorFocusedElement, doc)) {
            try {
                priorFocusedElement.focus();
            } catch (_) {}
        }
        priorFocusedElement = null;

        // Dispatch closed event ONLY when an actually-open drawer transitions to closed
        if (wasOpen && doc && typeof doc.dispatchEvent === 'function') {
            const detail = closingContext || {};
            const event = (typeof CustomEvent === 'function')
                ? new CustomEvent(EVENT_DISCUSSION_CLOSED, { detail: detail })
                : { type: EVENT_DISCUSSION_CLOSED, detail: detail };
            doc.dispatchEvent(event);
        }
    }

    /**
     * Renders provisional state (passage and count) and shows loading indicator.
     *
     * @param {string} canonicalText
     * @param {number} threadCount
     */
    function renderProvisionalState(canonicalText, threadCount) {
        const { passageEl, countEl, contentEl } = getElements();

        if (passageEl) {
            passageEl.textContent = canonicalText;
        }

        if (countEl) {
            countEl.textContent = formatThreadCount(threadCount);
        }

        if (contentEl) {
            renderLoadingState(contentEl);
        }
    }

    /**
     * Renders accessible loading state inside content container.
     *
     * @param {Element} container
     */
    function renderLoadingState(container) {
        clearElement(container);
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.createElement !== 'function') {
            return;
        }

        const loadingDiv = doc.createElement('div');
        loadingDiv.className = 'novel-block-discussion-status novel-block-discussion-status--loading';
        loadingDiv.setAttribute('role', 'status');
        loadingDiv.setAttribute('aria-live', 'polite');

        const spinner = doc.createElement('span');
        spinner.className = 'novel-block-discussion-spinner';
        spinner.setAttribute('aria-hidden', 'true');

        const text = doc.createElement('span');
        text.className = 'novel-block-discussion-status-text';
        text.textContent = 'Đang tải thảo luận...';

        loadingDiv.appendChild(spinner);
        loadingDiv.appendChild(text);
        container.appendChild(loadingDiv);
    }

    /**
     * Renders empty state when block has zero discussion threads.
     *
     * @param {Element} container
     */
    function renderEmptyState(container) {
        clearElement(container);
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.createElement !== 'function') {
            return;
        }

        const emptyDiv = doc.createElement('div');
        emptyDiv.className = 'novel-block-discussion-empty';

        const message = doc.createElement('p');
        message.className = 'novel-block-discussion-empty-text';
        message.textContent = 'Chưa có thảo luận nào cho đoạn này.';

        emptyDiv.appendChild(message);
        container.appendChild(emptyDiv);
    }

    /**
     * Renders block unavailable (404) error message.
     *
     * @param {string} message
     */
    function renderBlockUnavailableError(message) {
        const { contentEl } = getElements();
        if (!contentEl) {
            return;
        }
        clearElement(contentEl);
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.createElement !== 'function') {
            return;
        }

        const errorDiv = doc.createElement('div');
        errorDiv.className = 'novel-block-discussion-status novel-block-discussion-status--unavailable';
        errorDiv.setAttribute('role', 'alert');

        const text = doc.createElement('p');
        text.className = 'novel-block-discussion-status-text';
        text.textContent = message || 'Đoạn này không còn khả dụng trong phiên bản hiện tại.';

        errorDiv.appendChild(text);
        contentEl.appendChild(errorDiv);
    }

    /**
     * Renders generic network/server error message with Retry button.
     *
     * @param {string} message
     */
    function renderGenericError(message) {
        const { contentEl } = getElements();
        if (!contentEl) {
            return;
        }
        clearElement(contentEl);
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.createElement !== 'function') {
            return;
        }

        const errorDiv = doc.createElement('div');
        errorDiv.className = 'novel-block-discussion-status novel-block-discussion-status--error';
        errorDiv.setAttribute('role', 'alert');

        const text = doc.createElement('p');
        text.className = 'novel-block-discussion-status-text';
        text.textContent = message || 'Không thể tải thảo luận. Vui lòng thử lại.';

        const retryBtn = doc.createElement('button');
        retryBtn.type = 'button';
        retryBtn.className = 'novel-block-discussion-retry-btn';
        retryBtn.textContent = 'Thử lại';
        retryBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            retryFetch();
        });

        errorDiv.appendChild(text);
        errorDiv.appendChild(retryBtn);
        contentEl.appendChild(errorDiv);
    }

    /**
     * Dispatches terminal failure event for block discussion loading.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @param {'unavailable'|'error'|'invalid_response'|'unsupported'} reason
     */
    function dispatchLoadFailed(chapterId, blockKey, reason) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (doc && typeof doc.dispatchEvent === 'function') {
            const detail = {
                chapterId: chapterId,
                blockKey: blockKey,
                reason: reason
            };
            const event = (typeof CustomEvent === 'function')
                ? new CustomEvent(EVENT_DISCUSSION_LOAD_FAILED, { detail: detail })
                : { type: EVENT_DISCUSSION_LOAD_FAILED, detail: detail };
            doc.dispatchEvent(event);
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
     * Renders visible discussion threads and flat replies in response order.
     *
     * @param {Element} container
     * @param {Array} threads
     */
    function renderThreads(container, threads) {
        clearElement(container);
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || typeof doc.createElement !== 'function') {
            return;
        }

        if (!Array.isArray(threads) || threads.length === 0) {
            renderEmptyState(container);
            return;
        }

        const listContainer = doc.createElement('div');
        listContainer.className = 'novel-block-discussion-threads';
        listContainer.setAttribute('role', 'feed');
        listContainer.setAttribute('aria-label', 'Danh sách thảo luận');

        for (let i = 0; i < threads.length; i++) {
            const thread = threads[i];
            if (!thread || !thread.root) {
                continue;
            }

            const threadCard = doc.createElement('article');
            threadCard.className = 'novel-block-discussion-thread';
            if (thread.root.id) {
                threadCard.setAttribute('data-root-id', String(thread.root.id));
            }

            // Root comment element
            const rootEl = doc.createElement('div');
            rootEl.className = 'novel-comment novel-comment--root';
            if (thread.root.id) {
                rootEl.setAttribute('data-comment-id', String(thread.root.id));
            }
            if (thread.root.authorUserId) {
                rootEl.setAttribute('data-author-user-id', String(thread.root.authorUserId));
            }

            // Root Header (Author + Timestamp)
            const rootHeader = doc.createElement('header');
            rootHeader.className = 'novel-comment-header';

            renderAuthorPresentation(rootHeader, thread.root.author, doc);

            const rootTimeStr = formatTimestamp(thread.root.createdAt);
            if (rootTimeStr) {
                const rootTime = doc.createElement('time');
                rootTime.className = 'novel-comment-time';
                rootTime.setAttribute('datetime', String(thread.root.createdAt));
                rootTime.textContent = rootTimeStr;
                rootHeader.appendChild(rootTime);
            }

            if (!thread.root.tombstone && isCommentEdited(thread.root)) {
                const rootEdited = doc.createElement('button');
                rootEdited.type = 'button';
                rootEdited.className = 'novel-comment-edited';
                rootEdited.setAttribute('data-action', 'view-revisions');
                if (thread.root.id) {
                    rootEdited.setAttribute('data-comment-id', String(thread.root.id));
                }
                rootEdited.setAttribute('aria-label', 'Xem lịch sử chỉnh sửa');
                rootEdited.textContent = 'đã chỉnh sửa';
                rootHeader.appendChild(rootEdited);
            }

            // Root Body (rendered safely as textContent)
            const rootBody = doc.createElement('div');
            rootBody.className = 'novel-comment-body';
            rootBody.textContent = thread.root.body || '';

            rootEl.appendChild(rootHeader);
            rootEl.appendChild(rootBody);

            const rootActions = doc.createElement('div');
            rootActions.className = 'novel-comment-actions';

            const rootReplyBtn = doc.createElement('button');
            rootReplyBtn.type = 'button';
            rootReplyBtn.className = 'novel-comment-reply-btn';
            rootReplyBtn.setAttribute('data-action', 'reply');
            if (thread.root.id) {
                rootReplyBtn.setAttribute('data-comment-id', String(thread.root.id));
                rootReplyBtn.setAttribute('data-root-id', String(thread.root.id));
            }
            if (thread.root.author && typeof thread.root.author.displayName === 'string' && thread.root.author.displayName.trim()) {
                rootReplyBtn.setAttribute('data-author-name', thread.root.author.displayName.trim());
            }
            rootReplyBtn.textContent = 'Trả lời';
            rootActions.appendChild(rootReplyBtn);

            if (thread.root.canEdit === true) {
                const rootEditBtn = doc.createElement('button');
                rootEditBtn.type = 'button';
                rootEditBtn.className = 'novel-comment-edit-btn';
                rootEditBtn.setAttribute('data-action', 'edit');
                if (thread.root.id) {
                    rootEditBtn.setAttribute('data-comment-id', String(thread.root.id));
                    rootEditBtn.setAttribute('data-root-id', String(thread.root.id));
                }
                rootEditBtn.textContent = 'Chỉnh sửa';
                rootActions.appendChild(rootEditBtn);
            }

            if (!thread.root.tombstone && thread.root.canDelete === true) {
                const rootDeleteBtn = doc.createElement('button');
                rootDeleteBtn.type = 'button';
                rootDeleteBtn.className = 'novel-comment-delete-btn';
                rootDeleteBtn.setAttribute('data-action', 'delete');
                if (thread.root.id) {
                    rootDeleteBtn.setAttribute('data-comment-id', String(thread.root.id));
                    rootDeleteBtn.setAttribute('data-root-id', String(thread.root.id));
                }
                rootDeleteBtn.textContent = 'Xóa';
                rootActions.appendChild(rootDeleteBtn);
            }

            rootEl.appendChild(rootActions);
            threadCard.appendChild(rootEl);

            // Replies container (flat visual level)
            const replies = Array.isArray(thread.replies) ? thread.replies : [];
            if (replies.length > 0) {
                // Construct in-memory comment lookup for immediate parent resolution
                const commentLookup = Object.create(null);
                if (thread.root && thread.root.id) {
                    commentLookup[String(thread.root.id)] = thread.root;
                }
                for (let k = 0; k < replies.length; k++) {
                    const rep = replies[k];
                    if (rep && rep.id) {
                        commentLookup[String(rep.id)] = rep;
                    }
                }
                const rootId = (thread.root && thread.root.id) ? String(thread.root.id) : '';

                const repliesContainer = doc.createElement('div');
                repliesContainer.className = 'novel-comment-replies';
                repliesContainer.setAttribute('role', 'group');
                repliesContainer.setAttribute('aria-label', 'Phản hồi');

                for (let j = 0; j < replies.length; j++) {
                    const reply = replies[j];
                    if (!reply) {
                        continue;
                    }

                    const replyEl = doc.createElement('article');
                    replyEl.className = 'novel-comment novel-comment--reply';
                    if (reply.id) {
                        replyEl.setAttribute('data-reply-id', String(reply.id));
                        replyEl.setAttribute('data-comment-id', String(reply.id));
                    }

                    const isTombstone = reply.tombstone === true || reply.status === 'DELETED';
                    if (isTombstone) {
                        if (replyEl.classList && typeof replyEl.classList.add === 'function') {
                            replyEl.classList.add('is-tombstone');
                        }
                        const tombstoneBody = doc.createElement('div');
                        tombstoneBody.className = 'novel-comment-body novel-comment-body--tombstone';

                        const contextChildDisplayName = (reply.id)
                            ? resolveTombstoneContextChildDisplayName(replies, reply.id)
                            : null;

                        if (contextChildDisplayName) {
                            const prefixSpan = doc.createElement('span');
                            prefixSpan.textContent = 'Bình luận mà ';

                            const mentionSpan = doc.createElement('span');
                            mentionSpan.className = 'novel-comment-reply-mention';
                            mentionSpan.textContent = '@' + contextChildDisplayName;

                            const suffixSpan = doc.createElement('span');
                            suffixSpan.textContent = ' phản hồi đã bị xóa.';

                            tombstoneBody.appendChild(prefixSpan);
                            tombstoneBody.appendChild(mentionSpan);
                            tombstoneBody.appendChild(suffixSpan);
                        } else {
                            tombstoneBody.textContent = 'Bình luận đã bị xóa.';
                        }

                        replyEl.appendChild(tombstoneBody);
                    } else {
                        if (reply.authorUserId) {
                            replyEl.setAttribute('data-author-user-id', String(reply.authorUserId));
                        }

                        const replyHeader = doc.createElement('header');
                        replyHeader.className = 'novel-comment-header';

                        renderAuthorPresentation(replyHeader, reply.author, doc);

                        const replyTimeStr = formatTimestamp(reply.createdAt);
                        if (replyTimeStr) {
                            const replyTime = doc.createElement('time');
                            replyTime.className = 'novel-comment-time';
                            replyTime.setAttribute('datetime', String(reply.createdAt));
                            replyTime.textContent = replyTimeStr;
                            replyHeader.appendChild(replyTime);
                        }

                        if (!isTombstone && isCommentEdited(reply)) {
                            const replyEdited = doc.createElement('button');
                            replyEdited.type = 'button';
                            replyEdited.className = 'novel-comment-edited';
                            replyEdited.setAttribute('data-action', 'view-revisions');
                            if (reply.id) {
                                replyEdited.setAttribute('data-comment-id', String(reply.id));
                            }
                            replyEdited.setAttribute('aria-label', 'Xem lịch sử chỉnh sửa');
                            replyEdited.textContent = 'đã chỉnh sửa';
                            replyHeader.appendChild(replyEdited);
                        }

                        const replyBody = doc.createElement('div');
                        replyBody.className = 'novel-comment-body';

                        // Resolve immediate parent for Wattpad-style nested reply mention
                        let parentDisplayName = null;
                        const parentId = (reply.parentCommentId != null) ? String(reply.parentCommentId).trim() : '';
                        if (parentId && parentId !== rootId) {
                            const immediateParent = commentLookup[parentId];
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

                        if (parentDisplayName) {
                            const mentionSpan = doc.createElement('span');
                            mentionSpan.className = 'novel-comment-reply-mention';
                            mentionSpan.textContent = '@' + parentDisplayName;

                            const bodyTextSpan = doc.createElement('span');
                            bodyTextSpan.className = 'novel-comment-reply-body-text';
                            bodyTextSpan.textContent = reply.body || '';

                            replyBody.appendChild(mentionSpan);
                            replyBody.appendChild(bodyTextSpan);
                        } else {
                            replyBody.textContent = reply.body || '';
                        }

                        replyEl.appendChild(replyHeader);
                        replyEl.appendChild(replyBody);

                        const replyActions = doc.createElement('div');
                        replyActions.className = 'novel-comment-actions';

                        const replyBtn = doc.createElement('button');
                        replyBtn.type = 'button';
                        replyBtn.className = 'novel-comment-reply-btn';
                        replyBtn.setAttribute('data-action', 'reply');
                        if (reply.id) {
                            replyBtn.setAttribute('data-comment-id', String(reply.id));
                            replyBtn.setAttribute('data-reply-id', String(reply.id));
                        }
                        if (thread.root.id) {
                            replyBtn.setAttribute('data-root-id', String(thread.root.id));
                        }
                        if (reply.author && typeof reply.author.displayName === 'string' && reply.author.displayName.trim()) {
                            replyBtn.setAttribute('data-author-name', reply.author.displayName.trim());
                        }
                        replyBtn.textContent = 'Trả lời';

                        replyActions.appendChild(replyBtn);

                        if (!isTombstone && reply.canEdit === true) {
                            const replyEditBtn = doc.createElement('button');
                            replyEditBtn.type = 'button';
                            replyEditBtn.className = 'novel-comment-edit-btn';
                            replyEditBtn.setAttribute('data-action', 'edit');
                            if (reply.id) {
                                replyEditBtn.setAttribute('data-comment-id', String(reply.id));
                                replyEditBtn.setAttribute('data-reply-id', String(reply.id));
                            }
                            if (thread.root.id) {
                                replyEditBtn.setAttribute('data-root-id', String(thread.root.id));
                            }
                            replyEditBtn.textContent = 'Chỉnh sửa';
                            replyActions.appendChild(replyEditBtn);
                        }

                        if (!isTombstone && reply.canDelete === true) {
                            const replyDeleteBtn = doc.createElement('button');
                            replyDeleteBtn.type = 'button';
                            replyDeleteBtn.className = 'novel-comment-delete-btn';
                            replyDeleteBtn.setAttribute('data-action', 'delete');
                            if (reply.id) {
                                replyDeleteBtn.setAttribute('data-comment-id', String(reply.id));
                                replyDeleteBtn.setAttribute('data-reply-id', String(reply.id));
                            }
                            if (thread.root.id) {
                                replyDeleteBtn.setAttribute('data-root-id', String(thread.root.id));
                            }
                            replyDeleteBtn.textContent = 'Xóa';
                            replyActions.appendChild(replyDeleteBtn);
                        }

                        replyEl.appendChild(replyActions);
                    }

                    repliesContainer.appendChild(replyEl);
                }

                threadCard.appendChild(repliesContainer);
            }

            listContainer.appendChild(threadCard);
        }

        container.appendChild(listContainer);
    }

    /**
     * Cancels any ongoing fetch request and increments request token.
     */
    function cancelInFlightFetch() {
        currentRequestId++;
        if (currentAbortController) {
            try {
                currentAbortController.abort();
            } catch (_) {}
            currentAbortController = null;
        }
    }

    /**
     * Validates authoritative server response.
     *
     * @param {*} data
     * @param {string} requestedChapterId
     * @param {string} requestedBlockKey
     * @returns {boolean}
     */
    function isValidServerResponse(data, requestedChapterId, requestedBlockKey) {
        if (!data || typeof data !== 'object') {
            return false;
        }

        const chapterId = typeof data.chapterId === 'string' ? data.chapterId.trim() : '';
        if (!chapterId || chapterId !== requestedChapterId) {
            return false;
        }

        const blockKey = typeof data.blockKey === 'string' ? data.blockKey.trim() : '';
        if (!blockKey || blockKey !== requestedBlockKey) {
            return false;
        }

        const version = data.contentVersion;
        if (typeof version !== 'number' || !Number.isSafeInteger(version) || version <= 0) {
            return false;
        }

        if (typeof data.canonicalText !== 'string') {
            return false;
        }

        if (!Array.isArray(data.threads)) {
            return false;
        }

        return true;
    }

    /**
     * Processes authoritative successful block discussion response.
     *
     * @param {Object} data
     * @param {string} requestedChapterId
     * @param {string} requestedBlockKey
     */
    function handleDiscussionSuccess(data, requestedChapterId, requestedBlockKey) {
        if (!isValidServerResponse(data, requestedChapterId, requestedBlockKey)) {
            renderGenericError('Không thể tải thảo luận. Dữ liệu phản hồi không hợp lệ.');
            dispatchLoadFailed(requestedChapterId, requestedBlockKey, 'invalid_response');
            return;
        }

        // Update active context with server-authoritative data
        if (activeContext) {
            activeContext.contentVersion = data.contentVersion;
            activeContext.canonicalText = data.canonicalText;
            activeContext.authoritative = true;
        }

        const { passageEl, countEl, contentEl } = getElements();

        // 1. Authoritative passage text
        if (passageEl) {
            passageEl.textContent = data.canonicalText;
        }

        // 2. Authoritative thread count and comment count
        const threadCount = (typeof data.threadCount === 'number' && Number.isSafeInteger(data.threadCount) && data.threadCount >= 0)
            ? data.threadCount
            : data.threads.length;

        let commentCount = (typeof data.commentCount === 'number' && Number.isSafeInteger(data.commentCount) && data.commentCount >= 0)
            ? data.commentCount
            : null;

        if (commentCount === null) {
            commentCount = 0;
            for (let i = 0; i < data.threads.length; i++) {
                const t = data.threads[i];
                if (t && t.root) {
                    commentCount += 1;
                }
                if (t && Array.isArray(t.replies)) {
                    for (let j = 0; j < t.replies.length; j++) {
                        if (t.replies[j] && !t.replies[j].tombstone) {
                            commentCount += 1;
                        }
                    }
                }
            }
        }

        if (activeContext) {
            activeContext.threadCount = threadCount;
            activeContext.commentCount = commentCount;
        }

        if (countEl) {
            countEl.textContent = formatCommentCount(commentCount);
        }

        // 3. Render threads or empty state
        if (contentEl) {
            if (data.threads.length === 0) {
                renderEmptyState(contentEl);
            } else {
                renderThreads(contentEl, data.threads);
            }
        }

        // 4. Dispatch block-discussion-loaded event with authoritative context
        const targetDoc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (targetDoc && typeof targetDoc.dispatchEvent === 'function') {
            const eventPayload = {
                chapterId: requestedChapterId,
                contentVersion: data.contentVersion,
                blockKey: requestedBlockKey,
                threadCount: threadCount,
                commentCount: commentCount
            };
            const event = (typeof CustomEvent === 'function')
                ? new CustomEvent(EVENT_DISCUSSION_LOADED, { detail: eventPayload })
                : { type: EVENT_DISCUSSION_LOADED, detail: eventPayload };
            targetDoc.dispatchEvent(event);
        }
    }

    /**
     * Issues GET request to fetch block discussion from backend API.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     */
    async function fetchBlockDiscussion(chapterId, blockKey) {
        cancelInFlightFetch();
        const requestId = currentRequestId;

        let controller = null;
        if (typeof AbortController === 'function') {
            controller = new AbortController();
            currentAbortController = controller;
        }

        const url = '/api/novel/chapters/' + encodeURIComponent(chapterId) + '/comments/blocks/' + encodeURIComponent(blockKey);
        const fetchImpl = injectedFetch || (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);

        if (!fetchImpl) {
            if (requestId === currentRequestId) {
                renderGenericError('Không thể tải thảo luận. Trình duyệt không hỗ trợ fetch.');
                dispatchLoadFailed(chapterId, blockKey, 'unsupported');
            }
            return;
        }

        try {
            const fetchOptions = {
                method: 'GET',
                headers: {
                    'Accept': 'application/json'
                }
            };
            if (controller) {
                fetchOptions.signal = controller.signal;
            }

            const res = await fetchImpl(url, fetchOptions);

            // Stale request guard
            if (requestId !== currentRequestId) {
                return;
            }

            if (res.status === 404) {
                renderBlockUnavailableError('Đoạn này không còn khả dụng trong phiên bản hiện tại.');
                dispatchLoadFailed(chapterId, blockKey, 'unavailable');
                return;
            }

            if (!res.ok) {
                renderGenericError('Không thể tải thảo luận. Vui lòng thử lại.');
                dispatchLoadFailed(chapterId, blockKey, 'error');
                return;
            }

            const data = await res.json();

            // Stale request guard
            if (requestId !== currentRequestId) {
                return;
            }

            if (!isValidServerResponse(data, chapterId, blockKey)) {
                renderGenericError('Không thể tải thảo luận. Dữ liệu phản hồi không hợp lệ.');
                dispatchLoadFailed(chapterId, blockKey, 'invalid_response');
                return;
            }

            handleDiscussionSuccess(data, chapterId, blockKey);
        } catch (err) {
            if (err && err.name === 'AbortError') {
                return;
            }
            if (requestId === currentRequestId) {
                renderGenericError('Không thể tải thảo luận. Vui lòng thử lại.');
                dispatchLoadFailed(chapterId, blockKey, 'error');
            }
        }
    }

    /**
     * Re-issues fetch request for the current active context.
     */
    function retryFetch() {
        if (!activeContext) {
            return;
        }
        const countToDisplay = (typeof activeContext.commentCount === 'number' && Number.isSafeInteger(activeContext.commentCount) && activeContext.commentCount >= 0)
            ? activeContext.commentCount
            : activeContext.threadCount;
        renderProvisionalState(activeContext.canonicalText, countToDisplay);
        fetchBlockDiscussion(activeContext.chapterId, activeContext.blockKey);
    }

    /**
     * Refreshes the currently active block discussion while keeping drawer open state intact.
     *
     * @returns {Promise<void>}
     */
    function refreshActiveDiscussion() {
        if (!isDrawerOpen || !activeContext || !activeContext.chapterId || !activeContext.blockKey || !activeContext.authoritative) {
            return Promise.resolve();
        }
        return fetchBlockDiscussion(activeContext.chapterId, activeContext.blockKey);
    }

    /**
     * Opens the block discussion drawer from a discussion-requested event detail.
     *
     * @param {*} detail
     */
    function openDiscussion(detail) {
        if (!isValidDiscussionDetail(detail)) {
            return;
        }

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);

        // Record prior focused element before focusing into drawer
        if (!isDrawerOpen && doc && doc.activeElement) {
            priorFocusedElement = (typeof doc.activeElement.focus === 'function')
                ? doc.activeElement
                : null;
        }

        const chapterId = detail.chapterId.trim();
        const blockKey = detail.blockKey.trim();
        const contentVersion = Number(detail.contentVersion);
        const threadCount = Number(detail.threadCount);
        const canonicalText = detail.canonicalText;

        const rawCommentCount = detail.commentCount;
        let commentCount = null;
        if (typeof rawCommentCount === 'number' && Number.isSafeInteger(rawCommentCount) && rawCommentCount >= 0) {
            commentCount = rawCommentCount;
        } else if (typeof rawCommentCount === 'string') {
            const trimmed = rawCommentCount.trim();
            if (/^\d+$/.test(trimmed)) {
                const num = Number(trimmed);
                if (Number.isSafeInteger(num) && num >= 0) {
                    commentCount = num;
                }
            }
        }
        const initialCommentCount = commentCount !== null ? commentCount : threadCount;

        activeContext = {
            chapterId: chapterId,
            blockKey: blockKey,
            contentVersion: contentVersion,
            threadCount: threadCount,
            commentCount: initialCommentCount,
            canonicalText: canonicalText,
            authoritative: false
        };

        // 1. Immediately open drawer UI
        showDrawerUI();

        // 2. Immediately display provisional passage text & count
        renderProvisionalState(canonicalText, initialCommentCount);

        // 3. Issue GET request
        fetchBlockDiscussion(chapterId, blockKey);
    }

    /**
     * Binds document-level event listeners. Idempotent per document.
     *
     * @param {Document} doc
     */
    function bindDrawerEvents(doc) {
        if (!doc || typeof doc.addEventListener !== 'function') {
            return;
        }
        if (boundDoc === doc) {
            return;
        }
        boundDoc = doc;
        currentDoc = doc;

        // 1. Open event: kiemlai:block-discussion-requested
        doc.addEventListener(EVENT_DISCUSSION_REQUESTED, function (e) {
            if (e && e.detail) {
                openDiscussion(e.detail);
            }
        });

        // 2. Chapter transition event: kiemlai:chapter-changed
        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            closeDrawer();
        });

        // 3. Click handler for close button, backdrop, and drawer isolation
        doc.addEventListener('click', function (e) {
            if (!isDrawerOpen) {
                return;
            }
            const target = e.target;
            if (!target || typeof target.closest !== 'function') {
                return;
            }

            // Close button clicked
            if (target.closest('#' + CLOSE_BTN_ID)) {
                if (e.preventDefault) e.preventDefault();
                closeDrawer();
                return;
            }

            // Backdrop clicked
            if (target.closest('#' + BACKDROP_ID)) {
                if (e.preventDefault) e.preventDefault();
                closeDrawer();
                return;
            }

            // Clicking inside drawer does NOT close
            if (target.closest('#' + DRAWER_ID)) {
                return;
            }
        });

        // 4. Keyboard Escape key closes drawer
        doc.addEventListener('keydown', function (e) {
            if (!isDrawerOpen) {
                return;
            }
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                closeDrawer();
            }
        });
    }

    /**
     * Initializes the block discussion drawer module. Idempotent.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderBlockDiscussionDrawer(targetDoc, options) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) {
            return null;
        }
        currentDoc = doc;

        if (options && options.fetchFn) {
            injectedFetch = options.fetchFn;
        }

        bindDrawerEvents(doc);
        return {
            openDiscussion,
            closeDrawer,
            retryFetch
        };
    }

    /**
     * Resets module state. Useful for test teardown and isolation.
     */
    function resetDrawerState() {
        closeDrawer();
        currentDoc = null;
        boundDoc = null;
        injectedFetch = null;
        activeContext = null;
        isDrawerOpen = false;
        priorFocusedElement = null;
        currentRequestId = 0;
        currentAbortController = null;
    }

    // Auto-initialize when document is ready in browser
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderBlockDiscussionDrawer(document);
            });
        } else {
            initReaderBlockDiscussionDrawer(document);
        }
    }

    return {
        DRAWER_ID,
        BACKDROP_ID,
        CLOSE_BTN_ID,
        TITLE_ID,
        COUNT_ID,
        PASSAGE_ID,
        CONTENT_ID,
        EVENT_DISCUSSION_REQUESTED,
        EVENT_DISCUSSION_LOADED,
        EVENT_DISCUSSION_CLOSED,
        EVENT_DISCUSSION_LOAD_FAILED,
        EVENT_CHAPTER_CHANGED,
        BODY_OPEN_CLASS,
        isValidDiscussionDetail,
        isValidServerResponse,
        isUsableFocusTarget,
        formatCommentCount,
        formatThreadCount,
        formatTimestamp,
        isCommentEdited,
        resolveTombstoneContextChildDisplayName,
        openDiscussion,
        closeDrawer,
        retryFetch,
        refreshActiveDiscussion,
        initReaderBlockDiscussionDrawer,
        resetDrawerState,
        getActiveContext: function () { return activeContext; },
        isDrawerOpen: function () { return isDrawerOpen; },
        getPriorFocusedElement: function () { return priorFocusedElement; },
        setFetchImplementation: function (fn) { injectedFetch = fn; }
    };
});
