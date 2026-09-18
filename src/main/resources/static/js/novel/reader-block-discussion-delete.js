/**
 * KiemLai Universe — Novel Block Discussion Delete (MS-05E5G4C2)
 *
 * Responsibilities:
 * - Manages inline deletion confirmation and deletion of own comments inside the discussion drawer.
 * - Supports deleting active root comments and active replies where canDelete === true && !tombstone.
 * - Displays a contextual, bounded confirmation UI attached to the target comment:
 *     - Root: "Xóa bình luận này? Toàn bộ cuộc thảo luận này sẽ không còn hiển thị."
 *     - Reply: "Xóa bình luận này?"
 * - Single-confirmation discipline: opening Delete for comment B closes any active confirmation on A.
 * - Closing/clearing on drawer close, chapter change, block discussion switch, or drawer reload.
 * - Automatically closes if user switches to Edit or Reply on comments.
 * - Issues DELETE /api/novel/chapters/{chapterId}/comments/{commentId} with CSRF header.
 * - Prevents double submission while request is pending.
 * - On HTTP 204 No Content: closes confirmation and triggers authoritative drawer and indicators refresh.
 * - Strict race safety via generation tokens and lifecycle teardown (handling exact ABA races).
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderBlockDiscussionDelete = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderBlockDiscussionDelete = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const CONFIRMATION_CLASS = 'novel-delete-confirmation';
    const CONFIRMATION_MSG_CLASS = 'novel-delete-confirmation-message';
    const CONFIRMATION_STATUS_CLASS = 'novel-delete-confirmation-status';
    const CONFIRMATION_ACTIONS_CLASS = 'novel-delete-confirmation-actions';
    const CONFIRM_BTN_CLASS = 'novel-delete-confirmation-confirm';
    const CANCEL_BTN_CLASS = 'novel-delete-confirmation-cancel';

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_LOADED = 'kiemlai:block-discussion-loaded';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let injectedFetch = null;
    let injectedDrawer = null;
    let injectedIndicators = null;
    let injectedEditComposer = null;
    let injectedReplyComposer = null;
    let isDeleting = false;
    let currentMutationToken = 0;
    let activeDeleteTarget = null;
    let activeConfirmationEl = null;

    let nodeCommentMutations = null;
    if (typeof require === 'function') {
        try {
            nodeCommentMutations = require('./reader-comment-mutations.js');
        } catch (_) {}
    }

    let injectedCommentMutations = null;

    /**
     * Resolves the shared comment mutations client.
     *
     * @returns {Object|null}
     */
    function resolveCommentMutations() {
        if (injectedCommentMutations) {
            return injectedCommentMutations;
        }
        if (typeof window !== 'undefined') {
            return window.NovelReaderCommentMutations ||
                (window.KiemLai && window.KiemLai.NovelReaderCommentMutations) ||
                null;
        }
        return nodeCommentMutations;
    }

    /**
     * Resolves the drawer module instance.
     *
     * @returns {Object|null}
     */
    function resolveDrawerModule() {
        if (injectedDrawer) {
            return injectedDrawer;
        }
        if (typeof window !== 'undefined') {
            return window.NovelReaderBlockDiscussionDrawer ||
                (window.KiemLai && window.KiemLai.NovelReaderBlockDiscussionDrawer) ||
                null;
        }
        return null;
    }

    /**
     * Resolves the indicators module instance.
     *
     * @returns {Object|null}
     */
    function resolveIndicatorsModule() {
        if (injectedIndicators) {
            return injectedIndicators;
        }
        if (typeof window !== 'undefined') {
            return window.NovelChapterCommentIndicators ||
                (window.KiemLai && window.KiemLai.NovelChapterCommentIndicators) ||
                null;
        }
        return null;
    }

    /**
     * Closes other active mutation composers (edit or reply) if present.
     */
    function closeOtherComposers() {
        const editMod = injectedEditComposer || (typeof window !== 'undefined' && (
            window.NovelReaderBlockDiscussionEditComposer ||
            (window.KiemLai && window.KiemLai.NovelReaderBlockDiscussionEditComposer)
        )) || null;

        if (editMod && typeof editMod.closeEditComposer === 'function') {
            try {
                editMod.closeEditComposer(false);
            } catch (_) {}
        }

        const replyMod = injectedReplyComposer || (typeof window !== 'undefined' && (
            window.NovelReaderBlockDiscussionReplyComposer ||
            (window.KiemLai && window.KiemLai.NovelReaderBlockDiscussionReplyComposer)
        )) || null;

        if (replyMod && typeof replyMod.closeReplyComposer === 'function') {
            try {
                replyMod.closeReplyComposer(false);
            } catch (_) {}
        }
    }

    /**
     * Extracts CSRF token and header name from document <meta> tags.
     *
     * @param {Document} [doc]
     * @returns {{token: string, headerName: string}|null}
     */
    function getCsrf(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.querySelector !== 'function') {
            return null;
        }

        const tokenMeta = d.querySelector('meta[name="_csrf"]');
        const headerMeta = d.querySelector('meta[name="_csrf_header"]');

        const token = tokenMeta && typeof tokenMeta.getAttribute === 'function'
            ? (tokenMeta.getAttribute('content') || '').trim()
            : '';
        const headerName = headerMeta && typeof headerMeta.getAttribute === 'function'
            ? (headerMeta.getAttribute('content') || '').trim()
            : '';

        if (!token || !headerName) {
            return null;
        }

        return { token, headerName };
    }

    /**
     * Updates the status message on a delete confirmation container.
     *
     * @param {Element|null} statusEl
     * @param {string} message
     * @param {'error'|'info'|''} [type='']
     */
    function setStatus(statusEl, message, type) {
        if (!statusEl) {
            return;
        }
        statusEl.textContent = message || '';

        if (statusEl.classList) {
            if (type === 'error') {
                statusEl.classList.add('is-error');
                statusEl.classList.remove('is-info');
            } else if (type === 'info') {
                statusEl.classList.add('is-info');
                statusEl.classList.remove('is-error');
            } else {
                statusEl.classList.remove('is-error');
                statusEl.classList.remove('is-info');
            }
        }
    }

    /**
     * Closes and removes any active delete confirmation from DOM, restoring hidden action buttons.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeDeleteConfirmation(restoreFocus) {
        currentMutationToken++;
        if (activeConfirmationEl && activeConfirmationEl.parentNode) {
            try {
                activeConfirmationEl.parentNode.removeChild(activeConfirmationEl);
            } catch (_) {}
        }
        activeConfirmationEl = null;

        const prevTarget = activeDeleteTarget;
        if (prevTarget) {
            if (prevTarget.commentEl && typeof prevTarget.commentEl.querySelector === 'function') {
                const actionsEl = prevTarget.commentEl.querySelector('.novel-comment-actions');
                if (actionsEl) {
                    actionsEl.hidden = false;
                    if (actionsEl.style) {
                        actionsEl.style.display = '';
                    }
                }
            }
        }

        activeDeleteTarget = null;
        isDeleting = false;

        if (restoreFocus && prevTarget && prevTarget.deleteBtn && typeof prevTarget.deleteBtn.focus === 'function') {
            try {
                prevTarget.deleteBtn.focus();
            } catch (_) {}
        }
    }

    /**
     * Opens or switches delete confirmation for an exact comment target.
     *
     * @param {{commentId: string, rootId: string, isRoot: boolean, chapterId: string, blockKey: string, commentEl: Element, deleteBtn: Element|null}} targetInfo
     */
    function openDeleteConfirmation(targetInfo) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || !targetInfo || !targetInfo.commentEl) {
            return;
        }

        // Close other mutation composers if active
        closeOtherComposers();

        // Close any currently active confirmation (clearing previous target)
        closeDeleteConfirmation(false);
        currentMutationToken++;

        activeDeleteTarget = {
            commentId: targetInfo.commentId,
            rootId: targetInfo.rootId,
            isRoot: Boolean(targetInfo.isRoot),
            chapterId: targetInfo.chapterId,
            blockKey: targetInfo.blockKey,
            commentEl: targetInfo.commentEl,
            deleteBtn: targetInfo.deleteBtn
        };

        // Hide comment actions while confirmation is active
        const actionsEl = activeDeleteTarget.commentEl.querySelector ? activeDeleteTarget.commentEl.querySelector('.novel-comment-actions') : null;
        if (actionsEl) {
            actionsEl.hidden = true;
            if (actionsEl.style) {
                actionsEl.style.display = 'none';
            }
        }

        const container = doc.createElement('div');
        container.className = CONFIRMATION_CLASS;
        container.setAttribute('role', 'alertdialog');
        container.setAttribute('aria-modal', 'false');

        // Message: stronger warning for root comment since deleting root hides the whole thread
        const msgEl = doc.createElement('div');
        msgEl.className = CONFIRMATION_MSG_CLASS;
        msgEl.textContent = activeDeleteTarget.isRoot
            ? 'Xóa bình luận này? Toàn bộ cuộc thảo luận này sẽ không còn hiển thị.'
            : 'Xóa bình luận này?';

        // Status element for loading / error feedback
        const statusEl = doc.createElement('div');
        statusEl.className = CONFIRMATION_STATUS_CLASS;
        statusEl.setAttribute('role', 'status');
        statusEl.setAttribute('aria-live', 'polite');

        // Action buttons (Cancel & Confirm)
        const actionsDiv = doc.createElement('div');
        actionsDiv.className = CONFIRMATION_ACTIONS_CLASS;

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = CANCEL_BTN_CLASS;
        cancelBtn.textContent = 'Hủy';

        const confirmBtn = doc.createElement('button');
        confirmBtn.type = 'button';
        confirmBtn.className = CONFIRM_BTN_CLASS;
        confirmBtn.textContent = 'Xóa';

        actionsDiv.appendChild(cancelBtn);
        actionsDiv.appendChild(confirmBtn);

        container.appendChild(msgEl);
        container.appendChild(statusEl);
        container.appendChild(actionsDiv);

        if (actionsEl && actionsEl.parentNode === activeDeleteTarget.commentEl) {
            activeDeleteTarget.commentEl.insertBefore(container, actionsEl);
        } else {
            activeDeleteTarget.commentEl.appendChild(container);
        }

        activeConfirmationEl = container;

        if (typeof cancelBtn.focus === 'function') {
            try {
                cancelBtn.focus();
            } catch (_) {}
        }
    }

    /**
     * Checks dynamically whether mutation ownership is still valid.
     *
     * @param {number} mutationToken
     * @param {{chapterId: string, commentId: string, blockKey: string}} snapshot
     * @returns {boolean}
     */
    function isMutationContextCurrent(mutationToken, snapshot) {
        if (mutationToken !== currentMutationToken) {
            return false;
        }
        if (!activeDeleteTarget) {
            return false;
        }
        return activeDeleteTarget.chapterId === snapshot.chapterId &&
            activeDeleteTarget.commentId === snapshot.commentId &&
            activeDeleteTarget.blockKey === snapshot.blockKey;
    }

    /**
     * Handles confirmed comment deletion.
     *
     * @returns {Promise<void>}
     */
    async function handleConfirmDelete() {
        if (isDeleting) {
            return;
        }

        if (!activeDeleteTarget || !activeConfirmationEl) {
            return;
        }

        const statusEl = activeConfirmationEl.querySelector('.' + CONFIRMATION_STATUS_CLASS);
        const confirmBtn = activeConfirmationEl.querySelector('.' + CONFIRM_BTN_CLASS);
        const cancelBtn = activeConfirmationEl.querySelector('.' + CANCEL_BTN_CLASS);

        isDeleting = true;
        const mutationToken = ++currentMutationToken;
        const snapshot = {
            chapterId: activeDeleteTarget.chapterId,
            commentId: activeDeleteTarget.commentId,
            rootId: activeDeleteTarget.rootId,
            blockKey: activeDeleteTarget.blockKey,
            isRoot: activeDeleteTarget.isRoot
        };

        if (confirmBtn) confirmBtn.disabled = true;
        if (cancelBtn) cancelBtn.disabled = true;
        setStatus(statusEl, 'Đang xóa...', 'info');

        const mutationsClient = resolveCommentMutations();
        if (!mutationsClient || typeof mutationsClient.deleteComment !== 'function') {
            isDeleting = false;
            if (confirmBtn) confirmBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            setStatus(statusEl, 'Hệ thống xóa bình luận chưa sẵn sàng.', 'error');
            return;
        }

        try {
            const res = await mutationsClient.deleteComment(
                {
                    chapterId: snapshot.chapterId,
                    commentId: snapshot.commentId
                },
                {
                    document: currentDoc,
                    fetch: injectedFetch
                }
            );

            if (res && (res.status === 204 || res.status === 200 || res.ok)) {
                if (isMutationContextCurrent(mutationToken, snapshot)) {
                    isDeleting = false;
                    closeDeleteConfirmation(false);

                    // 1. Refresh authoritative drawer (preserves open drawer and updates threads)
                    const drawer = resolveDrawerModule();
                    if (drawer && typeof drawer.refreshActiveDiscussion === 'function') {
                        try {
                            drawer.refreshActiveDiscussion().catch(function () {});
                        } catch (_) {}
                    }

                    // 2. Refresh block indicators so paragraph badge updates with new counts
                    const indicators = resolveIndicatorsModule();
                    if (indicators && typeof indicators.refreshChapterIndicators === 'function') {
                        try {
                            indicators.refreshChapterIndicators().catch(function () {});
                        } catch (_) {}
                    }
                }
                return;
            }

            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }

            isDeleting = false;
            if (confirmBtn) confirmBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            const status = res ? res.status : 0;
            if (status === 401 || status === 403) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền xóa bình luận này.', 'error');
            } else if (status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else {
                setStatus(statusEl, 'Không thể xóa bình luận. Vui lòng thử lại.', 'error');
            }
        } catch (err) {
            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }
            isDeleting = false;
            if (confirmBtn) confirmBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            if (err && err.code === 'CSRF_MISSING') {
                setStatus(statusEl, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.', 'error');
            } else if (err && err.code === 'FETCH_UNAVAILABLE') {
                setStatus(statusEl, 'Không thể xóa bình luận. Trình duyệt không hỗ trợ fetch.', 'error');
            } else if (err && (err.status === 401 || err.status === 403)) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền xóa bình luận này.', 'error');
            } else if (err && err.status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else {
                setStatus(statusEl, 'Không thể xóa bình luận. Vui lòng thử lại.', 'error');
            }
        }
    }

    /**
     * Resolves target comment info from a clicked delete button or element.
     *
     * @param {Element} deleteBtn
     * @returns {Object|null}
     */
    function resolveTargetFromButton(deleteBtn) {
        if (!deleteBtn) return null;

        const commentId = deleteBtn.getAttribute('data-comment-id');
        if (!commentId) return null;

        let rootId = deleteBtn.getAttribute('data-root-id');

        let commentEl = null;
        if (typeof deleteBtn.closest === 'function') {
            commentEl = deleteBtn.closest('.novel-comment');
        } else {
            let cur = deleteBtn.parentElement;
            while (cur) {
                if (cur.classList && cur.classList.contains('novel-comment')) {
                    commentEl = cur;
                    break;
                }
                cur = cur.parentElement;
            }
        }

        if (!commentEl) return null;

        if (!rootId) {
            let threadCard = null;
            if (typeof commentEl.closest === 'function') {
                threadCard = commentEl.closest('.novel-block-discussion-thread');
            } else {
                let cur = commentEl.parentElement;
                while (cur) {
                    if (cur.classList && cur.classList.contains('novel-block-discussion-thread')) {
                        threadCard = cur;
                        break;
                    }
                    cur = cur.parentElement;
                }
            }
            if (threadCard && typeof threadCard.getAttribute === 'function') {
                rootId = threadCard.getAttribute('data-root-id') || commentId;
            } else {
                rootId = commentId;
            }
        }

        const isRoot = (commentEl.classList && commentEl.classList.contains('novel-comment--root')) ||
            (deleteBtn.getAttribute('data-action') === 'delete' && !deleteBtn.hasAttribute('data-reply-id') && commentId === rootId);

        const drawer = resolveDrawerModule();
        const activeCtx = drawer && typeof drawer.getActiveContext === 'function' ? drawer.getActiveContext() : null;
        const chapterId = activeCtx ? activeCtx.chapterId : '';
        const blockKey = activeCtx ? activeCtx.blockKey : '';

        return {
            commentId: commentId,
            rootId: rootId,
            isRoot: isRoot,
            chapterId: chapterId,
            blockKey: blockKey,
            commentEl: commentEl,
            deleteBtn: deleteBtn
        };
    }

    /**
     * Handles delete button click event.
     *
     * @param {Event} e
     */
    function handleDeleteButtonClick(e) {
        const target = e.target;
        if (!target) return;

        let deleteBtn = null;
        if (typeof target.closest === 'function') {
            deleteBtn = target.closest('.novel-comment-delete-btn') || target.closest('button[data-action="delete"]');
        } else if (target.classList && target.classList.contains('novel-comment-delete-btn')) {
            deleteBtn = target;
        }

        if (!deleteBtn) return;

        if (typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const targetInfo = resolveTargetFromButton(deleteBtn);
        if (!targetInfo) return;

        openDeleteConfirmation(targetInfo);
    }

    /**
     * Binds document event listeners.
     *
     * @param {Document} doc
     */
    function bindEvents(doc) {
        if (!doc || typeof doc.addEventListener !== 'function') return;
        if (boundDoc === doc) return;
        boundDoc = doc;
        currentDoc = doc;

        // 1. Delegated click handler
        doc.addEventListener('click', function (e) {
            const target = e.target;
            if (!target) return;

            // Delete button clicked
            if (typeof target.closest === 'function' && (target.closest('.novel-comment-delete-btn') || target.closest('button[data-action="delete"]'))) {
                handleDeleteButtonClick(e);
                return;
            }

            // Cancel button inside confirmation
            if (typeof target.closest === 'function' && target.closest('.' + CANCEL_BTN_CLASS)) {
                if (typeof e.preventDefault === 'function') e.preventDefault();
                closeDeleteConfirmation(true);
                return;
            }

            // Confirm button inside confirmation
            if (typeof target.closest === 'function' && target.closest('.' + CONFIRM_BTN_CLASS)) {
                if (typeof e.preventDefault === 'function') e.preventDefault();
                handleConfirmDelete();
                return;
            }

            // Switching to Edit or Reply on any comment closes active delete confirmation
            if (typeof target.closest === 'function' && (
                target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]') ||
                target.closest('.novel-comment-reply-btn') || target.closest('button[data-action="reply"]')
            )) {
                closeDeleteConfirmation(false);
                return;
            }
        });

        // 2. Chapter transition: clear state and pending UI
        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            currentMutationToken++;
            closeDeleteConfirmation(false);
        });

        // 3. Drawer closed: clear delete confirmation
        doc.addEventListener(EVENT_DISCUSSION_CLOSED, function () {
            currentMutationToken++;
            closeDeleteConfirmation(false);
        });

        // 4. Block discussion requested: new block requested
        doc.addEventListener(EVENT_DISCUSSION_REQUESTED, function () {
            currentMutationToken++;
            closeDeleteConfirmation(false);
        });

        // 5. Block discussion loaded / refreshed: clear stale confirmation
        doc.addEventListener(EVENT_DISCUSSION_LOADED, function () {
            currentMutationToken++;
            closeDeleteConfirmation(false);
        });

        // 6. Keyboard Escape cancels active confirmation
        doc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (activeConfirmationEl) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeDeleteConfirmation(true);
                }
            }
        });
    }

    /**
     * Initializes the Novel Block Discussion Delete module.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderBlockDiscussionDelete(targetDoc, options) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return null;
        currentDoc = doc;

        if (options) {
            if (options.commentMutations || options.mutationsClient || options.mutations) {
                injectedCommentMutations = options.commentMutations || options.mutationsClient || options.mutations;
            }
            if (options.fetchFn || options.fetch) injectedFetch = options.fetchFn || options.fetch;
            if (options.drawerModule) injectedDrawer = options.drawerModule;
            if (options.indicatorsModule) injectedIndicators = options.indicatorsModule;
            if (options.editComposerModule) injectedEditComposer = options.editComposerModule;
            if (options.replyComposerModule) injectedReplyComposer = options.replyComposerModule;
        }

        bindEvents(doc);

        return {
            openDeleteConfirmation,
            closeDeleteConfirmation,
            handleConfirmDelete
        };
    }

    /**
     * Resets module state (for test teardown).
     */
    function resetDeleteState() {
        closeDeleteConfirmation(false);
        currentDoc = null;
        boundDoc = null;
        injectedFetch = null;
        injectedDrawer = null;
        injectedIndicators = null;
        injectedEditComposer = null;
        injectedReplyComposer = null;
        injectedCommentMutations = null;
        isDeleting = false;
        currentMutationToken = 0;
        activeDeleteTarget = null;
        activeConfirmationEl = null;
    }

    // Auto-init on browser DOMContentLoaded
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderBlockDiscussionDelete(document);
            });
        } else {
            initReaderBlockDiscussionDelete(document);
        }
    }

    return {
        CONFIRMATION_CLASS,
        CONFIRMATION_MSG_CLASS,
        CONFIRMATION_STATUS_CLASS,
        CONFIRMATION_ACTIONS_CLASS,
        CONFIRM_BTN_CLASS,
        CANCEL_BTN_CLASS,
        initReaderBlockDiscussionDelete,
        resetDeleteState,
        openDeleteConfirmation,
        closeDeleteConfirmation,
        handleConfirmDelete,
        handleDeleteButtonClick,
        resolveTargetFromButton,
        getActiveDeleteTarget: function () { return activeDeleteTarget; },
        getActiveConfirmationEl: function () { return activeConfirmationEl; },
        isDeletingComment: function () { return isDeleting; },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setDrawerModule: function (mod) { injectedDrawer = mod; },
        setIndicatorsModule: function (mod) { injectedIndicators = mod; },
        setCommentMutations: function (m) { injectedCommentMutations = m; }
    };
});
