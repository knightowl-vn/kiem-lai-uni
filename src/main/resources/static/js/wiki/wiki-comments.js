/**
 * Kiem Lai Wiki — Article Discussion / Comments Module
 *
 * MS-05E6C: Article-level discussion frontend module for public Wiki articles.
 * Reuses Interaction backend endpoints:
 *   GET    /api/wiki/articles/{articleId}/comments?page={page}&size={size}
 *   GET    /api/wiki/articles/{articleId}/comments/{rootCommentId}/thread
 *   POST   /api/wiki/articles/{articleId}/comments
 *   POST   /api/wiki/articles/{articleId}/comments/{parentCommentId}/replies
 *   PATCH  /api/wiki/articles/{articleId}/comments/{commentId}
 *   DELETE /api/wiki/articles/{articleId}/comments/{commentId}
 */
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.WikiArticleComments = factory();
    }
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    const SECTION_ID = 'wikiDiscussion';
    const COUNT_BADGE_ID = 'wikiDiscussionCountBadge';
    const ROOT_COMPOSER_FORM_ID = 'wikiRootComposerForm';
    const ROOT_COMPOSER_INPUT_ID = 'wikiRootComposerInput';
    const ROOT_COMPOSER_SUBMIT_ID = 'wikiRootComposerSubmit';
    const ROOT_COMPOSER_ERROR_ID = 'wikiRootComposerError';
    const STATUS_ID = 'wikiDiscussionStatus';
    const THREAD_LIST_ID = 'wikiDiscussionThreadList';
    const FOOTER_ID = 'wikiDiscussionFooter';
    const LOAD_MORE_BTN_ID = 'wikiDiscussionLoadMoreBtn';

    const DEFAULT_PAGE_SIZE = 20;

    const HISTORY_MODAL_ID = 'wikiCommentHistoryModal';
    const HISTORY_TITLE_ID = 'wikiCommentHistoryModalTitle';
    const HISTORY_STATUS_ID = 'wikiCommentHistoryStatus';
    const HISTORY_LIST_ID = 'wikiCommentHistoryList';
    const HISTORY_MORE_CONTAINER_ID = 'wikiCommentHistoryMore';
    const HISTORY_MORE_BTN_ID = 'wikiCommentHistoryMoreBtn';

    // Module State
    let currentDoc = null;
    let articleId = null;
    let isAuthenticated = false;
    let loginUrl = '/login';
    let csrfToken = '';
    let csrfHeader = 'X-CSRF-TOKEN';

    let currentPage = 0;
    let hasNext = false;
    let isLoading = false;
    let isMutating = false;
    let loadToken = 0;

    let currentThreads = [];
    let renderedRootIds = new Set();
    let threadCount = 0;
    let commentCount = 0;

    // Revision History State (MS-05E6D)
    let historyActiveCommentId = null;
    let historyCurrentPage = 0;
    let historyHasNext = false;
    let historyIsLoading = false;
    let historyIsLoadingMore = false;
    let historyRequestToken = 0;
    let historyRenderedRevisionNumbers = new Set();
    let historyPreviousFocusedElement = null;
    let keydownHandler = null;

    // Injected implementations for testing
    let injectedFetch = null;
    let injectedConfirm = null;
    let injectedReportModal = null;
    let injectedCommentPresentation = undefined;
    let injectedRelativeTime = undefined;

    // Ephemeral Draft State (UX-DRAFT-01B)
    let rootDraftDebounceTimer = null;
    let rootDraftInputListener = null;
    let rootDraftPageExitListener = null;
    let injectedDraftStore = null;

    // Inline Ephemeral Draft State (UX-DRAFT-01C)
    let activeReplyTargetCommentId = null;
    let activeReplyRootCommentId = null;
    let activeReplyDebounceTimer = null;
    let activeReplyTextareaEl = null;

    let activeEditCommentId = null;
    let activeEditRootCommentId = null;
    let activeEditDebounceTimer = null;
    let activeEditTextareaEl = null;
    let activeEditHasUserTyped = false;
    let activeEditSavedChildNodes = null;
    let activeEditSavedTextContent = '';
    let activeEditContainerEl = null;

    // Deep-link context focus state (MS-05E5C2B)
    let pendingDeepLink = null;
    let highlightTimer = null;
    let highlightedElement = null;

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
     * Resolves the RelativeTime module.
     *
     * @returns {Object|null}
     */
    function resolveRelativeTime() {
        if (injectedRelativeTime !== undefined) {
            return injectedRelativeTime;
        }
        if (typeof window !== 'undefined' && window.RelativeTime) {
            return window.RelativeTime;
        }
        if (typeof globalThis !== 'undefined' && globalThis.RelativeTime) {
            return globalThis.RelativeTime;
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/relative-time.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Sets the injected RelativeTime module (for testing or explicit dependency injection).
     *
     * @param {Object|null} rt
     */
    function setRelativeTime(rt) {
        injectedRelativeTime = rt;
    }

    /**
     * Resets all module internal state.
     */
    function resetState() {
        injectedRelativeTime = undefined;
        clearHighlight();
        pendingDeepLink = null;
        closeActiveMenu(false);
        closeRevisionHistory();
        historyActiveCommentId = null;
        historyCurrentPage = 0;
        historyHasNext = false;
        historyIsLoading = false;
        historyIsLoadingMore = false;
        historyRenderedRevisionNumbers.clear();
        historyPreviousFocusedElement = null;
        if (currentDoc && typeof currentDoc.removeEventListener === 'function' && keydownHandler) {
            currentDoc.removeEventListener('keydown', keydownHandler);
        }
        keydownHandler = null;

        if (rootDraftDebounceTimer) {
            clearTimeout(rootDraftDebounceTimer);
            rootDraftDebounceTimer = null;
        }
        if (activeReplyDebounceTimer) {
            clearTimeout(activeReplyDebounceTimer);
            activeReplyDebounceTimer = null;
        }
        if (activeEditDebounceTimer) {
            clearTimeout(activeEditDebounceTimer);
            activeEditDebounceTimer = null;
        }
        activeReplyTargetCommentId = null;
        activeReplyRootCommentId = null;
        activeReplyTextareaEl = null;
        activeEditCommentId = null;
        activeEditRootCommentId = null;
        activeEditTextareaEl = null;
        activeEditHasUserTyped = false;
        activeEditSavedChildNodes = null;
        activeEditSavedTextContent = '';
        activeEditContainerEl = null;

        if (currentDoc) {
            const els = getElements(currentDoc);
            if (els.composerInputEl && rootDraftInputListener && typeof els.composerInputEl.removeEventListener === 'function') {
                els.composerInputEl.removeEventListener('input', rootDraftInputListener);
            }
        }
        rootDraftInputListener = null;

        if (rootDraftPageExitListener) {
            const win = (currentDoc && currentDoc.defaultView) ? currentDoc.defaultView : (typeof window !== 'undefined' ? window : null);
            if (win && typeof win.removeEventListener === 'function') {
                win.removeEventListener('pagehide', rootDraftPageExitListener);
            }
            rootDraftPageExitListener = null;
        }

        injectedDraftStore = null;

        currentDoc = null;
        articleId = null;
        isAuthenticated = false;
        loginUrl = '/login';
        csrfToken = '';
        csrfHeader = 'X-CSRF-TOKEN';
        currentPage = 0;
        hasNext = false;
        isLoading = false;
        isMutating = false;
        loadToken++;
        currentThreads = [];
        renderedRootIds.clear();
        threadCount = 0;
        commentCount = 0;
        injectedFetch = null;
        injectedConfirm = null;
        injectedReportModal = null;
        injectedCommentPresentation = undefined;
    }

    /**
     * Closes any currently open overflow actions menu popover.
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
     * Constructs normalized overflow action descriptors for a Wiki comment.
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
                className: 'wiki-comment-menu-item wiki-comment-action-btn',
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
                className: 'wiki-comment-menu-item wiki-comment-action-btn wiki-comment-edit-btn',
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
                className: 'wiki-comment-menu-item wiki-comment-action-btn wiki-comment-action-btn--danger wiki-comment-delete-btn',
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
                className: 'wiki-comment-menu-item wiki-comment-action-btn wiki-comment-action-btn--report wiki-comment-report-btn',
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
                legacyPrefix: 'wiki-comment'
            }, documentRef);
        }

        return null;
    }

    /**
     * Executes fetch using injected implementation or global fetch.
     */
    function doFetch(url, options) {
        if (typeof injectedFetch === 'function') {
            return injectedFetch(url, options);
        }
        return fetch(url, options);
    }

    /**
     * Prompts confirmation using injected confirm or global window.confirm.
     */
    function doConfirm(message) {
        if (typeof injectedConfirm === 'function') {
            return injectedConfirm(message);
        }
        if (typeof window !== 'undefined' && typeof window.confirm === 'function') {
            return window.confirm(message);
        }
        return true;
    }

    /**
     * Validates whether a redirect URL is an allowed same-origin security destination.
     * Matches the proven Wiki Save pattern.
     */
    function getValidSecurityRedirectUrl(targetUrl, currentHref) {
        if (!targetUrl || typeof targetUrl !== 'string') {
            return null;
        }
        try {
            const baseHref = currentHref || (typeof window !== 'undefined' && window.location ? window.location.href : 'http://localhost');
            const parsed = new URL(targetUrl, baseHref);
            const currentOrigin = new URL(baseHref).origin;

            if (parsed.origin !== currentOrigin) {
                return null;
            }
            if (parsed.pathname === '/login' || parsed.pathname === '/access-denied') {
                return parsed.href;
            }
            return null;
        } catch (_) {
            return null;
        }
    }

    /**
     * Formats timestamp to localized human-readable string (DD/MM/YYYY HH:mm).
     */
    function formatTimestamp(isoString) {
        const rt = resolveRelativeTime();
        if (rt && typeof rt.formatAbsolute === 'function') {
            return rt.formatAbsolute(isoString);
        }
        return '';
    }

    /**
     * Formats comment count label (e.g. '3 bình luận').
     */
    function formatCommentCount(count) {
        const num = Number(count);
        if (!Number.isSafeInteger(num) || num < 0) {
            return '0 bình luận';
        }
        return num + ' bình luận';
    }

    /**
     * Checks if a comment has been edited.
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
     * Safely clears all children of a container.
     */
    function clearElement(container) {
        if (!container) return;
        if (typeof container.replaceChildren === 'function') {
            container.replaceChildren();
        } else {
            while (container.firstChild) {
                container.removeChild(container.firstChild);
            }
        }
    }

    /**
     * Builds request headers including CSRF token if available.
     */
    function buildHeaders(includeJsonContent = true) {
        const headers = {};
        if (includeJsonContent) {
            headers['Content-Type'] = 'application/json';
        }
        if (csrfHeader && csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        return headers;
    }

    /**
     * Resolves DOM elements for the Wiki discussion section.
     */
    function getElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return {};
        }
        return {
            sectionEl: d.getElementById(SECTION_ID),
            countBadgeEl: d.getElementById(COUNT_BADGE_ID),
            composerFormEl: d.getElementById(ROOT_COMPOSER_FORM_ID),
            composerInputEl: d.getElementById(ROOT_COMPOSER_INPUT_ID),
            composerSubmitEl: d.getElementById(ROOT_COMPOSER_SUBMIT_ID),
            composerErrorEl: d.getElementById(ROOT_COMPOSER_ERROR_ID),
            statusEl: d.getElementById(STATUS_ID),
            threadListEl: d.getElementById(THREAD_LIST_ID),
            footerEl: d.getElementById(FOOTER_ID),
            loadMoreBtnEl: d.getElementById(LOAD_MORE_BTN_ID)
        };
    }

    /**
     * Updates the discussion header count badge authoritatively.
     */
    function updateCountBadge(count, doc) {
        const els = getElements(doc);
        if (els.countBadgeEl) {
            els.countBadgeEl.textContent = formatCommentCount(count);
        }
    }

    /**
     * Displays a neutral user-facing error message in the discussion status region.
     */
    function showDiscussionError(message, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--error';
            els.statusEl.textContent = message;
        }
    }

    /**
     * Clears the discussion status region.
     */
    function clearDiscussionStatus(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status';
        }
    }

    /**
     * Displays a positive user-facing success feedback message in the discussion status region.
     */
    function showDiscussionSuccess(message, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--success';
            els.statusEl.textContent = message;
        }
    }

    /**
     * Resolves the EphemeralDraftStore implementation.
     */
    function getDraftStore() {
        if (injectedDraftStore) {
            return injectedDraftStore;
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
     * Resolves the CommentReportModal implementation.
     */
    function getReportModal() {
        if (injectedReportModal) {
            return injectedReportModal;
        }
        if (typeof window !== 'undefined') {
            if (window.CommentReportModal) return window.CommentReportModal;
            if (window.KiemLai && window.KiemLai.CommentReportModal) return window.KiemLai.CommentReportModal;
        }
        if (typeof globalThis !== 'undefined') {
            if (globalThis.CommentReportModal) return globalThis.CommentReportModal;
            if (globalThis.KiemLai && globalThis.KiemLai.CommentReportModal) return globalThis.KiemLai.CommentReportModal;
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/comment-report-modal.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Builds canonical storage key for the article root comment draft.
     * Schema: kiemlai:draft:wiki-comment:{encodedArticleId}:root
     *
     * @param {string} [artId] - Optional explicit article ID override.
     * @returns {string|null} Canonical storage key or null if ID is invalid/empty.
     */
    function getRootDraftKey(artId) {
        let rawId;
        if (arguments.length > 0) {
            // Explicit argument provided: do NOT fallback to current articleId
            if (artId == null || typeof artId !== 'string') {
                return null;
            }
            rawId = artId.trim();
        } else {
            // No-argument invocation: use active module articleId
            rawId = (typeof articleId === 'string') ? articleId.trim() : '';
        }

        if (!rawId) {
            return null;
        }

        return 'kiemlai:draft:wiki-comment:' + encodeURIComponent(rawId) + ':root';
    }

    /**
     * Saves the current root comment draft into ephemeral storage.
     */
    function saveRootDraft(doc) {
        const d = doc || currentDoc;
        const key = getRootDraftKey();
        if (!key) return false;
        const els = getElements(d);
        const text = els.composerInputEl ? els.composerInputEl.value : '';
        const store = getDraftStore();
        if (!store || typeof store.save !== 'function') return false;
        return store.save(key, text);
    }

    /**
     * Restores saved draft into root composer if input is currently empty.
     */
    function restoreRootDraft(doc) {
        const d = doc || currentDoc;
        const key = getRootDraftKey();
        if (!key) return null;
        const els = getElements(d);
        if (!els.composerInputEl) return null;
        if (typeof els.composerInputEl.value === 'string' && els.composerInputEl.value.trim().length > 0) {
            return null; // Don't overwrite existing user text
        }
        const store = getDraftStore();
        if (!store || typeof store.load !== 'function') return null;
        const draft = store.load(key);
        if (draft && typeof draft === 'string') {
            els.composerInputEl.value = draft;
            return draft;
        }
        return null;
    }

    /**
     * Removes the root comment draft from ephemeral storage.
     */
    function removeRootDraft(artId) {
        const key = arguments.length > 0 ? getRootDraftKey(artId) : getRootDraftKey();
        if (!key) return;
        const store = getDraftStore();
        if (store && typeof store.remove === 'function') {
            store.remove(key);
        }
    }

    /**
     * Builds canonical storage key for a reply draft.
     * Schema: kiemlai:draft:wiki-comment:{encodedArticleId}:reply:{encodedTargetCommentId}
     *
     * @param {string} targetCommentId - Target comment ID being replied to.
     * @param {string} [artId] - Optional explicit article ID override.
     * @returns {string|null} Canonical storage key or null if invalid.
     */
    function getReplyDraftKey(targetCommentId, artId) {
        if (typeof targetCommentId !== 'string') return null;
        const rawTargetId = targetCommentId.trim();
        if (!rawTargetId) return null;

        let rawArtId;
        if (arguments.length > 1) {
            if (artId == null || typeof artId !== 'string') return null;
            rawArtId = artId.trim();
        } else {
            rawArtId = (typeof articleId === 'string') ? articleId.trim() : '';
        }
        if (!rawArtId) return null;

        return 'kiemlai:draft:wiki-comment:' + encodeURIComponent(rawArtId) + ':reply:' + encodeURIComponent(rawTargetId);
    }

    /**
     * Builds canonical storage key for an edit draft.
     * Schema: kiemlai:draft:wiki-comment:{encodedArticleId}:edit:{encodedCommentId}
     *
     * @param {string} commentId - Comment ID being edited.
     * @param {string} [artId] - Optional explicit article ID override.
     * @returns {string|null} Canonical storage key or null if invalid.
     */
    function getEditDraftKey(commentId, artId) {
        if (typeof commentId !== 'string') return null;
        const rawCommentId = commentId.trim();
        if (!rawCommentId) return null;

        let rawArtId;
        if (arguments.length > 1) {
            if (artId == null || typeof artId !== 'string') return null;
            rawArtId = artId.trim();
        } else {
            rawArtId = (typeof articleId === 'string') ? articleId.trim() : '';
        }
        if (!rawArtId) return null;

        return 'kiemlai:draft:wiki-comment:' + encodeURIComponent(rawArtId) + ':edit:' + encodeURIComponent(rawCommentId);
    }

    /**
     * Builds canonical storage key for the active inline composer marker.
     * Schema: kiemlai:draft:wiki-comment:{encodedArticleId}:active-inline
     *
     * @param {string} [artId] - Optional explicit article ID override.
     * @returns {string|null} Canonical storage key or null if invalid.
     */
    function getActiveInlineMarkerKey(artId) {
        let rawArtId;
        if (arguments.length > 0) {
            if (artId == null || typeof artId !== 'string') return null;
            rawArtId = artId.trim();
        } else {
            rawArtId = (typeof articleId === 'string') ? articleId.trim() : '';
        }
        if (!rawArtId) return null;

        return 'kiemlai:draft:wiki-comment:' + encodeURIComponent(rawArtId) + ':active-inline';
    }

    /**
     * Saves active inline composer marker to ephemeral storage.
     */
    function saveActiveInlineMarker(markerData, artId) {
        const key = arguments.length > 1 ? getActiveInlineMarkerKey(artId) : getActiveInlineMarkerKey();
        if (!key || !markerData || typeof markerData !== 'object') return false;
        const store = getDraftStore();
        if (!store || typeof store.save !== 'function') return false;
        try {
            return store.save(key, JSON.stringify(markerData));
        } catch (_) {
            return false;
        }
    }

    /**
     * Loads active inline composer marker from ephemeral storage.
     */
    function loadActiveInlineMarker(artId) {
        const key = arguments.length > 0 ? getActiveInlineMarkerKey(artId) : getActiveInlineMarkerKey();
        if (!key) return null;
        const store = getDraftStore();
        if (!store || typeof store.load !== 'function') return null;
        try {
            const raw = store.load(key);
            if (!raw || typeof raw !== 'string') return null;
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object') {
                return parsed;
            }
            if (store && typeof store.remove === 'function') {
                store.remove(key);
            }
            return null;
        } catch (_) {
            if (store && typeof store.remove === 'function') {
                store.remove(key);
            }
            return null;
        }
    }

    /**
     * Removes active inline composer marker from ephemeral storage.
     */
    function removeActiveInlineMarker(artId) {
        const key = arguments.length > 0 ? getActiveInlineMarkerKey(artId) : getActiveInlineMarkerKey();
        if (!key) return;
        const store = getDraftStore();
        if (store && typeof store.remove === 'function') {
            store.remove(key);
        }
    }

    /**
     * Saves reply draft into ephemeral storage.
     */
    function saveReplyDraft(targetCommentId, text, artId) {
        const key = arguments.length > 2 ? getReplyDraftKey(targetCommentId, artId) : getReplyDraftKey(targetCommentId);
        if (!key) return false;
        const store = getDraftStore();
        if (!store || typeof store.save !== 'function') return false;
        return store.save(key, text);
    }

    /**
     * Loads reply draft from ephemeral storage.
     */
    function loadReplyDraft(targetCommentId, artId) {
        const key = arguments.length > 1 ? getReplyDraftKey(targetCommentId, artId) : getReplyDraftKey(targetCommentId);
        if (!key) return null;
        const store = getDraftStore();
        if (!store || typeof store.load !== 'function') return null;
        const draft = store.load(key);
        return (typeof draft === 'string') ? draft : null;
    }

    /**
     * Removes reply draft from ephemeral storage.
     */
    function removeReplyDraft(targetCommentId, artId) {
        const key = arguments.length > 1 ? getReplyDraftKey(targetCommentId, artId) : getReplyDraftKey(targetCommentId);
        if (!key) return;
        const store = getDraftStore();
        if (store && typeof store.remove === 'function') {
            store.remove(key);
        }
    }

    /**
     * Saves edit draft into ephemeral storage.
     */
    function saveEditDraft(commentId, text, artId) {
        const key = arguments.length > 2 ? getEditDraftKey(commentId, artId) : getEditDraftKey(commentId);
        if (!key) return false;
        const store = getDraftStore();
        if (!store || typeof store.save !== 'function') return false;
        return store.save(key, text);
    }

    /**
     * Loads edit draft from ephemeral storage.
     */
    function loadEditDraft(commentId, artId) {
        const key = arguments.length > 1 ? getEditDraftKey(commentId, artId) : getEditDraftKey(commentId);
        if (!key) return null;
        const store = getDraftStore();
        if (!store || typeof store.load !== 'function') return null;
        const draft = store.load(key);
        return (typeof draft === 'string') ? draft : null;
    }

    /**
     * Removes edit draft from ephemeral storage.
     */
    function removeEditDraft(commentId, artId) {
        const key = arguments.length > 1 ? getEditDraftKey(commentId, artId) : getEditDraftKey(commentId);
        if (!key) return;
        const store = getDraftStore();
        if (store && typeof store.remove === 'function') {
            store.remove(key);
        }
    }

    /**
     * Finds comment context (comment item and authoritative thread root ID) by comment ID.
     *
     * @param {string} commentId - Comment ID to search for across loaded threads.
     * @returns {{comment: object, rootCommentId: string}|null}
     */
    function findCommentContext(commentId) {
        if (commentId == null) return null;
        const strId = String(commentId).trim();
        for (let i = 0; i < currentThreads.length; i++) {
            const t = currentThreads[i];
            if (!t || !t.root) continue;
            const rootId = String(t.root.id);
            if (rootId === strId) {
                return {
                    comment: t.root,
                    rootCommentId: rootId
                };
            }
            if (Array.isArray(t.replies)) {
                for (let j = 0; j < t.replies.length; j++) {
                    const rep = t.replies[j];
                    if (rep && String(rep.id) === strId) {
                        return {
                            comment: rep,
                            rootCommentId: rootId
                        };
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds comment data across current threads in memory by comment ID.
     */
    function findCommentData(commentId) {
        const ctx = findCommentContext(commentId);
        return ctx ? ctx.comment : null;
    }

    /**
     * Flushes currently active reply draft to ephemeral storage.
     */
    function flushActiveReplyDraft(doc) {
        if (!activeReplyTargetCommentId || !activeReplyTextareaEl) return false;
        const text = activeReplyTextareaEl.value;
        const saved = saveReplyDraft(activeReplyTargetCommentId, text);
        if (saved) {
            saveActiveInlineMarker({
                type: 'reply',
                targetCommentId: activeReplyTargetCommentId,
                rootCommentId: activeReplyRootCommentId || activeReplyTargetCommentId
            });
        }
        return saved;
    }

    /**
     * Flushes currently active edit draft to ephemeral storage if user has typed.
     */
    function flushActiveEditDraft(doc) {
        if (!activeEditCommentId || !activeEditTextareaEl) return false;
        const text = activeEditTextareaEl.value;
        if (!activeEditHasUserTyped) {
            const commentData = findCommentData(activeEditCommentId);
            const initialBody = (commentData && commentData.body) ? commentData.body : '';
            if (text === initialBody) {
                return false; // Still unchanged server body
            }
            activeEditHasUserTyped = true;
        }
        const saved = saveEditDraft(activeEditCommentId, text);
        if (saved) {
            saveActiveInlineMarker({
                type: 'edit',
                commentId: activeEditCommentId,
                rootCommentId: activeEditRootCommentId || activeEditCommentId
            });
        }
        return saved;
    }

    /**
     * Reopens active inline composer and restores draft after page reload or feed render.
     */
    function restoreActiveInlineComposer(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d || !articleId || !isAuthenticated) return;

        const marker = loadActiveInlineMarker();
        if (!marker || typeof marker !== 'object') return;

        if (marker.type === 'reply') {
            if (typeof marker.targetCommentId !== 'string' || !marker.targetCommentId.trim() ||
                typeof marker.rootCommentId !== 'string' || !marker.rootCommentId.trim()) {
                removeActiveInlineMarker();
                return;
            }
            const targetCommentId = marker.targetCommentId.trim();

            const ctx = findCommentContext(targetCommentId);
            if (!ctx) {
                return; // Target comment not loaded yet
            }
            const commentData = ctx.comment;
            const actualRootId = ctx.rootCommentId;

            if (commentData.tombstone === true) {
                removeReplyDraft(targetCommentId);
                removeActiveInlineMarker();
                return;
            }

            const slot = d.querySelector('[data-reply-slot="' + targetCommentId + '"]');
            if (!slot || slot.querySelector('.wiki-inline-composer')) return;

            const authorName = (commentData.author && commentData.author.displayName) ? commentData.author.displayName : '';
            openReplyComposer(targetCommentId, actualRootId, authorName, d);

        } else if (marker.type === 'edit') {
            if (typeof marker.commentId !== 'string' || !marker.commentId.trim() ||
                typeof marker.rootCommentId !== 'string' || !marker.rootCommentId.trim()) {
                removeActiveInlineMarker();
                return;
            }
            const commentId = marker.commentId.trim();

            const ctx = findCommentContext(commentId);
            if (!ctx) {
                return; // Comment not loaded yet
            }
            const commentData = ctx.comment;
            const actualRootId = ctx.rootCommentId;

            if (commentData.tombstone === true || commentData.canEdit !== true) {
                removeEditDraft(commentId);
                removeActiveInlineMarker();
                return;
            }

            const bodyContainer = d.querySelector('[data-body-container="' + commentId + '"]');
            if (!bodyContainer || bodyContainer.querySelector('form')) return;

            openEditComposer(commentId, actualRootId, d);

        } else {
            removeActiveInlineMarker();
        }
    }

    /**
     * Redirects unauthenticated guest to login URL safely.
     */
    function redirectToLogin(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const currentHref = (d && d.defaultView && d.defaultView.location) ? d.defaultView.location.href : (typeof window !== 'undefined' && window.location ? window.location.href : '');
        const validUrl = getValidSecurityRedirectUrl(loginUrl, currentHref);
        const destination = validUrl || loginUrl || '/login';
        if (typeof window !== 'undefined' && window.location) {
            window.location.href = destination;
        } else if (d && d.defaultView && d.defaultView.location) {
            d.defaultView.location.href = destination;
        }
    }

    /**
     * Authoritatively refreshes total article discussion metrics.
     */
    async function refreshMetrics(doc) {
        if (!articleId) return;
        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments?page=0&size=1';
            const res = await doFetch(url, { method: 'GET' });
            if (res && res.status === 200) {
                const data = await res.json();
                if (data && typeof data.commentCount === 'number') {
                    commentCount = data.commentCount;
                    threadCount = data.threadCount;
                    updateCountBadge(commentCount, doc);
                }
            }
        } catch (_) {
            // Ignore metrics background failure
        }
    }

    /**
     * Resolves the window object associated with a document.
     */
    function resolveWindow(doc) {
        if (doc && doc.defaultView) {
            return doc.defaultView;
        }
        if (typeof window !== 'undefined') {
            return window;
        }
        return null;
    }

    /**
     * Extracts commentId and threadId deep-link parameters from options or window URL search.
     *
     * @param {Document} [doc]
     * @param {Object} [options]
     * @returns {{ commentId: string, threadId: string }|null}
     */
    function extractDeepLinkParams(doc, options) {
        let commentId = null;
        let threadId = null;

        if (options && typeof options === 'object') {
            if (options.commentId && options.threadId) {
                commentId = String(options.commentId).trim();
                threadId = String(options.threadId).trim();
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
                        if (c && t && c.trim() && t.trim()) {
                            commentId = String(c).trim();
                            threadId = String(t).trim();
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
                        }
                    }
                } catch (_) {}
            }
        }

        if (commentId && threadId) {
            return {
                commentId: commentId,
                threadId: threadId
            };
        }
        return null;
    }

    /**
     * Removes commentId and threadId from URL query parameters via replaceState.
     * Preserves unrelated query parameters and hash (e.g. #wikiDiscussion).
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
     * Applies restored target highlight to target element with accessible focus.
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
                targetEl.focus({ preventScroll: true });
            } catch (_) {
                try {
                    targetEl.focus();
                } catch (_) {}
            }
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
     * Falls back smoothly to Wiki discussion container.
     *
     * @param {Document} [doc]
     */
    function fallbackToDiscussion(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        const sectionEl = els.sectionEl || (d && typeof d.getElementById === 'function' ? d.getElementById(SECTION_ID) : null);
        if (!sectionEl) return;

        const hasTabIndex = typeof sectionEl.hasAttribute === 'function'
            ? sectionEl.hasAttribute('tabindex')
            : (sectionEl.getAttribute && sectionEl.getAttribute('tabindex') !== null);

        if (!hasTabIndex) {
            if (typeof sectionEl.setAttribute === 'function') {
                sectionEl.setAttribute('tabindex', '-1');
            } else {
                sectionEl.tabIndex = -1;
            }
        }

        if (typeof sectionEl.focus === 'function') {
            try {
                sectionEl.focus({ preventScroll: true });
            } catch (_) {
                try {
                    sectionEl.focus();
                } catch (_) {}
            }
        }

        if (typeof sectionEl.scrollIntoView === 'function') {
            try {
                sectionEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
            } catch (_) {
                sectionEl.scrollIntoView();
            }
        }
    }

    /**
     * Locates a comment element (root or reply) within a container.
     *
     * @param {Element} container
     * @param {string} strCommentId
     * @param {string} strThreadId
     * @returns {Element|null}
     */
    function findCommentElement(container, strCommentId, strThreadId) {
        if (!container || !strCommentId) return null;
        const isRoot = strCommentId === strThreadId;
        if (isRoot) {
            if (typeof container.querySelector === 'function') {
                try {
                    const el = container.querySelector('[data-comment-id="' + strCommentId + '"]');
                    if (el) return el;
                } catch (_) {}
            }
            if (container.getAttribute && container.getAttribute('data-comment-id') === strCommentId) {
                return container;
            }
            return container;
        }

        if (typeof container.querySelector === 'function') {
            try {
                const el = container.querySelector('[data-reply-id="' + strCommentId + '"]')
                    || container.querySelector('[data-comment-id="' + strCommentId + '"]');
                if (el) return el;
            } catch (_) {}
        }
        return null;
    }

    /**
     * Resolves and focuses exact comment or reply in Wiki discussion feed (MS-05E5C2B).
     *
     * @param {Object} deepLink
     * @param {string} deepLink.commentId
     * @param {string} deepLink.threadId
     * @param {Document} [doc]
     * @param {number} [token]
     * @returns {Promise<void>}
     */
    async function resolveDeepLink(deepLink, doc, token) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!deepLink || !d || !articleId) {
            return;
        }

        const strCommentId = String(deepLink.commentId).trim();
        const strThreadId = String(deepLink.threadId).trim();
        if (!strCommentId || !strThreadId) {
            return;
        }

        const targetArticleId = articleId;
        let isStale = false;

        try {
            const els = getElements(d);
            if (!els.sectionEl || !els.threadListEl) {
                return;
            }

            // 1. Check if thread is already rendered in DOM
            let threadCard = els.threadListEl.querySelector('[data-thread-id="' + strThreadId + '"]');
            if (threadCard) {
                const targetEl = findCommentElement(threadCard, strCommentId, strThreadId);
                if (targetEl) {
                    applyHighlight(targetEl);
                    return;
                }
                fallbackToDiscussion(d);
                return;
            }

            // 2. Thread is off-page. Request single thread from server:
            // GET /api/wiki/articles/{articleId}/comments/{rootCommentId}/thread
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(strThreadId) + '/thread';
            const res = await doFetch(url, { method: 'GET' });

            if ((token && token !== loadToken) || targetArticleId !== articleId) {
                isStale = true;
                return;
            }

            if (!res || res.status !== 200) {
                fallbackToDiscussion(d);
                return;
            }

            const threadData = await res.json();
            if ((token && token !== loadToken) || targetArticleId !== articleId) {
                isStale = true;
                return;
            }

            if (!threadData || !threadData.root) {
                fallbackToDiscussion(d);
                return;
            }

            const isRootDeleted = threadData.root.tombstone === true || threadData.root.status === 'DELETED';
            if (isRootDeleted) {
                fallbackToDiscussion(d);
                return;
            }

            // Duplicate safety check before inserting:
            let existingCard = els.threadListEl.querySelector('[data-thread-id="' + strThreadId + '"]');
            if (!existingCard) {
                const threadEl = renderThread(threadData, d);
                if (threadEl) {
                    if (els.statusEl && els.statusEl.classList.contains('wiki-discussion-status--empty')) {
                        clearElement(els.statusEl);
                        els.statusEl.className = 'wiki-discussion-status';
                    }

                    const firstChild = els.threadListEl.firstChild || (els.threadListEl.childNodes && els.threadListEl.childNodes[0]) || null;
                    if (firstChild) {
                        els.threadListEl.insertBefore(threadEl, firstChild);
                    } else {
                        els.threadListEl.appendChild(threadEl);
                    }

                    renderedRootIds.add(strThreadId);
                    currentThreads.unshift(threadData);
                    existingCard = threadEl;
                }
            }

            if (!existingCard) {
                fallbackToDiscussion(d);
                return;
            }

            const targetEl = findCommentElement(existingCard, strCommentId, strThreadId);
            if (targetEl) {
                applyHighlight(targetEl);
            } else {
                fallbackToDiscussion(d);
            }
        } catch (_) {
            if ((token && token !== loadToken) || targetArticleId !== articleId) {
                isStale = true;
                return;
            }
            fallbackToDiscussion(d);
        } finally {
            if (!isStale && (!token || token === loadToken) && targetArticleId === articleId) {
                scrubDeepLinkParams(d);
            }
        }
    }

    /**
     * Loads the Wiki discussion feed from the server.
     */
    async function loadDiscussionFeed(page = 0, append = false, doc = null, force = false) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (!els.sectionEl || !articleId) {
            return;
        }

        if (isLoading && append && !force) {
            return;
        }

        const currentToken = ++loadToken;
        isLoading = true;

        if (els.threadListEl) {
            els.threadListEl.setAttribute('aria-busy', 'true');
        }

        if (!append && els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status';
            els.statusEl.textContent = 'Đang tải thảo luận...';
        }

        if (els.loadMoreBtnEl) {
            els.loadMoreBtnEl.disabled = true;
        }

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments?page=' + page + '&size=' + DEFAULT_PAGE_SIZE;
            const response = await doFetch(url, { method: 'GET' });

            if (currentToken !== loadToken) {
                return; // Discard stale response
            }

            if (!response || response.status !== 200) {
                if (els.statusEl) {
                    clearElement(els.statusEl);
                    els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--error';
                    els.statusEl.textContent = 'Không thể tải bình luận. Vui lòng thử lại sau.';
                }
                if (pendingDeepLink) {
                    pendingDeepLink = null;
                    fallbackToDiscussion(d);
                    scrubDeepLinkParams(d);
                }
                return;
            }

            const data = await response.json();
            if (currentToken !== loadToken) {
                return;
            }

            currentPage = data.page || 0;
            hasNext = Boolean(data.hasNext);
            threadCount = data.threadCount || 0;
            commentCount = data.commentCount || 0;

            updateCountBadge(commentCount, d);

            if (!append) {
                currentThreads = [];
                renderedRootIds.clear();
                if (els.threadListEl) {
                    clearElement(els.threadListEl);
                }
            }

            const newThreads = Array.isArray(data.threads) ? data.threads : [];

            if (newThreads.length === 0 && !append) {
                if (els.statusEl) {
                    clearElement(els.statusEl);
                    els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--empty';
                    els.statusEl.textContent = 'Chưa có bình luận nào. Hãy là người đầu tiên thảo luận!';
                }
                if (els.footerEl) {
                    els.footerEl.hidden = true;
                }
                if (pendingDeepLink) {
                    const dl = pendingDeepLink;
                    pendingDeepLink = null;
                    await resolveDeepLink(dl, d, currentToken);
                }
                return;
            }

            // Populated threads
            if (els.statusEl) {
                clearElement(els.statusEl);
                els.statusEl.className = 'wiki-discussion-status';
            }

            if (els.threadListEl) {
                for (let i = 0; i < newThreads.length; i++) {
                    const t = newThreads[i];
                    if (!t || !t.root || !t.root.id) continue;
                    const rootIdStr = String(t.root.id);
                    if (renderedRootIds.has(rootIdStr)) {
                        continue; // Prevent duplicate roots
                    }

                    const threadEl = renderThread(t, d);
                    if (threadEl) {
                        renderedRootIds.add(rootIdStr);
                        currentThreads.push(t);
                        els.threadListEl.appendChild(threadEl);
                    }
                }
            }

            if (els.footerEl) {
                els.footerEl.hidden = !hasNext;
            }

            restoreActiveInlineComposer(d);

            if (pendingDeepLink) {
                const dl = pendingDeepLink;
                pendingDeepLink = null;
                await resolveDeepLink(dl, d, currentToken);
            }
        } catch (err) {
            if (currentToken !== loadToken) return;
            if (els.statusEl) {
                clearElement(els.statusEl);
                els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--error';
                els.statusEl.textContent = 'Lỗi kết nối khi tải thảo luận. Vui lòng thử lại sau.';
            }
            if (pendingDeepLink) {
                pendingDeepLink = null;
                fallbackToDiscussion(d);
                scrubDeepLinkParams(d);
            }
        } finally {
            if (currentToken === loadToken) {
                isLoading = false;
                if (els.threadListEl) {
                    els.threadListEl.setAttribute('aria-busy', 'false');
                }
                if (els.loadMoreBtnEl) {
                    els.loadMoreBtnEl.disabled = false;
                }
            }
        }
    }

    /**
     * Authoritatively refreshes a single thread by rootCommentId in-place.
     */
    async function refreshThread(rootCommentId, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!articleId || !rootCommentId || !d) return;

        const strRootId = String(rootCommentId);
        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(strRootId) + '/thread';
            const res = await doFetch(url, { method: 'GET' });

            const els = getElements(d);
            const threadEl = d.querySelector('[data-thread-id="' + strRootId + '"]');

            if (res && res.status === 200) {
                const freshThread = await res.json();
                if (!freshThread || !freshThread.root) return;

                // Update internal thread list
                const idx = currentThreads.findIndex(t => t && t.root && String(t.root.id) === strRootId);
                if (idx >= 0) {
                    currentThreads[idx] = freshThread;
                }

                if (threadEl) {
                    const newThreadEl = renderThread(freshThread, d);
                    if (newThreadEl && threadEl.parentNode) {
                        threadEl.parentNode.replaceChild(newThreadEl, threadEl);
                    } else if (!newThreadEl && threadEl.parentNode) {
                        threadEl.parentNode.removeChild(threadEl);
                        renderedRootIds.delete(strRootId);
                        currentThreads = currentThreads.filter(t => t && t.root && String(t.root.id) !== strRootId);
                    }
                }
                restoreActiveInlineComposer(d);
            } else if (res && res.status === 404) {
                // Thread is deleted / pruned
                if (threadEl && threadEl.parentNode) {
                    threadEl.parentNode.removeChild(threadEl);
                }
                renderedRootIds.delete(strRootId);
                currentThreads = currentThreads.filter(t => t && t.root && String(t.root.id) !== strRootId);
            } else {
                showDiscussionError('Không thể cập nhật thảo luận. Vui lòng tải lại trang.', d);
            }
        } catch (err) {
            showDiscussionError('Lỗi kết nối khi cập nhật thảo luận. Vui lòng thử lại.', d);
        }
    }

    /**
     * Resolves the immediate parent author display name for a flat reply.
     */
    function resolveImmediateParentDisplayName(reply, rootItem, replies) {
        if (!reply || !reply.parentCommentId || !rootItem) {
            return null;
        }
        const strParentId = String(reply.parentCommentId).trim();
        const strRootId = String(rootItem.id).trim();

        if (strParentId === strRootId) {
            // Replying directly to root
            return null; // Root reply doesn't need @RootAuthor mention
        }

        if (Array.isArray(replies)) {
            for (let i = 0; i < replies.length; i++) {
                const cand = replies[i];
                if (cand && String(cand.id).trim() === strParentId) {
                    if (cand.tombstone !== true && cand.status !== 'DELETED' && cand.author && cand.author.displayName) {
                        return cand.author.displayName.trim() || null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Renders a comment item (root or reply, active or tombstone) using CommentPresentation.
     * Returns null if CommentPresentation is unavailable.
     *
     * @param {Object} comment Comment DTO
     * @param {string|number} rootCommentId Root comment ID
     * @param {Object} [thread] Thread object containing root and replies
     * @param {boolean} [isReply=false] True if rendering a reply
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function renderComment(comment, rootCommentId, thread, isReply, doc) {
        const presentation = resolveCommentPresentation();
        if (!presentation || typeof presentation.renderComment !== 'function') {
            return null;
        }

        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        if (!comment || typeof comment !== 'object') {
            return null;
        }

        const strCommentId = comment.id != null ? String(comment.id) : '';
        const strRootId = rootCommentId != null ? String(rootCommentId) : strCommentId;
        const isTombstone = comment.tombstone === true || comment.status === 'DELETED';

        // Tombstone branch
        if (isTombstone) {
            const tombstoneAttrs = {};
            if (strCommentId) {
                tombstoneAttrs['data-comment-id'] = strCommentId;
                if (isReply) {
                    tombstoneAttrs['data-reply-id'] = strCommentId;
                }
            }

            const tombstoneDescriptor = {
                id: strCommentId,
                tag: 'div',
                legacyPrefix: 'wiki-comment',
                className: (isReply ? 'wiki-comment--reply' : 'wiki-comment--root') + ' is-tombstone',
                attributes: tombstoneAttrs,
                tombstone: true,
                tombstoneContent: 'Bình luận đã bị xóa.'
            };

            return presentation.renderComment(tombstoneDescriptor, d);
        }

        // Active comment
        const authorUserId = (comment.author && comment.author.userId) || comment.authorUserId;
        const authorDisplayName = (comment.author && typeof comment.author.displayName === 'string')
            ? comment.author.displayName.trim()
            : '';
        const isEdited = isCommentEdited(comment);
        const canEdit = comment.canEdit === true;
        const canDelete = comment.canDelete === true;
        const canReport = comment.status !== 'DELETED' && !canEdit && !canDelete;

        const cardAttrs = {};
        if (strCommentId) {
            cardAttrs['data-comment-id'] = strCommentId;
            if (isReply) {
                cardAttrs['data-reply-id'] = strCommentId;
            }
        }
        if (authorUserId) {
            cardAttrs['data-author-user-id'] = String(authorUserId);
        }

        const overflowDescriptors = buildOverflowActionDescriptors({
            commentId: strCommentId,
            replyId: isReply ? strCommentId : undefined,
            rootCommentId: strRootId,
            isEdited: isEdited,
            canEdit: canEdit,
            canDelete: canDelete,
            canReport: canReport
        });

        const primaryActions = strCommentId ? [
            {
                key: 'reply',
                label: 'Phản hồi',
                className: 'wiki-comment-action-btn wiki-comment-reply-btn',
                attributes: {
                    'data-action': 'reply',
                    'data-comment-id': strCommentId,
                    'data-root-id': strRootId,
                    ...(authorDisplayName ? { 'data-author-name': authorDisplayName } : {})
                }
            }
        ] : [];

        const commentDescriptor = {
            id: strCommentId,
            tag: 'div',
            legacyPrefix: 'wiki-comment',
            className: isReply ? 'wiki-comment--reply' : 'wiki-comment--root',
            attributes: cardAttrs,
            tombstone: false,
            author: comment.author,
            createdAt: comment.createdAt,
            edited: isEdited,
            body: function (bodyEl, bodyDoc) {
                const targetDoc = bodyDoc || d;
                bodyEl.setAttribute('data-body-container', strCommentId);

                if (isReply) {
                    const immediateParentName = thread
                        ? resolveImmediateParentDisplayName(comment, thread.root, thread.replies)
                        : null;
                    if (immediateParentName) {
                        const mentionSpan = targetDoc.createElement('span');
                        mentionSpan.className = 'wiki-comment-reply-mention';
                        mentionSpan.textContent = '@' + immediateParentName;
                        bodyEl.appendChild(mentionSpan);

                        const textSpan = targetDoc.createElement('span');
                        textSpan.className = 'wiki-comment-reply-text';
                        textSpan.textContent = comment.body || '';
                        bodyEl.appendChild(textSpan);
                    } else {
                        bodyEl.textContent = comment.body || '';
                    }
                } else {
                    bodyEl.textContent = comment.body || '';
                }
            },
            overflowActions: overflowDescriptors.length > 0 ? overflowDescriptors : null,
            primaryActions: primaryActions
        };

        const commentEl = presentation.renderComment(commentDescriptor, d);
        if (!commentEl) {
            return null;
        }

        // Inline reply composer slot (attached for active comments)
        const replyComposerContainer = d.createElement('div');
        replyComposerContainer.className = 'wiki-reply-composer-slot';
        replyComposerContainer.setAttribute('data-reply-slot', strCommentId);
        commentEl.appendChild(replyComposerContainer);

        return commentEl;
    }

    /**
     * Renders a full thread card (root + flat replies container).
     * Suppresses deleted root comments completely.
     * Returns null if CommentPresentation is unavailable or if root is deleted.
     *
     * @param {Object} thread
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function renderThread(thread, doc) {
        if (!thread || !thread.root) {
            return null;
        }

        const isRootDeleted = thread.root.tombstone === true || thread.root.status === 'DELETED';
        if (isRootDeleted) {
            return null;
        }

        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        const strRootId = String(thread.root.id);
        const rootEl = renderComment(thread.root, strRootId, thread, false, d);
        if (!rootEl) {
            return null;
        }

        const threadArticle = d.createElement('article');
        threadArticle.className = 'wiki-thread';
        threadArticle.setAttribute('data-thread-id', strRootId);
        threadArticle.setAttribute('data-root-id', strRootId);

        threadArticle.appendChild(rootEl);

        // Render replies container (flat level 1)
        const repliesContainer = d.createElement('div');
        repliesContainer.className = 'wiki-thread-replies';
        repliesContainer.setAttribute('data-thread-replies', strRootId);

        const replies = Array.isArray(thread.replies) ? thread.replies : [];
        for (let i = 0; i < replies.length; i++) {
            const rep = replies[i];
            if (!rep || !rep.id) continue;
            const replyEl = renderComment(rep, strRootId, thread, true, d);
            if (replyEl) {
                repliesContainer.appendChild(replyEl);
            }
        }

        threadArticle.appendChild(repliesContainer);
        return threadArticle;
    }

    /**
     * Opens an inline reply composer under a specific comment.
     */
    function openReplyComposer(targetCommentId, rootCommentId, authorName, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        // Close any existing open inline composers first (auto-flushes dirty draft)
        closeAllInlineComposers(d);

        const slot = d.querySelector('[data-reply-slot="' + targetCommentId + '"]');
        if (!slot) return;

        const strTargetId = String(targetCommentId);
        const strRootId = String(rootCommentId);

        const composerBox = d.createElement('div');
        composerBox.className = 'wiki-inline-composer';

        const form = d.createElement('form');
        form.className = 'wiki-comment-composer-form';

        const label = d.createElement('label');
        label.className = 'visually-hidden';
        label.textContent = 'Nội dung phản hồi';
        const textareaId = 'wikiReplyInput_' + strTargetId;
        label.setAttribute('for', textareaId);
        form.appendChild(label);

        const textarea = d.createElement('textarea');
        textarea.id = textareaId;
        textarea.className = 'wiki-comment-textarea';
        textarea.rows = 2;
        textarea.placeholder = authorName ? ('Trả lời @' + authorName + '...') : 'Viết phản hồi...';
        textarea.maxLength = 2000;

        // Restore existing draft if present
        const existingDraft = loadReplyDraft(strTargetId);
        if (existingDraft && typeof existingDraft === 'string') {
            textarea.value = existingDraft;
        }

        form.appendChild(textarea);

        // Update active marker & module state
        saveActiveInlineMarker({ type: 'reply', targetCommentId: strTargetId, rootCommentId: strRootId });
        activeReplyTargetCommentId = strTargetId;
        activeReplyRootCommentId = strRootId;
        activeReplyTextareaEl = textarea;

        // Attach input listener with ~400ms debounce
        textarea.addEventListener('input', function () {
            if (activeReplyDebounceTimer) {
                clearTimeout(activeReplyDebounceTimer);
            }
            activeReplyDebounceTimer = setTimeout(function () {
                const saved = saveReplyDraft(strTargetId, textarea.value);
                if (saved) {
                    saveActiveInlineMarker({ type: 'reply', targetCommentId: strTargetId, rootCommentId: strRootId });
                }
                activeReplyDebounceTimer = null;
            }, 400);
        });

        const footer = d.createElement('div');
        footer.className = 'wiki-comment-composer-footer';

        const errorSpan = d.createElement('span');
        errorSpan.className = 'wiki-comment-composer-error';
        errorSpan.setAttribute('role', 'alert');
        errorSpan.hidden = true;
        footer.appendChild(errorSpan);

        const actionsDiv = d.createElement('div');
        actionsDiv.className = 'wiki-comment-composer-actions';

        const cancelBtn = d.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'wiki-comment-btn wiki-comment-btn--secondary';
        cancelBtn.textContent = 'Hủy';
        cancelBtn.addEventListener('click', function () {
            if (activeReplyDebounceTimer) {
                clearTimeout(activeReplyDebounceTimer);
                activeReplyDebounceTimer = null;
            }
            removeReplyDraft(strTargetId);
            const marker = loadActiveInlineMarker();
            if (marker && marker.type === 'reply' && String(marker.targetCommentId) === strTargetId) {
                removeActiveInlineMarker();
            }
            if (String(activeReplyTargetCommentId) === strTargetId) {
                activeReplyTargetCommentId = null;
                activeReplyRootCommentId = null;
                activeReplyTextareaEl = null;
            }
            clearElement(slot);
        });
        actionsDiv.appendChild(cancelBtn);

        const submitBtn = d.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = 'wiki-comment-btn wiki-comment-btn--primary';
        submitBtn.textContent = 'Gửi phản hồi';
        actionsDiv.appendChild(submitBtn);

        footer.appendChild(actionsDiv);
        form.appendChild(footer);

        form.addEventListener('submit', async function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            const body = textarea.value ? textarea.value.trim() : '';
            if (!body) {
                errorSpan.textContent = 'Vui lòng nhập nội dung phản hồi.';
                errorSpan.hidden = false;
                textarea.focus();
                return;
            }

            if (!isAuthenticated) {
                if (activeReplyDebounceTimer) {
                    clearTimeout(activeReplyDebounceTimer);
                    activeReplyDebounceTimer = null;
                }
                saveReplyDraft(strTargetId, textarea.value);
                saveActiveInlineMarker({ type: 'reply', targetCommentId: strTargetId, rootCommentId: strRootId });
                redirectToLogin(d);
                return;
            }

            if (isMutating) return;
            isMutating = true;

            if (activeReplyDebounceTimer) {
                clearTimeout(activeReplyDebounceTimer);
                activeReplyDebounceTimer = null;
            }
            saveReplyDraft(strTargetId, textarea.value);
            saveActiveInlineMarker({ type: 'reply', targetCommentId: strTargetId, rootCommentId: strRootId });

            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            textarea.disabled = true;
            errorSpan.hidden = true;

            try {
                const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(strTargetId) + '/replies';
                const res = await doFetch(url, {
                    method: 'POST',
                    headers: buildHeaders(true),
                    body: JSON.stringify({ body: body })
                });

                if (res && res.status === 201) {
                    removeReplyDraft(strTargetId);
                    const marker = loadActiveInlineMarker();
                    if (marker && marker.type === 'reply' && String(marker.targetCommentId) === strTargetId) {
                        removeActiveInlineMarker();
                    }
                    if (String(activeReplyTargetCommentId) === strTargetId) {
                        activeReplyTargetCommentId = null;
                        activeReplyRootCommentId = null;
                        activeReplyTextareaEl = null;
                    }
                    clearElement(slot);
                    await refreshThread(strRootId, d);
                    await refreshMetrics(d);
                } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                    saveReplyDraft(strTargetId, textarea.value);
                    saveActiveInlineMarker({ type: 'reply', targetCommentId: strTargetId, rootCommentId: strRootId });
                    redirectToLogin(d);
                } else if (res && res.status === 400) {
                    errorSpan.textContent = 'Nội dung phản hồi không hợp lệ.';
                    errorSpan.hidden = false;
                } else if (res && res.status === 404) {
                    errorSpan.textContent = 'Bình luận hoặc bài viết không còn tồn tại.';
                    errorSpan.hidden = false;
                } else {
                    errorSpan.textContent = 'Không thể gửi phản hồi. Vui lòng thử lại sau.';
                    errorSpan.hidden = false;
                }
            } catch (err) {
                errorSpan.textContent = 'Lỗi kết nối khi gửi phản hồi.';
                errorSpan.hidden = false;
            } finally {
                isMutating = false;
                submitBtn.disabled = false;
                cancelBtn.disabled = false;
                textarea.disabled = false;
            }
        });

        composerBox.appendChild(form);
        slot.appendChild(composerBox);
        textarea.focus();
    }

    /**
     * Opens an inline edit form replacing comment body text.
     */
    function openEditComposer(commentId, rootCommentId, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        // Close any existing open inline composers first (auto-flushes dirty draft)
        closeAllInlineComposers(d);

        const strCommentId = String(commentId);
        const strRootId = String(rootCommentId);

        const bodyContainer = d.querySelector('[data-body-container="' + strCommentId + '"]');
        if (!bodyContainer) return;

        // Find current comment data
        const commentData = findCommentData(strCommentId);
        if (!commentData || commentData.tombstone === true || commentData.canEdit !== true) {
            return;
        }

        const initialBody = (commentData && commentData.body) ? commentData.body : '';

        // Check for existing draft in storage
        const existingDraft = loadEditDraft(strCommentId);
        let initialTextareaValue = initialBody;
        let userHasTyped = false;
        if (existingDraft && typeof existingDraft === 'string') {
            initialTextareaValue = existingDraft;
            userHasTyped = true;
        }

        // Save previous DOM state for cancel
        const savedChildNodes = Array.from(bodyContainer.childNodes);
        const savedTextContent = bodyContainer.textContent;

        clearElement(bodyContainer);

        const form = d.createElement('form');
        form.className = 'wiki-inline-edit-form';

        const label = d.createElement('label');
        label.className = 'visually-hidden';
        label.textContent = 'Chỉnh sửa bình luận';
        const textareaId = 'wikiEditInput_' + strCommentId;
        label.setAttribute('for', textareaId);
        form.appendChild(label);

        const textarea = d.createElement('textarea');
        textarea.id = textareaId;
        textarea.className = 'wiki-comment-textarea';
        textarea.rows = 3;
        textarea.value = initialTextareaValue;
        textarea.maxLength = 2000;
        form.appendChild(textarea);

        // Update active marker & module state
        saveActiveInlineMarker({ type: 'edit', commentId: strCommentId, rootCommentId: strRootId });
        activeEditCommentId = strCommentId;
        activeEditRootCommentId = strRootId;
        activeEditTextareaEl = textarea;
        activeEditHasUserTyped = userHasTyped;
        activeEditSavedChildNodes = savedChildNodes;
        activeEditSavedTextContent = savedTextContent;
        activeEditContainerEl = bodyContainer;

        // Attach input listener with ~400ms debounce
        textarea.addEventListener('input', function () {
            activeEditHasUserTyped = true;
            if (activeEditDebounceTimer) {
                clearTimeout(activeEditDebounceTimer);
            }
            activeEditDebounceTimer = setTimeout(function () {
                const saved = saveEditDraft(strCommentId, textarea.value);
                if (saved) {
                    saveActiveInlineMarker({ type: 'edit', commentId: strCommentId, rootCommentId: strRootId });
                }
                activeEditDebounceTimer = null;
            }, 400);
        });

        const footer = d.createElement('div');
        footer.className = 'wiki-comment-composer-footer';

        const errorSpan = d.createElement('span');
        errorSpan.className = 'wiki-comment-composer-error';
        errorSpan.setAttribute('role', 'alert');
        errorSpan.hidden = true;
        footer.appendChild(errorSpan);

        const actionsDiv = d.createElement('div');
        actionsDiv.className = 'wiki-comment-composer-actions';

        const cancelBtn = d.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'wiki-comment-btn wiki-comment-btn--secondary';
        cancelBtn.textContent = 'Hủy';
        cancelBtn.addEventListener('click', function () {
            if (activeEditDebounceTimer) {
                clearTimeout(activeEditDebounceTimer);
                activeEditDebounceTimer = null;
            }
            removeEditDraft(strCommentId);
            const marker = loadActiveInlineMarker();
            if (marker && marker.type === 'edit' && String(marker.commentId) === strCommentId) {
                removeActiveInlineMarker();
            }
            if (String(activeEditCommentId) === strCommentId) {
                activeEditCommentId = null;
                activeEditRootCommentId = null;
                activeEditTextareaEl = null;
                activeEditHasUserTyped = false;
                activeEditSavedChildNodes = null;
                activeEditSavedTextContent = '';
                activeEditContainerEl = null;
            }
            clearElement(bodyContainer);
            if (savedChildNodes.length > 0) {
                for (let i = 0; i < savedChildNodes.length; i++) {
                    bodyContainer.appendChild(savedChildNodes[i]);
                }
            } else {
                bodyContainer.textContent = savedTextContent;
            }
        });
        actionsDiv.appendChild(cancelBtn);

        const submitBtn = d.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = 'wiki-comment-btn wiki-comment-btn--primary';
        submitBtn.textContent = 'Lưu';
        actionsDiv.appendChild(submitBtn);

        footer.appendChild(actionsDiv);
        form.appendChild(footer);

        form.addEventListener('submit', async function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            const rawBody = textarea.value || '';
            const submittedBody = rawBody.trim();
            if (!submittedBody) {
                errorSpan.textContent = 'Vui lòng nhập nội dung bình luận.';
                errorSpan.hidden = false;
                textarea.focus();
                return;
            }

            if (!isAuthenticated) {
                if (activeEditDebounceTimer) {
                    clearTimeout(activeEditDebounceTimer);
                    activeEditDebounceTimer = null;
                }
                if (activeEditHasUserTyped || rawBody !== initialBody) {
                    activeEditHasUserTyped = true;
                    saveEditDraft(strCommentId, rawBody);
                }
                saveActiveInlineMarker({ type: 'edit', commentId: strCommentId, rootCommentId: strRootId });
                redirectToLogin(d);
                return;
            }

            if (isMutating) return;
            isMutating = true;

            if (activeEditDebounceTimer) {
                clearTimeout(activeEditDebounceTimer);
                activeEditDebounceTimer = null;
            }
            if (activeEditHasUserTyped || rawBody !== initialBody) {
                activeEditHasUserTyped = true;
                saveEditDraft(strCommentId, rawBody);
                saveActiveInlineMarker({ type: 'edit', commentId: strCommentId, rootCommentId: strRootId });
            }

            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            textarea.disabled = true;
            errorSpan.hidden = true;

            try {
                const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(strCommentId);
                const res = await doFetch(url, {
                    method: 'PATCH',
                    headers: buildHeaders(true),
                    body: JSON.stringify({ body: submittedBody })
                });

                if (res && res.status === 204) {
                    removeEditDraft(strCommentId);
                    const marker = loadActiveInlineMarker();
                    if (marker && marker.type === 'edit' && String(marker.commentId) === strCommentId) {
                        removeActiveInlineMarker();
                    }
                    if (String(activeEditCommentId) === strCommentId) {
                        activeEditCommentId = null;
                        activeEditRootCommentId = null;
                        activeEditTextareaEl = null;
                        activeEditHasUserTyped = false;
                        activeEditSavedChildNodes = null;
                        activeEditSavedTextContent = '';
                        activeEditContainerEl = null;
                    }
                    await refreshThread(strRootId, d);
                } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                    if (activeEditHasUserTyped || rawBody !== initialBody) {
                        saveEditDraft(strCommentId, rawBody);
                    }
                    saveActiveInlineMarker({ type: 'edit', commentId: strCommentId, rootCommentId: strRootId });
                    redirectToLogin(d);
                } else if (res && res.status === 403) {
                    errorSpan.textContent = 'Bạn không có quyền chỉnh sửa bình luận này.';
                    errorSpan.hidden = false;
                    await refreshThread(strRootId, d);
                } else if (res && res.status === 400) {
                    errorSpan.textContent = 'Nội dung bình luận không hợp lệ.';
                    errorSpan.hidden = false;
                } else if (res && res.status === 404) {
                    errorSpan.textContent = 'Bình luận không còn tồn tại.';
                    errorSpan.hidden = false;
                } else {
                    errorSpan.textContent = 'Không thể chỉnh sửa bình luận. Vui lòng thử lại sau.';
                    errorSpan.hidden = false;
                }
            } catch (err) {
                errorSpan.textContent = 'Lỗi kết nối khi lưu bình luận.';
                errorSpan.hidden = false;
            } finally {
                isMutating = false;
                submitBtn.disabled = false;
                cancelBtn.disabled = false;
                textarea.disabled = false;
            }
        });

        bodyContainer.appendChild(form);
        textarea.focus();
    }

    /**
     * Handles deletion of a comment (root or reply).
     */
    async function handleDeleteComment(commentId, rootCommentId, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        const confirmed = doConfirm('Bạn có chắc chắn muốn xóa bình luận này không?');
        if (!confirmed) return;

        if (isMutating) return;
        isMutating = true;

        const isRootDelete = String(commentId) === String(rootCommentId);

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(commentId);
            const res = await doFetch(url, {
                method: 'DELETE',
                headers: buildHeaders(false)
            });

            if (res && res.status === 204) {
                clearDiscussionStatus(d);
                if (isRootDelete) {
                    // Authoritatively reload feed from page 0
                    await loadDiscussionFeed(0, false, d);
                } else {
                    // Refresh affected thread authoritatively
                    await refreshThread(rootCommentId, d);
                    await refreshMetrics(d);
                }
            } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                redirectToLogin(d);
            } else if (res && res.status === 403) {
                try {
                    if (isRootDelete) {
                        await loadDiscussionFeed(0, false, d);
                    } else {
                        await refreshThread(rootCommentId, d);
                    }
                } catch (ignored) {
                    // Ignore refresh error, authoritative 403 error message takes priority
                }
                showDiscussionError('Bạn không còn quyền xóa bình luận này.', d);
            } else if (res && res.status === 404) {
                await loadDiscussionFeed(0, false, d);
            } else {
                showDiscussionError('Không thể xóa bình luận. Vui lòng thử lại sau.', d);
            }
        } catch (err) {
            showDiscussionError('Lỗi kết nối khi xóa bình luận. Vui lòng thử lại.', d);
        } finally {
            isMutating = false;
        }
    }

    /**
     * Closes any open inline composers (reply or edit) in the document.
     * Auto-flushes dirty drafts to storage before clearing/restoring DOM.
     */
    function closeAllInlineComposers(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        // Auto-flush and clean up active reply composer
        if (activeReplyTargetCommentId && activeReplyTextareaEl) {
            if (activeReplyDebounceTimer) {
                clearTimeout(activeReplyDebounceTimer);
                activeReplyDebounceTimer = null;
            }
            flushActiveReplyDraft(d);
        }
        activeReplyTargetCommentId = null;
        activeReplyRootCommentId = null;
        activeReplyTextareaEl = null;

        // Auto-flush and clean up active edit composer
        if (activeEditCommentId && activeEditTextareaEl) {
            if (activeEditDebounceTimer) {
                clearTimeout(activeEditDebounceTimer);
                activeEditDebounceTimer = null;
            }
            flushActiveEditDraft(d);
            if (activeEditContainerEl) {
                clearElement(activeEditContainerEl);
                if (activeEditSavedChildNodes && activeEditSavedChildNodes.length > 0) {
                    for (let i = 0; i < activeEditSavedChildNodes.length; i++) {
                        activeEditContainerEl.appendChild(activeEditSavedChildNodes[i]);
                    }
                } else if (activeEditSavedTextContent) {
                    activeEditContainerEl.textContent = activeEditSavedTextContent;
                }
            }
        }
        activeEditCommentId = null;
        activeEditRootCommentId = null;
        activeEditTextareaEl = null;
        activeEditHasUserTyped = false;
        activeEditSavedChildNodes = null;
        activeEditSavedTextContent = '';
        activeEditContainerEl = null;

        if (d) {
            const slots = d.querySelectorAll('.wiki-reply-composer-slot');
            for (let i = 0; i < slots.length; i++) {
                clearElement(slots[i]);
            }
        }
    }

    /**
     * Opens the shared CommentReportModal for a specific interaction comment on the current Wiki article.
     *
     * @param {string} commentId - Target comment ID
     * @param {Element} [triggerEl] - Element that triggered open (for focus return)
     * @param {Document} [doc] - Document context
     * @returns {boolean} true if modal opened, false otherwise
     */
    function openReportModal(commentId, triggerEl, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return false;
        }

        if (!articleId || !commentId) {
            return false;
        }

        const modal = getReportModal();
        if (!modal || typeof modal.open !== 'function') {
            return false;
        }

        const cleanCommentId = String(commentId).trim();
        const submitUrl = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(cleanCommentId) + '/reports';

        return modal.open({
            commentId: cleanCommentId,
            submitUrl: submitUrl,
            contextLabel: 'wiki',
            triggerEl: triggerEl || null,
            onSuccess: function (result) {
                showDiscussionSuccess('Báo cáo của bạn đã được gửi thành công.', d);
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
     * Root comment composer form submit handler.
     */
    async function handleRootCommentSubmit(e, doc) {
        if (e && typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);

        if (!isAuthenticated) {
            if (rootDraftDebounceTimer) {
                clearTimeout(rootDraftDebounceTimer);
                rootDraftDebounceTimer = null;
            }
            saveRootDraft(d);
            redirectToLogin(d);
            return;
        }

        const text = els.composerInputEl ? els.composerInputEl.value.trim() : '';
        if (!text) {
            if (els.composerErrorEl) {
                els.composerErrorEl.textContent = 'Vui lòng nhập nội dung bình luận.';
                els.composerErrorEl.hidden = false;
            }
            if (els.composerInputEl) {
                els.composerInputEl.focus();
            }
            return;
        }

        if (isMutating) return;
        isMutating = true;

        if (rootDraftDebounceTimer) {
            clearTimeout(rootDraftDebounceTimer);
            rootDraftDebounceTimer = null;
        }
        saveRootDraft(d);

        if (els.composerSubmitEl) els.composerSubmitEl.disabled = true;
        if (els.composerInputEl) els.composerInputEl.disabled = true;
        if (els.composerErrorEl) els.composerErrorEl.hidden = true;

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments';
            const res = await doFetch(url, {
                method: 'POST',
                headers: buildHeaders(true),
                body: JSON.stringify({ body: text })
            });

            if (res && res.status === 201) {
                removeRootDraft();
                if (els.composerInputEl) {
                    els.composerInputEl.value = '';
                }
                // Refresh discussion authoritatively from page 0
                await loadDiscussionFeed(0, false, d);
            } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                saveRootDraft(d);
                redirectToLogin(d);
            } else if (res && res.status === 400) {
                if (els.composerErrorEl) {
                    els.composerErrorEl.textContent = 'Nội dung bình luận không hợp lệ.';
                    els.composerErrorEl.hidden = false;
                }
            } else if (res && res.status === 404) {
                if (els.composerErrorEl) {
                    els.composerErrorEl.textContent = 'Bài viết không còn tồn tại hoặc chưa xuất bản.';
                    els.composerErrorEl.hidden = false;
                }
            } else {
                if (els.composerErrorEl) {
                    els.composerErrorEl.textContent = 'Không thể gửi bình luận. Vui lòng thử lại sau.';
                    els.composerErrorEl.hidden = false;
                }
            }
        } catch (err) {
            if (els.composerErrorEl) {
                els.composerErrorEl.textContent = 'Lỗi kết nối khi gửi bình luận.';
                els.composerErrorEl.hidden = false;
            }
        } finally {
            isMutating = false;
            if (els.composerSubmitEl) els.composerSubmitEl.disabled = false;
            if (els.composerInputEl) els.composerInputEl.disabled = false;
        }
    }

    /**
     * Ensures the shared revision history modal exists in the document, creating it if absent.
     */
    function ensureHistoryModal(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        let modal = d.getElementById ? d.getElementById(HISTORY_MODAL_ID) : null;
        if (!modal && typeof d.querySelector === 'function') {
            modal = d.querySelector('#' + HISTORY_MODAL_ID);
        }
        if (modal) {
            return modal;
        }

        modal = d.createElement('div');
        modal.id = HISTORY_MODAL_ID;
        modal.setAttribute('id', HISTORY_MODAL_ID);
        modal.className = 'wiki-comment-history-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-labelledby', HISTORY_TITLE_ID);
        modal.hidden = true;
        modal.setAttribute('hidden', '');

        const backdrop = d.createElement('div');
        backdrop.className = 'wiki-comment-history-backdrop';
        backdrop.setAttribute('data-action', 'close-history-modal');

        const card = d.createElement('div');
        card.className = 'wiki-comment-history-card';
        card.setAttribute('role', 'document');

        const header = d.createElement('header');
        header.className = 'wiki-comment-history-header';

        const title = d.createElement('h3');
        title.id = HISTORY_TITLE_ID;
        title.setAttribute('id', HISTORY_TITLE_ID);
        title.className = 'wiki-comment-history-title';
        title.textContent = 'Lịch sử chỉnh sửa';

        const closeBtn = d.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'wiki-comment-history-close-btn';
        closeBtn.setAttribute('data-action', 'close-history-modal');
        closeBtn.setAttribute('aria-label', 'Đóng');
        closeBtn.textContent = '✕';

        header.appendChild(title);
        header.appendChild(closeBtn);

        const body = d.createElement('div');
        body.className = 'wiki-comment-history-body';
        body.id = 'wikiCommentHistoryBody';
        body.setAttribute('id', 'wikiCommentHistoryBody');

        const statusEl = d.createElement('div');
        statusEl.id = HISTORY_STATUS_ID;
        statusEl.setAttribute('id', HISTORY_STATUS_ID);
        statusEl.className = 'wiki-comment-history-status';
        statusEl.setAttribute('role', 'status');
        statusEl.setAttribute('aria-live', 'polite');

        const listEl = d.createElement('div');
        listEl.id = HISTORY_LIST_ID;
        listEl.setAttribute('id', HISTORY_LIST_ID);
        listEl.className = 'wiki-comment-history-list';
        listEl.setAttribute('role', 'feed');
        listEl.setAttribute('aria-label', 'Danh sách các phiên bản cũ');

        const moreContainer = d.createElement('div');
        moreContainer.id = HISTORY_MORE_CONTAINER_ID;
        moreContainer.setAttribute('id', HISTORY_MORE_CONTAINER_ID);
        moreContainer.className = 'wiki-comment-history-more';
        moreContainer.hidden = true;
        moreContainer.setAttribute('hidden', '');

        const moreBtn = d.createElement('button');
        moreBtn.type = 'button';
        moreBtn.id = HISTORY_MORE_BTN_ID;
        moreBtn.setAttribute('id', HISTORY_MORE_BTN_ID);
        moreBtn.className = 'wiki-comment-history-more-btn';
        moreBtn.textContent = 'Xem thêm';

        moreContainer.appendChild(moreBtn);

        body.appendChild(statusEl);
        body.appendChild(listEl);
        body.appendChild(moreContainer);

        card.appendChild(header);
        card.appendChild(body);

        modal.appendChild(backdrop);
        modal.appendChild(card);

        // Direct listeners for buttons (single execution path)
        closeBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            closeRevisionHistory(d);
        });

        backdrop.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            closeRevisionHistory(d);
        });

        moreBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            loadMoreRevisions(d);
        });

        if (d.body && typeof d.body.appendChild === 'function') {
            d.body.appendChild(modal);
        } else if (typeof d.appendChild === 'function') {
            d.appendChild(modal);
        }

        if (typeof d.registerElement === 'function' && d.elementsById instanceof Map) {
            d.registerElement(HISTORY_MODAL_ID, modal);
            d.registerElement(HISTORY_TITLE_ID, title);
            d.registerElement(HISTORY_STATUS_ID, statusEl);
            d.registerElement(HISTORY_LIST_ID, listEl);
            d.registerElement(HISTORY_MORE_CONTAINER_ID, moreContainer);
            d.registerElement(HISTORY_MORE_BTN_ID, moreBtn);
        }

        return modal;
    }

    /**
     * Resolves existing history modal elements WITHOUT creating the modal if absent.
     */
    function getExistingHistoryElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return { modal: null, statusEl: null, listEl: null, moreContainer: null, moreBtn: null, closeBtn: null };
        }
        let modal = d.getElementById ? d.getElementById(HISTORY_MODAL_ID) : null;
        if (!modal && typeof d.querySelector === 'function') {
            modal = d.querySelector('#' + HISTORY_MODAL_ID);
        }
        if (!modal) {
            return { modal: null, statusEl: null, listEl: null, moreContainer: null, moreBtn: null, closeBtn: null };
        }
        const statusEl = modal.querySelector('#' + HISTORY_STATUS_ID) || (d.getElementById ? d.getElementById(HISTORY_STATUS_ID) : null);
        const listEl = modal.querySelector('#' + HISTORY_LIST_ID) || (d.getElementById ? d.getElementById(HISTORY_LIST_ID) : null);
        const moreContainer = modal.querySelector('#' + HISTORY_MORE_CONTAINER_ID) || (d.getElementById ? d.getElementById(HISTORY_MORE_CONTAINER_ID) : null);
        const moreBtn = modal.querySelector('#' + HISTORY_MORE_BTN_ID) || (d.getElementById ? d.getElementById(HISTORY_MORE_BTN_ID) : null);
        const closeBtn = modal.querySelector('.wiki-comment-history-close-btn') ||
            modal.querySelector('button[data-action="close-history-modal"]') ||
            modal.querySelector('[data-action="close-history-modal"]');
        return { modal, statusEl, listEl, moreContainer, moreBtn, closeBtn };
    }

    /**
     * Resolves key elements of the history modal, ensuring it exists in the document.
     */
    function getHistoryElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return { modal: null, statusEl: null, listEl: null, moreContainer: null, moreBtn: null, closeBtn: null };
        }
        ensureHistoryModal(d);
        return getExistingHistoryElements(d);
    }

    /**
     * Displays an error notice inside the history modal.
     */
    function showHistoryStatusError(message, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;
        const { statusEl, moreContainer } = getHistoryElements(d);
        if (moreContainer) {
            moreContainer.hidden = true;
            moreContainer.setAttribute('hidden', '');
        }
        if (statusEl) {
            clearElement(statusEl);
            statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--error';
            statusEl.textContent = message;
        }
    }

    /**
     * Renders a single revision item DOM element.
     */
    function renderRevisionItem(rev, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const itemEl = d.createElement('article');
        itemEl.className = 'wiki-comment-history-entry';

        const headerEl = d.createElement('header');
        headerEl.className = 'wiki-comment-history-entry-header';

        const badgeEl = d.createElement('span');
        badgeEl.className = 'wiki-comment-history-badge';
        const revNum = rev && rev.revisionNumber != null ? rev.revisionNumber : '';
        badgeEl.textContent = 'Phiên bản #' + revNum;
        headerEl.appendChild(badgeEl);

        if (rev && rev.createdAt) {
            const timeEl = d.createElement('time');
            timeEl.className = 'wiki-comment-history-time';
            timeEl.setAttribute('datetime', String(rev.createdAt));
            timeEl.setAttribute('data-relative-time', '');
            const rt = resolveRelativeTime();
            const formatted = rt && typeof rt.formatElement === 'function'
                ? rt.formatElement(timeEl)
                : false;
            if (!formatted) {
                const fallback = formatTimestamp(rev.createdAt);
                if (fallback) {
                    timeEl.textContent = fallback;
                }
            }
            if (timeEl.textContent) {
                headerEl.appendChild(timeEl);
            }
        }

        const bodyEl = d.createElement('div');
        bodyEl.className = 'wiki-comment-history-entry-body';
        bodyEl.textContent = (rev && rev.body) ? String(rev.body) : '';

        itemEl.appendChild(headerEl);
        itemEl.appendChild(bodyEl);
        return itemEl;
    }

    /**
     * Opens the revision history modal for a comment and fetches page 0.
     */
    async function openRevisionHistory(commentId, triggerEl, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!articleId || !commentId || !d) return;

        const strCommentId = String(commentId).trim();
        if (!strCommentId) return;

        const { modal, statusEl, listEl, moreContainer, moreBtn, closeBtn } = getHistoryElements(d);
        if (!modal) return;

        const token = ++historyRequestToken;
        historyActiveCommentId = strCommentId;
        historyCurrentPage = 0;
        historyHasNext = false;
        historyIsLoading = true;
        historyIsLoadingMore = false;
        historyRenderedRevisionNumbers.clear();

        if (triggerEl && typeof triggerEl.focus === 'function') {
            historyPreviousFocusedElement = triggerEl;
        } else if (d.activeElement && d.activeElement !== modal) {
            historyPreviousFocusedElement = d.activeElement;
        }

        modal.hidden = false;
        modal.removeAttribute('hidden');

        if (closeBtn && typeof closeBtn.focus === 'function') {
            try {
                closeBtn.focus();
            } catch (_) {}
        }

        if (listEl) {
            clearElement(listEl);
        }
        if (moreContainer) {
            moreContainer.hidden = true;
            moreContainer.setAttribute('hidden', '');
        }
        if (statusEl) {
            clearElement(statusEl);
            statusEl.className = 'wiki-comment-history-status';
            statusEl.textContent = 'Đang tải lịch sử chỉnh sửa...';
        }

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) +
                '/comments/' + encodeURIComponent(strCommentId) +
                '/revisions?page=0&size=' + DEFAULT_PAGE_SIZE;
            const res = await doFetch(url, {
                method: 'GET',
                headers: { 'Accept': 'application/json' }
            });

            if (token !== historyRequestToken || historyActiveCommentId !== strCommentId) {
                return;
            }

            historyIsLoading = false;

            if (!res) {
                showHistoryStatusError('Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.', d);
                return;
            }

            if (res.status === 200) {
                const data = await res.json();
                if (token !== historyRequestToken || historyActiveCommentId !== strCommentId) {
                    return;
                }

                const items = Array.isArray(data.items) ? data.items : [];
                historyHasNext = Boolean(data.hasNext);
                historyCurrentPage = data.page != null ? Number(data.page) : 0;

                if (items.length === 0) {
                    if (statusEl) {
                        clearElement(statusEl);
                        statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--empty';
                        statusEl.textContent = 'Chưa có phiên bản chỉnh sửa trước đó.';
                    }
                    return;
                }

                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status';
                }

                if (listEl) {
                    clearElement(listEl);
                    for (let i = 0; i < items.length; i++) {
                        const rev = items[i];
                        if (!rev) continue;
                        const revNum = rev.revisionNumber;
                        if (revNum != null && historyRenderedRevisionNumbers.has(revNum)) {
                            continue;
                        }
                        if (revNum != null) {
                            historyRenderedRevisionNumbers.add(revNum);
                        }
                        listEl.appendChild(renderRevisionItem(rev, d));
                    }
                }

                if (moreContainer && moreBtn) {
                    if (historyHasNext) {
                        moreContainer.hidden = false;
                        moreContainer.removeAttribute('hidden');
                        moreBtn.disabled = false;
                        moreBtn.textContent = 'Xem thêm';
                    } else {
                        moreContainer.hidden = true;
                        moreContainer.setAttribute('hidden', '');
                    }
                }
            } else if (res.status === 404) {
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--unavailable';
                    statusEl.textContent = 'Lịch sử chỉnh sửa không còn khả dụng.';
                }
            } else if (res.status === 400) {
                showHistoryStatusError('Yêu cầu không hợp lệ.', d);
            } else {
                showHistoryStatusError('Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.', d);
            }
        } catch (err) {
            if (token !== historyRequestToken || historyActiveCommentId !== strCommentId) {
                return;
            }
            historyIsLoading = false;
            showHistoryStatusError('Lỗi kết nối khi tải lịch sử chỉnh sửa.', d);
        }
    }

    /**
     * Loads the next page of revisions and appends them to the history list.
     */
    async function loadMoreRevisions(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d || !articleId || !historyActiveCommentId || historyIsLoading || historyIsLoadingMore || !historyHasNext) {
            return;
        }

        const { statusEl, listEl, moreContainer, moreBtn } = getExistingHistoryElements(d);
        if (!moreBtn) return;

        historyIsLoadingMore = true;
        const token = historyRequestToken;
        const targetCommentId = historyActiveCommentId;
        const nextPage = historyCurrentPage + 1;

        moreBtn.disabled = true;
        moreBtn.textContent = 'Đang tải...';

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) +
                '/comments/' + encodeURIComponent(targetCommentId) +
                '/revisions?page=' + nextPage + '&size=' + DEFAULT_PAGE_SIZE;
            const res = await doFetch(url, {
                method: 'GET',
                headers: { 'Accept': 'application/json' }
            });

            if (token !== historyRequestToken || historyActiveCommentId !== targetCommentId) {
                return;
            }

            historyIsLoadingMore = false;

            if (!res) {
                if (moreBtn) {
                    moreBtn.disabled = false;
                    moreBtn.textContent = 'Thử lại';
                }
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--error';
                    statusEl.textContent = 'Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.';
                }
                return;
            }

            if (res.status === 200) {
                const data = await res.json();
                if (token !== historyRequestToken || historyActiveCommentId !== targetCommentId) {
                    return;
                }

                // Clear any previous transient error status on successful retry
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status';
                }

                historyCurrentPage = nextPage;
                const newItems = Array.isArray(data.items) ? data.items : [];
                historyHasNext = Boolean(data.hasNext);

                if (listEl && newItems.length > 0) {
                    for (let i = 0; i < newItems.length; i++) {
                        const rev = newItems[i];
                        if (!rev) continue;
                        const revNum = rev.revisionNumber;
                        if (revNum != null && historyRenderedRevisionNumbers.has(revNum)) {
                            continue;
                        }
                        if (revNum != null) {
                            historyRenderedRevisionNumbers.add(revNum);
                        }
                        listEl.appendChild(renderRevisionItem(rev, d));
                    }
                }

                if (moreContainer && moreBtn) {
                    if (historyHasNext) {
                        moreContainer.hidden = false;
                        moreContainer.removeAttribute('hidden');
                        moreBtn.disabled = false;
                        moreBtn.textContent = 'Xem thêm';
                    } else {
                        moreContainer.hidden = true;
                        moreContainer.setAttribute('hidden', '');
                    }
                }
            } else if (res.status === 404) {
                historyHasNext = false;
                if (moreContainer) {
                    moreContainer.hidden = true;
                    moreContainer.setAttribute('hidden', '');
                }
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--unavailable';
                    statusEl.textContent = 'Lịch sử chỉnh sửa không còn khả dụng.';
                }
            } else if (res.status === 400) {
                if (moreBtn) {
                    moreBtn.disabled = false;
                    moreBtn.textContent = 'Thử lại';
                }
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--error';
                    statusEl.textContent = 'Yêu cầu không hợp lệ.';
                }
            } else {
                if (moreBtn) {
                    moreBtn.disabled = false;
                    moreBtn.textContent = 'Thử lại';
                }
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--error';
                    statusEl.textContent = 'Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.';
                }
            }
        } catch (err) {
            if (token !== historyRequestToken || historyActiveCommentId !== targetCommentId) {
                return;
            }
            historyIsLoadingMore = false;
            if (moreBtn) {
                moreBtn.disabled = false;
                moreBtn.textContent = 'Thử lại';
            }
            if (statusEl) {
                clearElement(statusEl);
                statusEl.className = 'wiki-comment-history-status wiki-comment-history-status--error';
                statusEl.textContent = 'Lỗi kết nối khi tải lịch sử chỉnh sửa.';
            }
        }
    }

    /**
     * Closes the revision history modal and restores focus to trigger.
     */
    function closeRevisionHistory(doc) {
        historyRequestToken++; // Invalidate pending requests
        historyActiveCommentId = null;
        historyIsLoading = false;
        historyIsLoadingMore = false;
        historyRenderedRevisionNumbers.clear();

        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (d) {
            const { modal } = getExistingHistoryElements(d);
            if (modal) {
                modal.hidden = true;
                modal.setAttribute('hidden', '');
            }
        }

        if (historyPreviousFocusedElement && typeof historyPreviousFocusedElement.focus === 'function') {
            try {
                historyPreviousFocusedElement.focus();
            } catch (_) {}
        }
        historyPreviousFocusedElement = null;
    }

    /**
     * Load more button click handler.
     */
    async function handleLoadMore(doc) {
        if (!hasNext || isLoading) return;
        await loadDiscussionFeed(currentPage + 1, true, doc);
    }

    /**
     * Initializes the Wiki Article Comments frontend module.
     */
    function initWikiArticleComments(doc, options) {
        currentDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (!currentDoc) return false;

        const els = getElements(currentDoc);
        if (!els.sectionEl) {
            return false;
        }

        articleId = els.sectionEl.getAttribute('data-article-id');
        if (!articleId) {
            return false; // No article ID -> do not initialize or call APIs
        }

        const opts = (options && typeof options === 'object') ? options : {};
        if (typeof opts.fetch === 'function') {
            injectedFetch = opts.fetch;
        }
        if (opts.reportModal) {
            injectedReportModal = opts.reportModal;
        }
        if (opts.commentPresentation !== undefined) {
            injectedCommentPresentation = opts.commentPresentation;
        }

        isAuthenticated = els.sectionEl.getAttribute('data-authenticated') === 'true';
        if (typeof opts.authenticated === 'boolean') {
            isAuthenticated = opts.authenticated;
        }
        loginUrl = els.sectionEl.getAttribute('data-login-url') || '/login';
        if (opts.loginUrl) {
            loginUrl = String(opts.loginUrl);
        }

        // Read CSRF from data attributes or fallback to meta tags
        const attrCsrfToken = els.sectionEl.getAttribute('data-csrf-token');
        const attrCsrfHeader = els.sectionEl.getAttribute('data-csrf-header');

        const metaCsrf = currentDoc.querySelector('meta[name="_csrf"]');
        const metaCsrfHeader = currentDoc.querySelector('meta[name="_csrf_header"]');

        csrfToken = attrCsrfToken || (metaCsrf ? metaCsrf.getAttribute('content') : '');
        csrfHeader = attrCsrfHeader || (metaCsrfHeader ? metaCsrfHeader.getAttribute('content') : 'X-CSRF-TOKEN');

        // Restore root comment draft if available
        restoreRootDraft(currentDoc);

        // Wire root composer draft auto-saving
        if (els.composerInputEl) {
            if (rootDraftInputListener && typeof els.composerInputEl.removeEventListener === 'function') {
                els.composerInputEl.removeEventListener('input', rootDraftInputListener);
            }
            rootDraftInputListener = function () {
                if (rootDraftDebounceTimer) {
                    clearTimeout(rootDraftDebounceTimer);
                }
                rootDraftDebounceTimer = setTimeout(function () {
                    saveRootDraft(currentDoc);
                    rootDraftDebounceTimer = null;
                }, 400);
            };
            els.composerInputEl.addEventListener('input', rootDraftInputListener);
        }

        // Wire immediate page-exit draft flush on pagehide
        const win = (currentDoc && currentDoc.defaultView) ? currentDoc.defaultView : (typeof window !== 'undefined' ? window : null);
        if (win && typeof win.addEventListener === 'function') {
            if (rootDraftPageExitListener && typeof win.removeEventListener === 'function') {
                win.removeEventListener('pagehide', rootDraftPageExitListener);
            }
            rootDraftPageExitListener = function () {
                if (rootDraftDebounceTimer) {
                    clearTimeout(rootDraftDebounceTimer);
                    rootDraftDebounceTimer = null;
                }
                if (activeReplyDebounceTimer) {
                    clearTimeout(activeReplyDebounceTimer);
                    activeReplyDebounceTimer = null;
                }
                if (activeEditDebounceTimer) {
                    clearTimeout(activeEditDebounceTimer);
                    activeEditDebounceTimer = null;
                }
                saveRootDraft(currentDoc);
                flushActiveReplyDraft(currentDoc);
                flushActiveEditDraft(currentDoc);
                if (activeReplyTargetCommentId) {
                    saveActiveInlineMarker({
                        type: 'reply',
                        targetCommentId: activeReplyTargetCommentId,
                        rootCommentId: activeReplyRootCommentId || activeReplyTargetCommentId
                    });
                } else if (activeEditCommentId) {
                    saveActiveInlineMarker({
                        type: 'edit',
                        commentId: activeEditCommentId,
                        rootCommentId: activeEditRootCommentId || activeEditCommentId
                    });
                }
            };
            win.addEventListener('pagehide', rootDraftPageExitListener);
        }

        // Wire root composer
        if (els.composerFormEl) {
            els.composerFormEl.addEventListener('submit', function (e) {
                handleRootCommentSubmit(e, currentDoc);
            });
        }

        // Wire load more button
        if (els.loadMoreBtnEl) {
            els.loadMoreBtnEl.addEventListener('click', function () {
                handleLoadMore(currentDoc);
            });
        }

        // Delegate comment action buttons (reply, edit, delete, history, report) on thread list
        if (els.threadListEl) {
            els.threadListEl.addEventListener('click', function (e) {
                const target = e.target;
                if (!target || typeof target.getAttribute !== 'function') return;

                let actionBtn = null;
                if (typeof target.closest === 'function') {
                    actionBtn = target.closest('.kl-comment__menu-item') ||
                                target.closest('.kl-comment__primary-action') ||
                                target.closest('.wiki-comment-action-btn') ||
                                target.closest('.wiki-comment-menu-item') ||
                                target.closest('[data-action]') ||
                                target.closest('.wiki-comment-edited');
                } else if (target.getAttribute && (target.getAttribute('data-action') || (target.classList && target.classList.contains('wiki-comment-edited')))) {
                    actionBtn = target;
                }
                if (!actionBtn) return;

                const action = actionBtn.getAttribute('data-action') || actionBtn.getAttribute('data-action-key');
                const commentId = actionBtn.getAttribute('data-comment-id');
                const rootCommentId = actionBtn.getAttribute('data-root-id');
                const authorName = actionBtn.getAttribute('data-author-name') || '';

                if ((action === 'history' || action === 'view-revisions') && commentId) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeActiveMenu(false);
                    openRevisionHistory(commentId, actionBtn, currentDoc);
                } else if (action === 'reply' && commentId && rootCommentId) {
                    closeActiveMenu(false);
                    openReplyComposer(commentId, rootCommentId, authorName, currentDoc);
                } else if (action === 'edit' && commentId && rootCommentId) {
                    closeActiveMenu(false);
                    openEditComposer(commentId, rootCommentId, currentDoc);
                } else if (action === 'delete' && commentId && rootCommentId) {
                    closeActiveMenu(false);
                    handleDeleteComment(commentId, rootCommentId, currentDoc);
                } else if (action === 'report' && commentId) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeActiveMenu(false);
                    if (!isAuthenticated) {
                        redirectToLogin(currentDoc);
                        return;
                    }
                    openReportModal(commentId, actionBtn, currentDoc);
                }
            });
        }

        // Escape key listener for actions menu popover first, then history modal
        if (currentDoc && typeof currentDoc.addEventListener === 'function') {
            keydownHandler = function (e) {
                if (e && (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27)) {
                    if (e.defaultPrevented) {
                        return;
                    }
                    const presentation = resolveCommentPresentation();
                    if (presentation && typeof presentation.getActiveOpenMenu === 'function' && presentation.getActiveOpenMenu()) {
                        if (typeof e.preventDefault === 'function') e.preventDefault();
                        closeActiveMenu(true);
                        return;
                    }
                    const { modal } = getExistingHistoryElements(currentDoc);
                    if (modal && !modal.hidden) {
                        if (typeof e.preventDefault === 'function') e.preventDefault();
                        closeRevisionHistory(currentDoc);
                    }
                }
            };
            currentDoc.addEventListener('keydown', keydownHandler);
        }

        // Extract deep-link parameters if present (MS-05E5C2B)
        const deepLink = extractDeepLinkParams(currentDoc, opts);
        if (deepLink) {
            pendingDeepLink = deepLink;
        }

        // Initial feed load
        loadDiscussionFeed(0, false, currentDoc);

        return true;
    }

    // Auto-initialize in browser when DOM is ready
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initWikiArticleComments(document);
            });
        } else {
            initWikiArticleComments(document);
        }
    }

    // Public API
    return {
        SECTION_ID: SECTION_ID,
        COUNT_BADGE_ID: COUNT_BADGE_ID,
        THREAD_LIST_ID: THREAD_LIST_ID,
        STATUS_ID: STATUS_ID,
        FOOTER_ID: FOOTER_ID,
        LOAD_MORE_BTN_ID: LOAD_MORE_BTN_ID,
        HISTORY_MODAL_ID: HISTORY_MODAL_ID,
        HISTORY_TITLE_ID: HISTORY_TITLE_ID,
        HISTORY_STATUS_ID: HISTORY_STATUS_ID,
        HISTORY_LIST_ID: HISTORY_LIST_ID,
        HISTORY_MORE_CONTAINER_ID: HISTORY_MORE_CONTAINER_ID,
        HISTORY_MORE_BTN_ID: HISTORY_MORE_BTN_ID,
        init: initWikiArticleComments,
        destroy: resetState,
        resetState: resetState,
        loadDiscussionFeed: loadDiscussionFeed,
        refreshFeed: function (doc) { return loadDiscussionFeed(0, false, doc, true); },
        refreshThread: refreshThread,
        refreshMetrics: refreshMetrics,
        showDiscussionError: showDiscussionError,
        clearDiscussionStatus: clearDiscussionStatus,
        renderThread: renderThread,
        renderComment: renderComment,
        formatTimestamp: formatTimestamp,
        formatCommentCount: formatCommentCount,
        isCommentEdited: isCommentEdited,
        getValidSecurityRedirectUrl: getValidSecurityRedirectUrl,
        openReplyComposer: openReplyComposer,
        openEditComposer: openEditComposer,
        handleDeleteComment: handleDeleteComment,
        handleRootCommentSubmit: handleRootCommentSubmit,
        openRevisionHistory: openRevisionHistory,
        loadMoreRevisions: loadMoreRevisions,
        closeRevisionHistory: closeRevisionHistory,
        ensureHistoryModal: ensureHistoryModal,
        getHistoryElements: getHistoryElements,
        getExistingHistoryElements: getExistingHistoryElements,
        closeAllInlineComposers: closeAllInlineComposers,
        findCommentData: findCommentData,
        findCommentContext: findCommentContext,
        getState: function () {
            return {
                articleId: articleId,
                isAuthenticated: isAuthenticated,
                loginUrl: loginUrl,
                currentPage: currentPage,
                hasNext: hasNext,
                isLoading: isLoading,
                isMutating: isMutating,
                threadCount: threadCount,
                commentCount: commentCount,
                currentThreads: currentThreads,
                renderedRootIds: Array.from(renderedRootIds),
                historyActiveCommentId: historyActiveCommentId,
                historyCurrentPage: historyCurrentPage,
                historyHasNext: historyHasNext,
                historyIsLoading: historyIsLoading,
                historyIsLoadingMore: historyIsLoadingMore,
                activeReplyTargetCommentId: activeReplyTargetCommentId,
                activeReplyRootCommentId: activeReplyRootCommentId,
                activeEditCommentId: activeEditCommentId,
                activeEditRootCommentId: activeEditRootCommentId,
                activeEditHasUserTyped: activeEditHasUserTyped,
                pendingDeepLink: pendingDeepLink,
                highlightedElement: highlightedElement
            };
        },
        setFetchImplementation: function (fn) {
            injectedFetch = fn;
        },
        setConfirmImplementation: function (fn) {
            injectedConfirm = fn;
        },
        setDraftStore: function (store) {
            injectedDraftStore = store;
        },
        getDraftStore: getDraftStore,
        saveRootDraft: saveRootDraft,
        restoreRootDraft: restoreRootDraft,
        removeRootDraft: removeRootDraft,
        getRootDraftKey: getRootDraftKey,
        buildWikiRootDraftKey: getRootDraftKey,
        getReplyDraftKey: getReplyDraftKey,
        getEditDraftKey: getEditDraftKey,
        getActiveInlineMarkerKey: getActiveInlineMarkerKey,
        saveReplyDraft: saveReplyDraft,
        loadReplyDraft: loadReplyDraft,
        removeReplyDraft: removeReplyDraft,
        saveEditDraft: saveEditDraft,
        loadEditDraft: loadEditDraft,
        removeEditDraft: removeEditDraft,
        saveActiveInlineMarker: saveActiveInlineMarker,
        loadActiveInlineMarker: loadActiveInlineMarker,
        removeActiveInlineMarker: removeActiveInlineMarker,
        flushActiveReplyDraft: flushActiveReplyDraft,
        flushActiveEditDraft: flushActiveEditDraft,
        restoreActiveInlineComposer: restoreActiveInlineComposer,
        openReportModal: openReportModal,
        setReportModalImplementation: function (modal) {
            injectedReportModal = modal;
        },
        getReportModal: getReportModal,
        showDiscussionSuccess: showDiscussionSuccess,
        closeActiveMenu: closeActiveMenu,
        getActiveOpenMenu: getActiveOpenMenu,
        createActionsMenu: createActionsMenu,
        resolveCommentPresentation: resolveCommentPresentation,
        setCommentPresentation: setCommentPresentation,
        resolveRelativeTime: resolveRelativeTime,
        setRelativeTime: setRelativeTime,
        formatTimestamp: formatTimestamp,
        buildOverflowActionDescriptors: buildOverflowActionDescriptors,
        extractDeepLinkParams: extractDeepLinkParams,
        scrubDeepLinkParams: scrubDeepLinkParams,
        resolveDeepLink: resolveDeepLink,
        clearHighlight: clearHighlight,
        applyHighlight: applyHighlight,
        fallbackToDiscussion: fallbackToDiscussion,
        getHighlightedElement: function () { return highlightedElement; }
    };
}));
