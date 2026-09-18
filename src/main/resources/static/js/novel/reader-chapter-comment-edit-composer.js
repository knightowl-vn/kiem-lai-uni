/**
 * KiemLai Universe — Bottom Chapter Comment Edit Composer UI (MS-05E5H2F3B)
 *
 * Responsibilities:
 * - Provides single-instance inline edit composer across the bottom chapter comments section (#novelChapterComments).
 * - Delegates click on "Chỉnh sửa" menu items ([data-action="edit"], .novel-comment-edit-btn).
 * - Enforces strict containment within #novelChapterComments, ignoring Drawer actions.
 * - Extracts clean body text omitting Wattpad-style @mention on replies.
 * - Mutually exclusive with Bottom Reply composer and Bottom Delete confirmation.
 * - Enforces client-side validation (non-empty body).
 * - Single-flight submission with monotonic token race invalidation (currentMutationToken++).
 * - Uses shared transport NovelReaderCommentMutations.editComment(...).
 * - On success: closes composer and invokes commentsModule.refreshRootThread(rootId).
 * - If refreshRootThread fails after success, displays error on #novelChapterCommentsStatus without re-editing.
 * - Responds to 'kiemlai:chapter-changed' and 'kiemlai:chapter-comments-feed-replacing' to close
 *   active composer and invalidate pending mutation tokens.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderChapterCommentEditComposer = exports;
        root.NovelChapterCommentEditComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderChapterCommentEditComposer = exports;
        root.KiemLai.NovelChapterCommentEditComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const SECTION_ID = 'novelChapterComments';
    const STATUS_ID = 'novelChapterCommentsStatus';

    const EDIT_COMPOSER_CLASS = 'novel-chapter-comment-edit-composer';
    const EDIT_INPUT_CLASS = 'novel-chapter-comment-edit-input';
    const EDIT_STATUS_CLASS = 'novel-chapter-comment-edit-status';
    const EDIT_ACTIONS_CLASS = 'novel-chapter-comment-edit-actions';
    const EDIT_SUBMIT_CLASS = 'novel-chapter-comment-edit-submit';
    const EDIT_CANCEL_CLASS = 'novel-chapter-comment-edit-cancel';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_FEED_REPLACING = 'kiemlai:chapter-comments-feed-replacing';

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeEditTarget = null;
    let activeComposerEl = null;

    let injectedFetch = null;
    let injectedMutations = null;
    let injectedCommentsModule = null;
    let injectedReplyComposer = null;
    let injectedDeleteModule = null;

    // Stable listener references
    let delegatedClickHandler = null;
    let submitHandler = null;
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
     * Closes other Bottom interactions (Reply composer and Delete confirmation) if active.
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

        const deleteMod = injectedDeleteModule || (typeof window !== 'undefined' && (
            window.NovelReaderChapterCommentDelete ||
            (window.KiemLai && window.KiemLai.NovelReaderChapterCommentDelete)
        )) || null;
        if (deleteMod && typeof deleteMod.closeDeleteConfirmation === 'function') {
            try {
                deleteMod.closeDeleteConfirmation(false);
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
     * Extracts clean comment text, omitting Wattpad-style @parentName mention if present.
     *
     * @param {Element} commentEl
     * @returns {string}
     */
    function extractCommentBody(commentEl) {
        if (!commentEl || typeof commentEl.querySelector !== 'function') {
            return '';
        }
        const replyBodyText = commentEl.querySelector('.novel-comment-reply-body-text');
        if (replyBodyText) {
            return (replyBodyText.textContent || '').trim();
        }
        const bodyEl = commentEl.querySelector('.novel-comment-body');
        if (bodyEl) {
            const mentionEl = bodyEl.querySelector ? bodyEl.querySelector('.novel-comment-reply-mention') : null;
            if (mentionEl) {
                let text = bodyEl.textContent || '';
                const mentionText = mentionEl.textContent || '';
                if (mentionText && text.startsWith(mentionText)) {
                    text = text.slice(mentionText.length);
                }
                return text.trim();
            }
            return (bodyEl.textContent || '').trim();
        }
        return '';
    }

    /**
     * Closes and removes any active edit composer from DOM, restoring hidden body elements.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeEditComposer(restoreFocus) {
        currentMutationToken++;
        if (activeComposerEl && activeComposerEl.parentNode) {
            try {
                activeComposerEl.parentNode.removeChild(activeComposerEl);
            } catch (_) {}
        }
        activeComposerEl = null;

        const prevTarget = activeEditTarget;
        if (prevTarget) {
            if (prevTarget.bodyEl) {
                prevTarget.bodyEl.hidden = false;
                if (prevTarget.bodyEl.style) {
                    prevTarget.bodyEl.style.display = '';
                }
            }
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

        activeEditTarget = null;
        isSubmitting = false;

        if (restoreFocus && prevTarget && prevTarget.editBtn && typeof prevTarget.editBtn.focus === 'function') {
            try {
                prevTarget.editBtn.focus();
            } catch (_) {}
        }
    }

    /**
     * Resolves target comment info from clicked edit button.
     *
     * @param {Element} editBtn
     * @returns {Object|null}
     */
    function resolveTargetFromButton(editBtn) {
        if (!editBtn) return null;
        const commentId = editBtn.getAttribute('data-comment-id');
        if (!commentId) return null;

        let rootId = editBtn.getAttribute('data-root-id');
        let commentEl = null;
        if (typeof editBtn.closest === 'function') {
            commentEl = editBtn.closest('.novel-comment');
        } else {
            let cur = editBtn.parentElement;
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

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const chapterId = resolveChapterId(doc);
        const bodyEl = commentEl.querySelector ? commentEl.querySelector('.novel-comment-body') : null;
        const cleanBody = extractCommentBody(commentEl);

        return {
            commentId: commentId,
            rootId: rootId,
            chapterId: chapterId,
            cleanBody: cleanBody,
            commentEl: commentEl,
            bodyEl: bodyEl,
            editBtn: editBtn
        };
    }

    /**
     * Opens or switches inline edit composer for a comment.
     *
     * @param {Object} targetInfo
     */
    function openEditComposer(targetInfo) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || !targetInfo || !targetInfo.commentEl) return;

        closeOtherInteractions();
        closeEditComposer(false);
        currentMutationToken++;

        activeEditTarget = {
            commentId: targetInfo.commentId,
            rootId: targetInfo.rootId,
            chapterId: targetInfo.chapterId,
            cleanBody: targetInfo.cleanBody,
            commentEl: targetInfo.commentEl,
            bodyEl: targetInfo.bodyEl,
            editBtn: targetInfo.editBtn
        };

        // Hide existing comment body while editing
        if (activeEditTarget.bodyEl) {
            activeEditTarget.bodyEl.hidden = true;
            if (activeEditTarget.bodyEl.style) {
                activeEditTarget.bodyEl.style.display = 'none';
            }
        }

        // Hide comment actions while editing
        const actionsEl = activeEditTarget.commentEl.querySelector ? activeEditTarget.commentEl.querySelector('.novel-comment-actions') : null;
        if (actionsEl) {
            actionsEl.hidden = true;
            if (actionsEl.style) {
                actionsEl.style.display = 'none';
            }
        }

        const container = doc.createElement('form');
        container.className = EDIT_COMPOSER_CLASS;

        const textarea = doc.createElement('textarea');
        textarea.className = EDIT_INPUT_CLASS;
        textarea.setAttribute('aria-label', 'Chỉnh sửa bình luận');
        textarea.setAttribute('placeholder', 'Nhập nội dung chỉnh sửa...');
        textarea.value = targetInfo.cleanBody || '';

        const statusEl = doc.createElement('div');
        statusEl.className = EDIT_STATUS_CLASS;
        statusEl.setAttribute('role', 'status');
        statusEl.setAttribute('aria-live', 'polite');

        const actionsDiv = doc.createElement('div');
        actionsDiv.className = EDIT_ACTIONS_CLASS;

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = EDIT_CANCEL_CLASS;
        cancelBtn.textContent = 'Hủy';

        const submitBtn = doc.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = EDIT_SUBMIT_CLASS;
        submitBtn.textContent = 'Lưu';

        actionsDiv.appendChild(cancelBtn);
        actionsDiv.appendChild(submitBtn);

        container.appendChild(textarea);
        container.appendChild(statusEl);
        container.appendChild(actionsDiv);

        if (actionsEl && actionsEl.parentNode === activeEditTarget.commentEl) {
            activeEditTarget.commentEl.insertBefore(container, actionsEl);
        } else {
            activeEditTarget.commentEl.appendChild(container);
        }

        activeComposerEl = container;

        if (typeof textarea.focus === 'function') {
            try {
                textarea.focus();
            } catch (_) {}
        }
    }

    /**
     * Validates whether mutation ownership is still current against race conditions.
     *
     * @param {number} mutationToken
     * @param {{chapterId: string, commentId: string, rootId: string}} snapshot
     * @returns {boolean}
     */
    function isMutationContextCurrent(mutationToken, snapshot) {
        if (mutationToken !== currentMutationToken) {
            return false;
        }
        if (!activeEditTarget) {
            return false;
        }
        return activeEditTarget.chapterId === snapshot.chapterId &&
            activeEditTarget.commentId === snapshot.commentId &&
            activeEditTarget.rootId === snapshot.rootId;
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
     * Handles submission of the edit composer.
     *
     * @param {Event} [e]
     * @returns {Promise<void>}
     */
    async function handleSubmit(e) {
        if (e && typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        if (isSubmitting) {
            return;
        }

        if (!activeEditTarget || !activeComposerEl) {
            return;
        }

        const textarea = activeComposerEl.querySelector('.' + EDIT_INPUT_CLASS);
        const statusEl = activeComposerEl.querySelector('.' + EDIT_STATUS_CLASS);
        const submitBtn = activeComposerEl.querySelector('.' + EDIT_SUBMIT_CLASS);
        const cancelBtn = activeComposerEl.querySelector('.' + EDIT_CANCEL_CLASS);

        const newBody = textarea ? textarea.value.trim() : '';
        if (!newBody) {
            setStatus(statusEl, 'Vui lòng nhập nội dung bình luận.', 'error');
            if (textarea && typeof textarea.focus === 'function') {
                try { textarea.focus(); } catch (_) {}
            }
            return;
        }

        isSubmitting = true;
        const mutationToken = ++currentMutationToken;
        const snapshot = {
            chapterId: activeEditTarget.chapterId,
            commentId: activeEditTarget.commentId,
            rootId: activeEditTarget.rootId
        };

        if (textarea) textarea.disabled = true;
        if (submitBtn) submitBtn.disabled = true;
        if (cancelBtn) cancelBtn.disabled = true;
        setStatus(statusEl, 'Đang lưu...', 'info');

        const mutationsClient = resolveMutations();
        if (!mutationsClient || typeof mutationsClient.editComment !== 'function') {
            isSubmitting = false;
            if (textarea) textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            setStatus(statusEl, 'Hệ thống chỉnh sửa bình luận chưa sẵn sàng.', 'error');
            return;
        }

        try {
            const res = await mutationsClient.editComment(
                {
                    chapterId: snapshot.chapterId,
                    commentId: snapshot.commentId,
                    body: newBody
                },
                {
                    document: currentDoc,
                    fetch: injectedFetch
                }
            );

            if (res && (res.status === 204 || res.status === 200 || res.ok)) {
                if (isMutationContextCurrent(mutationToken, snapshot)) {
                    isSubmitting = false;
                    closeEditComposer(false);

                    // Authoritative refresh of the root thread
                    const commentsModule = resolveCommentsModule();
                    if (commentsModule && typeof commentsModule.refreshRootThread === 'function') {
                        const refreshLifecycleToken = ++currentMutationToken;
                        const targetDoc = currentDoc;
                        const targetChapterId = snapshot.chapterId;
                        try {
                            await commentsModule.refreshRootThread(snapshot.rootId);
                        } catch (_) {
                            if (isRefreshLifecycleCurrent(refreshLifecycleToken, targetDoc, targetChapterId)) {
                                setSectionStatus('Bình luận đã được chỉnh sửa, nhưng chưa thể tải lại thảo luận.', 'error');
                            }
                        }
                    }
                }
                return;
            }

            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }

            isSubmitting = false;
            if (textarea) textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            const status = res ? res.status : 0;
            if (status === 400) {
                setStatus(statusEl, 'Nội dung chỉnh sửa không hợp lệ.', 'error');
            } else if (status === 401 || status === 403) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.', 'error');
            } else if (status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else {
                setStatus(statusEl, 'Không thể lưu chỉnh sửa. Vui lòng thử lại.', 'error');
            }
        } catch (err) {
            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }

            isSubmitting = false;
            if (textarea) textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            if (err && err.code === 'CSRF_MISSING') {
                setStatus(statusEl, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.', 'error');
            } else if (err && err.code === 'FETCH_UNAVAILABLE') {
                setStatus(statusEl, 'Không thể chỉnh sửa bình luận. Trình duyệt không hỗ trợ fetch.', 'error');
            } else if (err && (err.status === 401 || err.status === 403)) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.', 'error');
            } else if (err && err.status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else if (err && err.status === 400) {
                setStatus(statusEl, 'Nội dung chỉnh sửa không hợp lệ.', 'error');
            } else {
                setStatus(statusEl, 'Không thể lưu chỉnh sửa. Vui lòng thử lại.', 'error');
            }
        }
    }

    /**
     * Handles edit button click.
     *
     * @param {Event} e
     */
    function handleEditButtonClick(e) {
        const target = e ? (e.target || e.srcElement || e) : null;
        if (!target) return;

        let editBtn = null;
        if (typeof target.closest === 'function') {
            editBtn = target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]');
        } else if (target.classList && target.classList.contains('novel-comment-edit-btn')) {
            editBtn = target;
        }

        if (!editBtn || !isInsideBottomComments(editBtn)) return;

        if (typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const targetInfo = resolveTargetFromButton(editBtn);
        if (!targetInfo) return;

        openEditComposer(targetInfo);
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
                if (submitHandler) {
                    d.removeEventListener('submit', submitHandler);
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
        submitHandler = null;
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

            // Edit button click
            let editBtn = null;
            if (typeof target.closest === 'function') {
                editBtn = target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]');
            } else if (target.classList && target.classList.contains('novel-comment-edit-btn')) {
                editBtn = target;
            }
            if (editBtn) {
                if (isInsideBottomComments(editBtn)) {
                    handleEditButtonClick(e);
                }
                return;
            }

            // Cancel button click
            let cancelBtn = null;
            if (typeof target.closest === 'function') {
                cancelBtn = target.closest('.' + EDIT_CANCEL_CLASS);
            } else if (target.classList && target.classList.contains(EDIT_CANCEL_CLASS)) {
                cancelBtn = target;
            }
            if (cancelBtn) {
                if (isInsideBottomComments(cancelBtn)) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeEditComposer(true);
                }
                return;
            }
        };

        submitHandler = function (e) {
            const target = e.target;
            if (target && target.classList && target.classList.contains(EDIT_COMPOSER_CLASS)) {
                if (isInsideBottomComments(target)) {
                    handleSubmit(e);
                }
            }
        };

        chapterChangedHandler = function () {
            currentMutationToken++;
            closeEditComposer(false);
        };

        feedReplacingHandler = function () {
            currentMutationToken++;
            closeEditComposer(false);
        };

        keydownHandler = function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (activeComposerEl) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeEditComposer(true);
                }
            }
        };

        doc.addEventListener('click', delegatedClickHandler);
        doc.addEventListener('submit', submitHandler);
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
        isSubmitting = false;
        closeEditComposer(false);
        unbindEvents();

        currentDoc = null;
        boundDoc = null;
        activeEditTarget = null;
        activeComposerEl = null;

        injectedFetch = null;
        injectedMutations = null;
        injectedCommentsModule = null;
        injectedReplyComposer = null;
        injectedDeleteModule = null;
    }

    /**
     * Initializes the Novel Reader Chapter Comment Edit Composer module.
     * Safely destroys any previous bindings to ensure exactly one listener set.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderChapterCommentEditComposer(targetDoc, options) {
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
            if (options.deleteModule) injectedDeleteModule = options.deleteModule;
            if (options.fetchFn || options.fetch) injectedFetch = options.fetchFn || options.fetch;
        }

        bindEvents(doc);

        return {
            openEditComposer,
            closeEditComposer,
            handleSubmit,
            destroy
        };
    }

    /**
     * Resets module state (for test teardown). Delegates to destroy().
     */
    function resetEditComposerState() {
        destroy();
    }

    // Auto-init in browser DOMContentLoaded
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderChapterCommentEditComposer(document);
            });
        } else {
            initReaderChapterCommentEditComposer(document);
        }
    }

    return {
        SECTION_ID,
        STATUS_ID,
        EDIT_COMPOSER_CLASS,
        EDIT_INPUT_CLASS,
        EDIT_STATUS_CLASS,
        EDIT_ACTIONS_CLASS,
        EDIT_SUBMIT_CLASS,
        EDIT_CANCEL_CLASS,
        init: initReaderChapterCommentEditComposer,
        initReaderChapterCommentEditComposer,
        destroy,
        resetEditComposerState,
        openEditComposer,
        closeEditComposer,
        handleSubmit,
        handleEditButtonClick,
        resolveTargetFromButton,
        extractCommentBody,
        getActiveEditTarget: function () { return activeEditTarget; },
        getActiveComposerEl: function () { return activeComposerEl; },
        isSubmittingEdit: function () { return isSubmitting; },
        getCurrentMutationToken: function () { return currentMutationToken; },
        getState: function () {
            return {
                isSubmitting: isSubmitting,
                activeEditTarget: activeEditTarget,
                activeComposerEl: activeComposerEl,
                currentMutationToken: currentMutationToken
            };
        },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setMutationsClient: function (m) { injectedMutations = m; },
        setCommentsModule: function (m) { injectedCommentsModule = m; },
        setReplyComposerModule: function (m) { injectedReplyComposer = m; },
        setDeleteModule: function (m) { injectedDeleteModule = m; }
    };
});
