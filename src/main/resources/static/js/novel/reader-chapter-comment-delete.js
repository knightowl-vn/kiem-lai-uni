/**
 * KiemLai Universe — Bottom Chapter Comment Delete UI (MS-05E5H2F3B)
 *
 * Responsibilities:
 * - Provides inline deletion confirmation attached to the target comment in the bottom section (#novelChapterComments).
 * - Enforces strict containment within #novelChapterComments, ignoring Drawer actions.
 * - Displays contextual confirmation warning copy:
 *     - Root: "Xóa bình luận này? Toàn bộ cuộc thảo luận này sẽ không còn hiển thị."
 *     - Reply: "Xóa bình luận này?"
 * - Single-confirmation discipline: opening Delete on comment B closes active confirmation on A.
 * - Mutually exclusive with Bottom Reply composer and Bottom Edit composer.
 * - Single-flight submission with monotonic token race invalidation (currentMutationToken++).
 * - Uses shared transport NovelReaderCommentMutations.deleteComment(...).
 * - On success:
 *     - Reply: closes confirmation and invokes commentsModule.refreshRootThread(rootId).
 *     - Root: closes confirmation and invokes commentsModule.refreshFromPageZero().
 * - If refresh fails after successful DELETE, displays section error on #novelChapterCommentsStatus without re-deleting.
 * - Responds to 'kiemlai:chapter-changed' and 'kiemlai:chapter-comments-feed-replacing' to close
 *   active confirmation and invalidate pending mutation tokens.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderChapterCommentDelete = exports;
        root.NovelChapterCommentDelete = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderChapterCommentDelete = exports;
        root.KiemLai.NovelChapterCommentDelete = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const SECTION_ID = 'novelChapterComments';
    const STATUS_ID = 'novelChapterCommentsStatus';

    const CONFIRMATION_CLASS = 'novel-chapter-comment-delete-confirmation';
    const CONFIRMATION_MSG_CLASS = 'novel-chapter-comment-delete-message';
    const CONFIRMATION_STATUS_CLASS = 'novel-chapter-comment-delete-status';
    const CONFIRMATION_ACTIONS_CLASS = 'novel-chapter-comment-delete-actions';
    const CONFIRM_BTN_CLASS = 'novel-chapter-comment-delete-confirm';
    const CANCEL_BTN_CLASS = 'novel-chapter-comment-delete-cancel';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_FEED_REPLACING = 'kiemlai:chapter-comments-feed-replacing';

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let isDeleting = false;
    let currentMutationToken = 0;
    let activeDeleteTarget = null;
    let activeConfirmationEl = null;

    let injectedFetch = null;
    let injectedMutations = null;
    let injectedCommentsModule = null;
    let injectedReplyComposer = null;
    let injectedEditComposer = null;

    // Stable listener references
    let delegatedClickHandler = null;
    let chapterChangedHandler = null;
    let feedReplacingHandler = null;
    let keydownHandler = null;

    let nodeMutations = null;
    if (typeof require === 'function') {
        try {
            nodeMutations = require('./reader-comment-mutations.js');
        } catch (_) {}
    }

    /**
     * Checks whether an element is strictly inside the bottom chapter comments section.
     *
     * @param {Element|null} el
     * @returns {boolean}
     */
    function isInsideBottomComments(el) {
        if (!el) return false;
        if (typeof el.closest === 'function') {
            return el.closest('#' + SECTION_ID) !== null;
        }
        let cur = el;
        while (cur) {
            if (cur.id === SECTION_ID || (typeof cur.getAttribute === 'function' && cur.getAttribute('id') === SECTION_ID)) {
                return true;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    /**
     * Resolves the shared comment mutations client.
     *
     * @returns {Object|null}
     */
    function resolveMutations() {
        if (injectedMutations) {
            return injectedMutations;
        }
        if (typeof window !== 'undefined') {
            const m = window.NovelReaderCommentMutations ||
                (window.KiemLai && window.KiemLai.NovelReaderCommentMutations);
            if (m) return m;
        }
        return nodeMutations;
    }

    /**
     * Resolves the reader chapter comments module.
     *
     * @returns {Object|null}
     */
    function resolveCommentsModule() {
        if (injectedCommentsModule) {
            return injectedCommentsModule;
        }
        if (typeof window !== 'undefined') {
            const mod = window.NovelReaderChapterComments ||
                (window.KiemLai && window.KiemLai.NovelReaderChapterComments);
            if (mod) return mod;
        }
        if (typeof require === 'function') {
            try {
                return require('./reader-chapter-comments.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Resolves chapter ID from section or DOM body.
     *
     * @param {Document} doc
     * @returns {string}
     */
    function resolveChapterId(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return '';
        const section = d.getElementById ? d.getElementById(SECTION_ID) : null;
        if (section && typeof section.getAttribute === 'function') {
            const id = section.getAttribute('data-chapter-id');
            if (id && id.trim()) return id.trim();
        }
        if (typeof d.querySelector === 'function') {
            const bodyEl = d.querySelector('.novel-reader-chapter-body[data-chapter-id]');
            if (bodyEl && typeof bodyEl.getAttribute === 'function') {
                const id = bodyEl.getAttribute('data-chapter-id');
                if (id && id.trim()) return id.trim();
            }
            const fallbackEl = d.querySelector('[data-chapter-id]');
            if (fallbackEl && typeof fallbackEl.getAttribute === 'function') {
                const id = fallbackEl.getAttribute('data-chapter-id');
                if (id && id.trim()) return id.trim();
            }
        }
        return '';
    }

    /**
     * Closes other Bottom interactions (Reply composer and Edit composer) if active.
     */
    function closeOtherInteractions() {
        const replyMod = injectedReplyComposer || (typeof window !== 'undefined' && (
            window.NovelReaderChapterCommentReplyComposer ||
            (window.KiemLai && window.KiemLai.NovelReaderChapterCommentReplyComposer)
        )) || null;
        if (replyMod && typeof replyMod.closeActiveComposer === 'function') {
            try {
                replyMod.closeActiveComposer(false);
            } catch (_) {}
        }

        const editMod = injectedEditComposer || (typeof window !== 'undefined' && (
            window.NovelReaderChapterCommentEditComposer ||
            (window.KiemLai && window.KiemLai.NovelReaderChapterCommentEditComposer)
        )) || null;
        if (editMod && typeof editMod.closeEditComposer === 'function') {
            try {
                editMod.closeEditComposer(false);
            } catch (_) {}
        }
    }

    /**
     * Updates status element text and style.
     *
     * @param {Element|null} statusEl
     * @param {string} message
     * @param {'error'|'info'|''} [type='']
     */
    function setStatus(statusEl, message, type) {
        if (!statusEl) return;
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
     * Updates the main chapter comments section status element (fallback notification).
     *
     * @param {string} message
     * @param {'error'|'info'|''} [type='']
     */
    function setSectionStatus(message, type) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;
        const statusEl = doc.getElementById ? doc.getElementById(STATUS_ID) : null;
        if (statusEl) {
            setStatus(statusEl, message, type);
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
     * Resolves target comment info from clicked delete button.
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
                cur = cur.parentElement || cur.parentNode;
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
                    cur = cur.parentElement || cur.parentNode;
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

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const chapterId = resolveChapterId(doc);

        return {
            commentId: commentId,
            rootId: rootId,
            isRoot: Boolean(isRoot),
            chapterId: chapterId,
            commentEl: commentEl,
            deleteBtn: deleteBtn
        };
    }

    /**
     * Opens or switches inline delete confirmation for an exact comment.
     *
     * @param {Object} targetInfo
     */
    function openDeleteConfirmation(targetInfo) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || !targetInfo || !targetInfo.commentEl) return;

        closeOtherInteractions();
        closeDeleteConfirmation(false);
        currentMutationToken++;

        activeDeleteTarget = {
            commentId: targetInfo.commentId,
            rootId: targetInfo.rootId,
            isRoot: Boolean(targetInfo.isRoot),
            chapterId: targetInfo.chapterId,
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

        // Message: stronger warning for root comment since deleting root hides the entire thread
        const msgEl = doc.createElement('div');
        msgEl.className = CONFIRMATION_MSG_CLASS;
        msgEl.textContent = activeDeleteTarget.isRoot
            ? 'Xóa bình luận này? Toàn bộ cuộc thảo luận này sẽ không còn hiển thị.'
            : 'Xóa bình luận này?';

        const statusEl = doc.createElement('div');
        statusEl.className = CONFIRMATION_STATUS_CLASS;
        statusEl.setAttribute('role', 'status');
        statusEl.setAttribute('aria-live', 'polite');

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
     * Validates whether mutation ownership is still current against race conditions.
     *
     * @param {number} mutationToken
     * @param {{chapterId: string, commentId: string, rootId: string, isRoot: boolean}} snapshot
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
            activeDeleteTarget.rootId === snapshot.rootId;
    }

    /**
     * Validates whether a post-mutation refresh belongs to the current presentation lifecycle.
     *
     * @param {number} refreshLifecycleToken
     * @param {Document} targetDoc
     * @param {string} targetChapterId
     * @returns {boolean}
     */
    function isRefreshLifecycleCurrent(refreshLifecycleToken, targetDoc, targetChapterId) {
        if (refreshLifecycleToken !== currentMutationToken) {
            return false;
        }
        if (!currentDoc || currentDoc !== targetDoc) {
            return false;
        }
        if (targetChapterId && resolveChapterId(currentDoc) !== targetChapterId) {
            return false;
        }
        return true;
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
            isRoot: activeDeleteTarget.isRoot
        };

        if (confirmBtn) confirmBtn.disabled = true;
        if (cancelBtn) cancelBtn.disabled = true;
        setStatus(statusEl, 'Đang xóa...', 'info');

        const mutationsClient = resolveMutations();
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

                    const commentsModule = resolveCommentsModule();
                    if (commentsModule) {
                        const refreshLifecycleToken = ++currentMutationToken;
                        const targetDoc = currentDoc;
                        const targetChapterId = snapshot.chapterId;
                        try {
                            if (snapshot.isRoot) {
                                // Authoritative page-0 refresh for deleted root thread
                                if (typeof commentsModule.refreshFromPageZero === 'function') {
                                    await commentsModule.refreshFromPageZero();
                                }
                            } else {
                                // Authoritative root thread refresh for deleted reply
                                if (typeof commentsModule.refreshRootThread === 'function') {
                                    await commentsModule.refreshRootThread(snapshot.rootId);
                                }
                            }
                        } catch (_) {
                            if (isRefreshLifecycleCurrent(refreshLifecycleToken, targetDoc, targetChapterId)) {
                                setSectionStatus('Bình luận đã được xóa, nhưng chưa thể tải lại danh sách.', 'error');
                            }
                        }
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
     * Handles delete button click.
     *
     * @param {Event} e
     */
    function handleDeleteButtonClick(e) {
        const target = e ? (e.target || e.srcElement || e) : null;
        if (!target) return;

        let deleteBtn = null;
        if (typeof target.closest === 'function') {
            deleteBtn = target.closest('.novel-comment-delete-btn') || target.closest('button[data-action="delete"]');
        } else if (target.classList && target.classList.contains('novel-comment-delete-btn')) {
            deleteBtn = target;
        }

        if (!deleteBtn || !isInsideBottomComments(deleteBtn)) return;

        if (typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const targetInfo = resolveTargetFromButton(deleteBtn);
        if (!targetInfo) return;

        openDeleteConfirmation(targetInfo);
    }

    /**
     * Unbinds all registered event listeners from previously bound documents.
     *
     * @param {Document} [doc]
     */
    function unbindEvents(doc) {
        const docsToClean = new Set();
        if (boundDoc) docsToClean.add(boundDoc);
        if (currentDoc) docsToClean.add(currentDoc);
        if (doc) docsToClean.add(doc);

        for (const d of docsToClean) {
            if (d && typeof d.removeEventListener === 'function') {
                if (delegatedClickHandler) {
                    d.removeEventListener('click', delegatedClickHandler);
                }
                if (chapterChangedHandler) {
                    d.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
                }
                if (feedReplacingHandler) {
                    d.removeEventListener(EVENT_FEED_REPLACING, feedReplacingHandler);
                }
                if (keydownHandler) {
                    d.removeEventListener('keydown', keydownHandler);
                }
            }
        }

        delegatedClickHandler = null;
        chapterChangedHandler = null;
        feedReplacingHandler = null;
        keydownHandler = null;
        boundDoc = null;
    }

    /**
     * Binds document event listeners with stable handler references.
     *
     * @param {Document} doc
     */
    function bindEvents(doc) {
        if (!doc || typeof doc.addEventListener !== 'function') return;
        unbindEvents(doc);
        boundDoc = doc;
        currentDoc = doc;

        delegatedClickHandler = function (e) {
            const target = e.target;
            if (!target) return;

            // Delete button click
            let deleteBtn = null;
            if (typeof target.closest === 'function') {
                deleteBtn = target.closest('.novel-comment-delete-btn') || target.closest('button[data-action="delete"]');
            } else if (target.classList && target.classList.contains('novel-comment-delete-btn')) {
                deleteBtn = target;
            }
            if (deleteBtn) {
                if (isInsideBottomComments(deleteBtn)) {
                    handleDeleteButtonClick(e);
                }
                return;
            }

            // Cancel button click inside confirmation
            let cancelBtn = null;
            if (typeof target.closest === 'function') {
                cancelBtn = target.closest('.' + CANCEL_BTN_CLASS);
            } else if (target.classList && target.classList.contains(CANCEL_BTN_CLASS)) {
                cancelBtn = target;
            }
            if (cancelBtn) {
                if (isInsideBottomComments(cancelBtn)) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeDeleteConfirmation(true);
                }
                return;
            }

            // Confirm button click inside confirmation
            let confirmBtn = null;
            if (typeof target.closest === 'function') {
                confirmBtn = target.closest('.' + CONFIRM_BTN_CLASS);
            } else if (target.classList && target.classList.contains(CONFIRM_BTN_CLASS)) {
                confirmBtn = target;
            }
            if (confirmBtn) {
                if (isInsideBottomComments(confirmBtn)) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    handleConfirmDelete();
                }
                return;
            }
        };

        chapterChangedHandler = function () {
            currentMutationToken++;
            closeDeleteConfirmation(false);
        };

        feedReplacingHandler = function () {
            currentMutationToken++;
            closeDeleteConfirmation(false);
        };

        keydownHandler = function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (activeConfirmationEl) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeDeleteConfirmation(true);
                }
            }
        };

        doc.addEventListener('click', delegatedClickHandler);
        doc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
        doc.addEventListener(EVENT_FEED_REPLACING, feedReplacingHandler);
        doc.addEventListener('keydown', keydownHandler);
    }

    /**
     * Destroys module listeners and clears active state.
     * Preserves monotonic mutation generation without resetting to 0.
     */
    function destroy() {
        currentMutationToken++;
        isDeleting = false;
        closeDeleteConfirmation(false);
        unbindEvents();

        currentDoc = null;
        boundDoc = null;
        activeDeleteTarget = null;
        activeConfirmationEl = null;

        injectedFetch = null;
        injectedMutations = null;
        injectedCommentsModule = null;
        injectedReplyComposer = null;
        injectedEditComposer = null;
    }

    /**
     * Initializes the Novel Reader Chapter Comment Delete module.
     * Safely destroys any previous bindings to ensure exactly one listener set.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderChapterCommentDelete(targetDoc, options) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return null;

        destroy();

        currentDoc = doc;

        if (options) {
            if (options.commentMutations || options.mutationsClient || options.mutations) {
                injectedMutations = options.commentMutations || options.mutationsClient || options.mutations;
            }
            if (options.commentsModule) injectedCommentsModule = options.commentsModule;
            if (options.replyComposerModule) injectedReplyComposer = options.replyComposerModule;
            if (options.editComposerModule) injectedEditComposer = options.editComposerModule;
            if (options.fetchFn || options.fetch) injectedFetch = options.fetchFn || options.fetch;
        }

        bindEvents(doc);

        return {
            openDeleteConfirmation,
            closeDeleteConfirmation,
            handleConfirmDelete,
            destroy
        };
    }

    /**
     * Resets module state (for test teardown). Delegates to destroy().
     */
    function resetDeleteState() {
        destroy();
    }

    // Auto-init in browser DOMContentLoaded
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderChapterCommentDelete(document);
            });
        } else {
            initReaderChapterCommentDelete(document);
        }
    }

    return {
        SECTION_ID,
        STATUS_ID,
        CONFIRMATION_CLASS,
        CONFIRMATION_MSG_CLASS,
        CONFIRMATION_STATUS_CLASS,
        CONFIRMATION_ACTIONS_CLASS,
        CONFIRM_BTN_CLASS,
        CANCEL_BTN_CLASS,
        init: initReaderChapterCommentDelete,
        initReaderChapterCommentDelete,
        destroy,
        resetDeleteState,
        openDeleteConfirmation,
        closeDeleteConfirmation,
        handleConfirmDelete,
        handleDeleteButtonClick,
        resolveTargetFromButton,
        getActiveDeleteTarget: function () { return activeDeleteTarget; },
        getActiveConfirmationEl: function () { return activeConfirmationEl; },
        isDeletingComment: function () { return isDeleting; },
        getCurrentMutationToken: function () { return currentMutationToken; },
        getState: function () {
            return {
                isDeleting: isDeleting,
                activeDeleteTarget: activeDeleteTarget,
                activeConfirmationEl: activeConfirmationEl,
                currentMutationToken: currentMutationToken
            };
        },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setMutationsClient: function (m) { injectedMutations = m; },
        setCommentsModule: function (m) { injectedCommentsModule = m; },
        setReplyComposerModule: function (m) { injectedReplyComposer = m; },
        setEditComposerModule: function (m) { injectedEditComposer = m; }
    };
});
