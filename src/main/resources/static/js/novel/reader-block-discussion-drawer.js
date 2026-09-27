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
    let injectedAuthenticated = null;
    let injectedReportModal = null;
    let injectedCommentPresentation = undefined;
    let activeContext = null;
    let isDrawerOpen = false;
    let priorFocusedElement = null;
    let currentRequestId = 0;
    let currentAbortController = null;

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
     * Sets the injected CommentPresentation module (for testing or explicit dependency injection).
     *
     * @param {Object|null} pres
     */
    function setCommentPresentation(pres) {
        injectedCommentPresentation = pres;
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
     * Constructs normalized overflow action descriptors for a drawer comment.
     * Drawer consumer owns domain capabilities (canEdit, canDelete, canReport, isEdited).
     * Strictly ordered: view-revisions -> edit -> delete -> report.
     *
     * @param {Object} options
     * @returns {Array}
     */
    function buildOverflowActionDescriptors(options) {
        if (!options || typeof options !== 'object') {
            return [];
        }
        const hasHistory = Boolean(options.isEdited);
        const hasEdit = Boolean(options.canEdit);
        const hasDelete = Boolean(options.canDelete);
        const hasReport = Boolean(options.canReport);

        if (!hasHistory && !hasEdit && !hasDelete && !hasReport) {
            return [];
        }

        const descriptors = [];

        // 1. View Revisions
        if (hasHistory) {
            descriptors.push({
                key: 'view-revisions',
                label: 'Xem lịch sử chỉnh sửa',
                className: 'novel-comment-menu-item',
                attributes: {
                    'data-action': 'view-revisions',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.replyId ? { 'data-reply-id': String(options.replyId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 2. Edit
        if (hasEdit) {
            descriptors.push({
                key: 'edit',
                label: 'Chỉnh sửa',
                className: 'novel-comment-menu-item novel-comment-edit-btn',
                attributes: {
                    'data-action': 'edit',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.replyId ? { 'data-reply-id': String(options.replyId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 3. Delete
        if (hasDelete) {
            descriptors.push({
                key: 'delete',
                label: 'Xóa',
                danger: true,
                className: 'novel-comment-menu-item novel-comment-delete-btn',
                attributes: {
                    'data-action': 'delete',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.replyId ? { 'data-reply-id': String(options.replyId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        // 4. Report (with separator if preceded by other items)
        if (hasReport) {
            const hasPreceding = hasHistory || hasEdit || hasDelete;
            descriptors.push({
                key: 'report',
                label: 'Báo cáo',
                danger: true,
                separatorBefore: hasPreceding,
                className: 'novel-comment-menu-item novel-comment-report-btn',
                attributes: {
                    'data-action': 'report',
                    ...(options.commentId ? { 'data-comment-id': String(options.commentId) } : {}),
                    ...(options.replyId ? { 'data-reply-id': String(options.replyId) } : {}),
                    ...(options.rootCommentId ? { 'data-root-id': String(options.rootCommentId) } : {})
                }
            });
        }

        return descriptors;
    }

    /**
     * Constructs the three-dot overflow actions menu element by delegating to CommentPresentation.
     *
     * @param {Object|Array} opts
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function createActionsMenu(opts, doc) {
        if (!opts) return null;
        const documentRef = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const descriptors = Array.isArray(opts)
            ? opts
            : buildOverflowActionDescriptors(opts);

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
     * Checks whether the current user session is authenticated.
     *
     * @param {Document} [doc]
     * @returns {boolean}
     */
    function isUserAuthenticated(doc) {
        if (typeof injectedAuthenticated === 'boolean') {
            return injectedAuthenticated;
        }
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (d) {
            const drawerEl = d.getElementById ? d.getElementById(DRAWER_ID) : null;
            if (drawerEl) {
                const authAttr = (typeof drawerEl.getAttribute === 'function' ? drawerEl.getAttribute('data-authenticated') : null) ||
                    (drawerEl.dataset && drawerEl.dataset.authenticated);
                if (authAttr === 'true' || authAttr === true) {
                    return true;
                }
            }
            const sec = d.getElementById ? d.getElementById('novelChapterComments') : null;
            if (sec) {
                const authAttr = (typeof sec.getAttribute === 'function' ? sec.getAttribute('data-authenticated') : null) ||
                    (sec.dataset && sec.dataset.authenticated);
                if (authAttr === 'true' || authAttr === true) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Redirects unauthenticated guest to login URL safely.
     *
     * @param {Document} [doc]
     */
    function redirectToLogin(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        let destination = '/login';
        if (d) {
            const drawerEl = d.getElementById ? d.getElementById(DRAWER_ID) : null;
            if (drawerEl && typeof drawerEl.getAttribute === 'function') {
                const customUrl = drawerEl.getAttribute('data-login-url');
                if (customUrl && customUrl.trim()) {
                    destination = customUrl.trim();
                }
            }
            if (destination === '/login') {
                const sec = d.getElementById ? d.getElementById('novelChapterComments') : null;
                if (sec && typeof sec.getAttribute === 'function') {
                    const customUrl = sec.getAttribute('data-login-url');
                    if (customUrl && customUrl.trim()) {
                        destination = customUrl.trim();
                    }
                }
            }
        }
        const win = (d && d.defaultView) ? d.defaultView : (typeof window !== 'undefined' ? window : null);
        const currentHref = (win && win.location) ? (win.location.pathname + (win.location.search || '')) : '';
        if (destination === '/login' && currentHref) {
            destination = '/login?returnTo=' + encodeURIComponent(currentHref);
        }
        if (win && win.location) {
            win.location.href = destination;
        }
    }

    /**
     * Opens the shared CommentReportModal for a target comment in the discussion drawer.
     *
     * @param {string} commentId
     * @param {Element} [triggerEl]
     * @param {Document} [doc]
     * @returns {boolean} true if modal opened, false otherwise
     */
    function openReportModal(commentId, triggerEl, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        let chapterId = activeContext && activeContext.chapterId;
        if (!chapterId && d) {
            const drawerEl = d.getElementById ? d.getElementById(DRAWER_ID) : null;
            if (drawerEl && typeof drawerEl.getAttribute === 'function') {
                chapterId = drawerEl.getAttribute('data-chapter-id');
            }
            if (!chapterId) {
                const sec = d.getElementById ? d.getElementById('novelChapterComments') : null;
                if (sec && typeof sec.getAttribute === 'function') {
                    chapterId = sec.getAttribute('data-chapter-id');
                }
            }
        }
        if (!chapterId || !commentId) {
            return false;
        }

        if (!isUserAuthenticated(d)) {
            redirectToLogin(d);
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
            contextLabel: 'novel-block-discussion',
            triggerEl: triggerEl || null,
            onSuccess: function (result) {
                if (triggerEl) {
                    triggerEl.textContent = 'Đã báo cáo';
                    triggerEl.disabled = true;
                    if (typeof triggerEl.setAttribute === 'function') {
                        triggerEl.setAttribute('title', 'Bạn đã gửi báo cáo cho bình luận này');
                    }
                }
            }
        });
    }

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
        closeActiveMenu(false);
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
     * Renders a root comment card using CommentPresentation.
     * Consumer owns domain capabilities; Shared presentation renders them.
     * Returns null if CommentPresentation is unavailable.
     *
     * @param {Object} rootItem Root comment or thread object
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function renderRoot(rootItem, doc) {
        const presentation = resolveCommentPresentation();
        if (!presentation || typeof presentation.renderComment !== 'function') {
            return null;
        }

        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        const root = (rootItem && rootItem.root) ? rootItem.root : rootItem;
        if (!root || typeof root !== 'object') {
            return null;
        }

        const isTombstone = root.tombstone === true || root.status === 'DELETED';
        if (isTombstone) {
            return null;
        }

        const rootId = root.id != null ? String(root.id) : '';
        const isRootEdited = isCommentEdited(root);
        const canEditRoot = root.canEdit === true;
        const canDeleteRoot = root.canDelete === true;
        const canReportRoot = root.status !== 'DELETED' && !canEditRoot && !canDeleteRoot;
        const authorUserId = (root.author && root.author.userId) || root.authorUserId;
        const authorDisplayName = (root.author && typeof root.author.displayName === 'string')
            ? root.author.displayName.trim()
            : '';

        const rootAttrs = {};
        if (rootId) {
            rootAttrs['data-comment-id'] = rootId;
        }
        if (authorUserId) {
            rootAttrs['data-author-user-id'] = String(authorUserId);
        }

        const overflowDescriptors = buildOverflowActionDescriptors({
            commentId: rootId,
            rootCommentId: rootId,
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
            author: root.author,
            createdAt: root.createdAt,
            edited: isRootEdited,
            reactionSummary: root.reactionSummary,
            body: root.body || '',
            overflowActions: overflowDescriptors.length > 0 ? overflowDescriptors : null,
            primaryActions: rootId ? [
                {
                    key: 'reply',
                    label: 'Phản hồi',
                    className: 'novel-comment-reply-btn',
                    attributes: {
                        'data-action': 'reply',
                        'data-comment-id': rootId,
                        'data-root-id': rootId,
                        ...(authorDisplayName ? { 'data-author-name': authorDisplayName } : {})
                    }
                }
            ] : []
        };

        return presentation.renderComment(rootDescriptor, d);
    }

    /**
     * Renders an individual reply card (active or tombstone) using CommentPresentation.
     * Returns null if CommentPresentation is unavailable.
     *
     * @param {Object} reply Reply DTO
     * @param {Object|string|number} rootItem Root comment or root ID
     * @param {Array} [allReplies] Array of all replies in the thread
     * @param {Object} [commentLookup] In-memory map of commentId -> comment
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function renderReply(reply, rootItem, allReplies, commentLookup, doc) {
        const presentation = resolveCommentPresentation();
        if (!presentation || typeof presentation.renderComment !== 'function') {
            return null;
        }

        let d = doc;
        let lookup = commentLookup;
        if (lookup && typeof lookup.createElement === 'function' && !d) {
            d = lookup;
            lookup = null;
        }
        d = d || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        if (!reply || typeof reply !== 'object') {
            return null;
        }

        let rootId = '';
        if (rootItem) {
            if (typeof rootItem === 'object') {
                const r = rootItem.root ? rootItem.root : rootItem;
                rootId = (r.id != null) ? String(r.id) : (r.rootCommentId != null ? String(r.rootCommentId) : '');
            } else {
                rootId = String(rootItem);
            }
        }

        const replyId = reply.id != null ? String(reply.id) : '';
        const isTombstone = reply.tombstone === true || reply.status === 'DELETED';
        const repAuthorUserId = (reply.author && reply.author.userId) || reply.authorUserId;

        let tombstoneContentNodes = null;
        if (isTombstone) {
            const contextChildDisplayName = replyId
                ? resolveTombstoneContextChildDisplayName(allReplies || [], replyId)
                : null;

            if (contextChildDisplayName) {
                const prefixSpan = d.createElement('span');
                prefixSpan.textContent = 'Bình luận mà ';

                const mentionSpan = d.createElement('span');
                mentionSpan.className = 'novel-comment-reply-mention';
                mentionSpan.textContent = '@' + contextChildDisplayName;

                const suffixSpan = d.createElement('span');
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
            if (parentId && parentId !== rootId) {
                const immediateParent = lookup ? lookup[parentId] : null;
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
        const canEditReply = !isTombstone && reply.canEdit === true;
        const canDeleteReply = !isTombstone && reply.canDelete === true;
        const canReportReply = !isTombstone && reply.status !== 'DELETED' && !canEditReply && !canDeleteReply;

        const repAuthorName = (reply.author && typeof reply.author.displayName === 'string')
            ? reply.author.displayName.trim()
            : '';

        const replyAttrs = {};
        if (replyId) {
            replyAttrs['data-reply-id'] = replyId;
            replyAttrs['data-comment-id'] = replyId;
        }
        if (repAuthorUserId) {
            replyAttrs['data-author-user-id'] = String(repAuthorUserId);
        }

        const overflowDescriptors = !isTombstone ? buildOverflowActionDescriptors({
            commentId: replyId,
            replyId: replyId,
            rootCommentId: rootId,
            isEdited: isReplyEdited,
            canEdit: canEditReply,
            canDelete: canDeleteReply,
            canReport: canReportReply
        }) : [];

        const replyDescriptor = {
            id: replyId,
            tag: 'article',
            legacyPrefix: 'novel-comment',
            className: 'novel-comment--reply' + (isTombstone ? ' is-tombstone' : ''),
            attributes: replyAttrs,
            tombstone: isTombstone,
            tombstoneContent: tombstoneContentNodes,
            author: reply.author,
            createdAt: reply.createdAt,
            edited: isReplyEdited,
            reactionSummary: reply.reactionSummary,
            body: function (bodyEl, bodyDoc) {
                const targetDoc = bodyDoc || d;
                if (parentDisplayName) {
                    const mentionSpan = targetDoc.createElement('span');
                    mentionSpan.className = 'novel-comment-reply-mention';
                    mentionSpan.textContent = '@' + parentDisplayName;

                    const bodyTextSpan = targetDoc.createElement('span');
                    bodyTextSpan.className = 'novel-comment-reply-body-text';
                    bodyTextSpan.textContent = reply.body || '';

                    bodyEl.appendChild(mentionSpan);
                    bodyEl.appendChild(bodyTextSpan);
                } else {
                    bodyEl.textContent = reply.body || '';
                }
            },
            overflowActions: overflowDescriptors.length > 0 ? overflowDescriptors : null,
            primaryActions: (!isTombstone && replyId) ? [
                {
                    key: 'reply',
                    label: 'Phản hồi',
                    className: 'novel-comment-reply-btn',
                    attributes: {
                        'data-action': 'reply',
                        'data-comment-id': replyId,
                        'data-reply-id': replyId,
                        ...(rootId ? { 'data-root-id': rootId } : {}),
                        ...(repAuthorName ? { 'data-author-name': repAuthorName } : {})
                    }
                }
            ] : []
        };

        return presentation.renderComment(replyDescriptor, d);
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

        let renderedCount = 0;
        for (let i = 0; i < threads.length; i++) {
            const thread = threads[i];
            if (!thread || !thread.root) {
                continue;
            }
            if (thread.root.tombstone === true || thread.root.status === 'DELETED') {
                continue;
            }

            const threadCard = doc.createElement('article');
            threadCard.className = 'novel-block-discussion-thread';
            if (thread.root.id) {
                threadCard.setAttribute('data-root-id', String(thread.root.id));
            }

            const rootEl = renderRoot(thread.root, doc);
            if (rootEl) {
                threadCard.appendChild(rootEl);
            }

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

                const repliesContainer = doc.createElement('div');
                repliesContainer.className = 'novel-comment-replies';
                repliesContainer.setAttribute('role', 'group');
                repliesContainer.setAttribute('aria-label', 'Phản hồi');

                for (let j = 0; j < replies.length; j++) {
                    const reply = replies[j];
                    if (!reply) {
                        continue;
                    }

                    const replyEl = renderReply(reply, thread.root, replies, commentLookup, doc);
                    if (replyEl) {
                        repliesContainer.appendChild(replyEl);
                    }
                }

                threadCard.appendChild(repliesContainer);
            }

            listContainer.appendChild(threadCard);
            renderedCount++;
        }

        if (renderedCount === 0) {
            renderEmptyState(container);
            return;
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
            : data.threads.filter(t => t && t.root && !t.root.tombstone && t.root.status !== 'DELETED').length;

        let commentCount = (typeof data.commentCount === 'number' && Number.isSafeInteger(data.commentCount) && data.commentCount >= 0)
            ? data.commentCount
            : null;

        if (commentCount === null) {
            commentCount = 0;
            for (let i = 0; i < data.threads.length; i++) {
                const t = data.threads[i];
                if (!t || !t.root) {
                    continue;
                }
                const isRootDeleted = t.root.tombstone === true || t.root.status === 'DELETED';
                if (isRootDeleted) {
                    continue;
                }
                commentCount += 1;
                if (Array.isArray(t.replies)) {
                    for (let j = 0; j < t.replies.length; j++) {
                        const rep = t.replies[j];
                        if (rep && !rep.tombstone && rep.status !== 'DELETED') {
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
            const target = e.target;

            if (!isDrawerOpen) {
                return;
            }
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

            // Report button clicked
            const reportBtn = (typeof target.closest === 'function')
                ? (target.closest('.novel-comment-report-btn') || target.closest('[data-action="report"]'))
                : null;
            if (reportBtn) {
                if (e.preventDefault) e.preventDefault();
                closeActiveMenu(false);
                const commentId = reportBtn.getAttribute('data-comment-id');
                if (commentId) {
                    if (!isUserAuthenticated(doc)) {
                        redirectToLogin(doc);
                        return;
                    }
                    openReportModal(commentId, reportBtn, doc);
                }
                return;
            }

            // Clicking inside drawer does NOT close
            if (target.closest('#' + DRAWER_ID)) {
                return;
            }
        });

        // 4. Keyboard Escape key closes drawer or active popover
        doc.addEventListener('keydown', function (e) {
            if (e && (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27)) {
                if (e.defaultPrevented) {
                    return;
                }
                if (getActiveOpenMenu()) {
                    closeActiveMenu(true);
                    if (typeof e.preventDefault === 'function') {
                        e.preventDefault();
                    }
                    return;
                }
                if (!isDrawerOpen) {
                    return;
                }
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
        if (options && typeof options.authenticated === 'boolean') {
            injectedAuthenticated = options.authenticated;
        }
        if (options && options.reportModal) {
            injectedReportModal = options.reportModal;
        }
        if (options && options.commentPresentation !== undefined) {
            injectedCommentPresentation = options.commentPresentation;
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
        closeActiveMenu(false);
        const presentation = resolveCommentPresentation();
        if (presentation && typeof presentation.unbindDocument === 'function') {
            presentation.unbindDocument(currentDoc);
        }
        closeDrawer();
        currentDoc = null;
        boundDoc = null;
        injectedFetch = null;
        injectedAuthenticated = null;
        injectedReportModal = null;
        injectedCommentPresentation = undefined;
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
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        openReportModal,
        setReportModal: function (fn) { injectedReportModal = fn; },
        setAuthenticatedImplementation: function (val) { injectedAuthenticated = val; },
        isUserAuthenticated,
        closeActiveMenu: closeActiveMenu,
        getActiveOpenMenu: getActiveOpenMenu,
        resolveCommentPresentation: resolveCommentPresentation,
        setCommentPresentation: setCommentPresentation,
        buildOverflowActionDescriptors: buildOverflowActionDescriptors,
        createActionsMenu: createActionsMenu,
        renderRoot: renderRoot,
        renderReply: renderReply
    };
});
