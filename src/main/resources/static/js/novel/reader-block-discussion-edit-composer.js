/**
 * KiemLai Universe — Novel Block Discussion Edit Composer (MS-05E5G4B)
 *
 * Responsibilities:
 * - Manages inline editing of own comments inside the discussion drawer.
 * - Supports editing for active root comments and active replies where canEdit === true.
 * - Pre-fills edit textarea with the existing comment body (excluding Wattpad-style @mention prefix).
 * - Ensures single-composer discipline: opening editor for comment B closes editor on comment A.
 * - Provides "Lưu" (submit) and "Hủy" (cancel) actions, restoring focus to edit button on cancel.
 * - Issues PATCH /api/novel/chapters/{chapterId}/comments/{commentId} with:
 *     { "body": "..." }
 * - On HTTP 204 No Content: closes editor and triggers drawer.refreshActiveDiscussion().
 * - Preserves block indicators and paragraph counts without modification (edits do not change threadCount or commentCount).
 * - Preserves draft on server error or network failure.
 * - Strict race safety via generation tokens and lifecycle teardown.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderBlockDiscussionEditComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderBlockDiscussionEditComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const EDIT_COMPOSER_CLASS = 'novel-edit-composer';
    const EDIT_COMPOSER_FORM_ID = 'novelBlockDiscussionEditComposerForm';
    const EDIT_INPUT_CLASS = 'novel-edit-composer-input';
    const EDIT_STATUS_CLASS = 'novel-edit-composer-status';
    const EDIT_SUBMIT_CLASS = 'novel-edit-composer-submit';
    const EDIT_CANCEL_CLASS = 'novel-edit-composer-cancel';
    const EDIT_ACTIONS_CLASS = 'novel-edit-composer-actions';

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let injectedFetch = null;
    let injectedDrawer = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeEditTarget = null;
    let activeComposerEl = null;

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
     * Updates the status message on an edit composer.
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
            return replyBodyText.textContent || '';
        }
        const bodyEl = commentEl.querySelector('.novel-comment-body');
        if (bodyEl) {
            return bodyEl.textContent || '';
        }
        return '';
    }

    /**
     * Closes and removes any active edit composer form from DOM, restoring hidden elements.
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
     * Opens or switches edit composer for an exact comment target.
     *
     * @param {{commentId: string, rootId: string, chapterId: string, blockKey: string, cleanBody: string, commentEl: Element, bodyEl: Element, editBtn: Element|null}} targetInfo
     */
    function openEditComposer(targetInfo) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || !targetInfo || !targetInfo.commentEl) {
            return;
        }

        // Close any currently active composer (clearing previous target's draft and unhiding its body)
        closeEditComposer(false);
        currentMutationToken++;

        activeEditTarget = {
            commentId: targetInfo.commentId,
            rootId: targetInfo.rootId,
            chapterId: targetInfo.chapterId,
            blockKey: targetInfo.blockKey,
            cleanBody: targetInfo.cleanBody || '',
            commentEl: targetInfo.commentEl,
            bodyEl: targetInfo.bodyEl,
            editBtn: targetInfo.editBtn
        };

        // Hide original comment body while editing
        if (activeEditTarget.bodyEl) {
            activeEditTarget.bodyEl.hidden = true;
            if (activeEditTarget.bodyEl.style) {
                activeEditTarget.bodyEl.style.display = 'none';
            }
        }

        // Hide comment action buttons while editing
        const actionsEl = activeEditTarget.commentEl.querySelector ? activeEditTarget.commentEl.querySelector('.novel-comment-actions') : null;
        if (actionsEl) {
            actionsEl.hidden = true;
            if (actionsEl.style) {
                actionsEl.style.display = 'none';
            }
        }

        const form = doc.createElement('form');
        form.id = EDIT_COMPOSER_FORM_ID;
        form.className = EDIT_COMPOSER_CLASS;
        form.setAttribute('novalidate', '');

        // 1. Textarea prefilled with current comment body
        const textarea = doc.createElement('textarea');
        textarea.className = EDIT_INPUT_CLASS;
        textarea.setAttribute('rows', '3');
        textarea.setAttribute('aria-label', 'Chỉnh sửa bình luận');
        textarea.value = activeEditTarget.cleanBody;

        // 2. Status container
        const statusDiv = doc.createElement('div');
        statusDiv.className = EDIT_STATUS_CLASS;
        statusDiv.setAttribute('role', 'status');
        statusDiv.setAttribute('aria-live', 'polite');

        // 3. Action buttons
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

        form.appendChild(textarea);
        form.appendChild(statusDiv);
        form.appendChild(actionsDiv);

        // Append composer in place of body or after body
        if (activeEditTarget.bodyEl && activeEditTarget.bodyEl.nextSibling) {
            activeEditTarget.commentEl.insertBefore(form, activeEditTarget.bodyEl.nextSibling);
        } else {
            activeEditTarget.commentEl.appendChild(form);
        }
        activeComposerEl = form;

        // Focus textarea and position cursor at the end
        if (typeof textarea.focus === 'function') {
            try {
                textarea.focus();
                if (typeof textarea.setSelectionRange === 'function' && typeof textarea.value === 'string') {
                    textarea.setSelectionRange(textarea.value.length, textarea.value.length);
                }
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
        if (!activeEditTarget) {
            return false;
        }
        return activeEditTarget.chapterId === snapshot.chapterId &&
            activeEditTarget.commentId === snapshot.commentId &&
            activeEditTarget.blockKey === snapshot.blockKey;
    }

    /**
     * Handles edit form submission.
     *
     * @param {Event} [evt]
     * @returns {Promise<void>}
     */
    async function handleSubmit(evt) {
        if (evt && typeof evt.preventDefault === 'function') {
            evt.preventDefault();
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

        if (!textarea) {
            return;
        }

        const rawBody = (textarea.value || '').trim();
        if (!rawBody) {
            setStatus(statusEl, 'Vui lòng nhập nội dung bình luận.', 'error');
            if (typeof textarea.focus === 'function') {
                try {
                    textarea.focus();
                } catch (_) {}
            }
            return;
        }

        const csrf = getCsrf();
        if (!csrf) {
            setStatus(statusEl, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.', 'error');
            return;
        }

        isSubmitting = true;
        const mutationToken = ++currentMutationToken;
        const snapshot = {
            chapterId: activeEditTarget.chapterId,
            commentId: activeEditTarget.commentId,
            blockKey: activeEditTarget.blockKey
        };

        textarea.disabled = true;
        if (submitBtn) submitBtn.disabled = true;
        if (cancelBtn) cancelBtn.disabled = true;
        setStatus(statusEl, 'Đang lưu...', 'info');

        const url = '/api/novel/chapters/' + encodeURIComponent(snapshot.chapterId) +
            '/comments/' + encodeURIComponent(snapshot.commentId);

        const fetchImpl = injectedFetch || (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchImpl) {
            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            setStatus(statusEl, 'Không thể lưu bình luận. Trình duyệt không hỗ trợ fetch.', 'error');
            return;
        }

        try {
            const headers = {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            };
            headers[csrf.headerName] = csrf.token;

            const res = await fetchImpl(url, {
                method: 'PATCH',
                headers: headers,
                body: JSON.stringify({ body: rawBody })
            });

            if (res.status === 204) {
                if (isMutationContextCurrent(mutationToken, snapshot)) {
                    isSubmitting = false;
                    closeEditComposer(false);

                    // Refresh authoritative drawer (preserves open drawer and updates comment body)
                    const drawer = resolveDrawerModule();
                    if (drawer && typeof drawer.refreshActiveDiscussion === 'function') {
                        try {
                            drawer.refreshActiveDiscussion().catch(function () {});
                        } catch (_) {}
                    }
                }
                return;
            }

            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }

            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            if (res.status === 400) {
                setStatus(statusEl, 'Nội dung bình luận không hợp lệ.', 'error');
            } else if (res.status === 401 || res.status === 403) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.', 'error');
            } else if (res.status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else {
                setStatus(statusEl, 'Không thể lưu thay đổi. Vui lòng thử lại.', 'error');
            }
        } catch (_) {
            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }
            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            setStatus(statusEl, 'Lỗi kết nối mạng. Vui lòng thử lại.', 'error');
        }
    }

    /**
     * Resolves target comment info from a clicked edit button or element.
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

        const bodyEl = commentEl.querySelector ? commentEl.querySelector('.novel-comment-body') : null;
        const cleanBody = extractCommentBody(commentEl);

        const drawer = resolveDrawerModule();
        const activeCtx = drawer && typeof drawer.getActiveContext === 'function' ? drawer.getActiveContext() : null;
        const chapterId = activeCtx ? activeCtx.chapterId : '';
        const blockKey = activeCtx ? activeCtx.blockKey : '';

        return {
            commentId: commentId,
            rootId: rootId,
            chapterId: chapterId,
            blockKey: blockKey,
            cleanBody: cleanBody,
            commentEl: commentEl,
            bodyEl: bodyEl,
            editBtn: editBtn
        };
    }

    /**
     * Handles edit button click event.
     *
     * @param {Event} e
     */
    function handleEditButtonClick(e) {
        const target = e.target;
        if (!target) return;

        let editBtn = null;
        if (typeof target.closest === 'function') {
            editBtn = target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]');
        } else if (target.classList && target.classList.contains('novel-comment-edit-btn')) {
            editBtn = target;
        }

        if (!editBtn) return;

        if (typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const targetInfo = resolveTargetFromButton(editBtn);
        if (!targetInfo) return;

        openEditComposer(targetInfo);
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

        // 1. Edit button click & Cancel button click
        doc.addEventListener('click', function (e) {
            const target = e.target;
            if (!target) return;

            // Edit button
            if (typeof target.closest === 'function' && (target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]'))) {
                handleEditButtonClick(e);
                return;
            }

            // Cancel edit composer
            if (typeof target.closest === 'function' && target.closest('.' + EDIT_CANCEL_CLASS)) {
                if (typeof e.preventDefault === 'function') e.preventDefault();
                closeEditComposer(true);
                return;
            }
        });

        // 2. Submit edit composer form
        doc.addEventListener('submit', function (e) {
            const target = e.target;
            if (target && target.classList && target.classList.contains(EDIT_COMPOSER_CLASS)) {
                handleSubmit(e);
            }
        });

        // 3. Chapter transition: clear state and pending UI
        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            currentMutationToken++;
            closeEditComposer(false);
        });

        // 4. Drawer closed: clear edit composer
        doc.addEventListener(EVENT_DISCUSSION_CLOSED, function () {
            currentMutationToken++;
            closeEditComposer(false);
        });

        // 5. Block discussion requested: new block requested
        doc.addEventListener(EVENT_DISCUSSION_REQUESTED, function () {
            currentMutationToken++;
            closeEditComposer(false);
        });

        // 6. Keyboard Escape cancels active edit composer
        doc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (activeComposerEl) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeEditComposer(true);
                }
            }
        });
    }

    /**
     * Initializes the Novel Block Discussion Edit Composer module.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderBlockDiscussionEditComposer(targetDoc, options) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return null;
        currentDoc = doc;

        if (options && options.fetchFn) {
            injectedFetch = options.fetchFn;
        }
        if (options && options.drawerModule) {
            injectedDrawer = options.drawerModule;
        }

        bindEvents(doc);

        return {
            openEditComposer,
            closeEditComposer,
            handleSubmit
        };
    }

    /**
     * Resets module state (for test teardown).
     */
    function resetEditComposerState() {
        closeEditComposer(false);
        currentDoc = null;
        boundDoc = null;
        injectedFetch = null;
        injectedDrawer = null;
        isSubmitting = false;
        currentMutationToken = 0;
        activeEditTarget = null;
        activeComposerEl = null;
    }

    // Auto-init on browser DOMContentLoaded
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderBlockDiscussionEditComposer(document);
            });
        } else {
            initReaderBlockDiscussionEditComposer(document);
        }
    }

    return {
        EDIT_COMPOSER_CLASS,
        EDIT_COMPOSER_FORM_ID,
        EDIT_INPUT_CLASS,
        EDIT_STATUS_CLASS,
        EDIT_SUBMIT_CLASS,
        EDIT_CANCEL_CLASS,
        EDIT_ACTIONS_CLASS,
        initReaderBlockDiscussionEditComposer,
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
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setDrawerModule: function (mod) { injectedDrawer = mod; }
    };
});
