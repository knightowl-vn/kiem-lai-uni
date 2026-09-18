/**
 * KiemLai Universe — Novel Block Discussion Reply Composer (MS-05E5G4A)
 *
 * Responsibilities:
 * - Manages inline reply creation UX inside the discussion drawer.
 * - Adds reply action support for active root comments and active replies.
 * - Supports guest UX: guest clicking Reply does not POST; shows auth modal with
 *   Đăng nhập, Tạo tài khoản, and Hủy, equipped with validated semantic returnTo target:
 *     /novel/chapters/{slug}?discussionBlock={blockKey}&threadId={rootId}&replyTo={commentId}&intent=reply
 * - Dedicated reusable reply composer: displays "Đang trả lời <displayName>" context,
 *   textarea, submit button, and cancel button.
 * - Bounded drafts: switching target safely moves composer, bounds draft to selected target,
 *   and prevents submitting to a previous target.
 * - Consumes A3 restore event 'kiemlai:comment-reply-resume-requested' to open composer and
 *   focus textarea for the restored comment.
 * - Rejects false attachment: missing or tombstoned target after auth does NOT silently retarget.
 * - Issues POST /api/novel/chapters/{chapterId}/comments/{parentCommentId}/replies with:
 *     { "body": "..." }
 * - On HTTP 201: clears draft, closes reply composer, calls drawer.refreshActiveDiscussion().
 * - Count invariant: reply creation does NOT increment root discussion indicator count.
 * - Error and race safety: preserves draft on failure, disables duplicate submissions,
 *   invalidates UI on chapter-changed or drawer-closed.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderBlockDiscussionReplyComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderBlockDiscussionReplyComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const REPLY_COMPOSER_CLASS = 'novel-reply-composer';
    const REPLY_COMPOSER_FORM_ID = 'novelBlockDiscussionReplyComposerForm';
    const REPLY_INPUT_CLASS = 'novel-reply-composer-input';
    const REPLY_STATUS_CLASS = 'novel-reply-composer-status';
    const REPLY_SUBMIT_CLASS = 'novel-reply-composer-submit';
    const REPLY_CANCEL_CLASS = 'novel-reply-composer-cancel';
    const REPLY_CONTEXT_CLASS = 'novel-reply-composer-context';

    const AUTH_MODAL_ID = 'novelCommentAuthModal';
    const AUTH_LOGIN_LINK_ID = 'novelCommentAuthLoginLink';
    const AUTH_REGISTER_LINK_ID = 'novelCommentAuthRegisterLink';
    const AUTH_CANCEL_BTN_ID = 'novelCommentAuthCancelBtn';

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_LOADED = 'kiemlai:block-discussion-loaded';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_REPLY_RESUME_REQUESTED = 'kiemlai:comment-reply-resume-requested';

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let injectedFetch = null;
    let injectedDrawer = null;
    let injectedIndicators = null;
    let injectedEditComposer = null;
    let injectedCommentMutations = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeTarget = null;
    let activeComposerEl = null;
    let priorFocusedReplyBtn = null;

    let nodeCommentMutations = null;
    if (typeof require === 'function') {
        try {
            nodeCommentMutations = require('./reader-comment-mutations.js');
        } catch (_) {}
    }

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
        if (typeof globalThis !== 'undefined' && globalThis.NovelReaderCommentMutations) {
            return globalThis.NovelReaderCommentMutations;
        }
        if (nodeCommentMutations) {
            return nodeCommentMutations;
        }
        return null;
    }

    /**
     * Resolves the edit composer module instance.
     *
     * @returns {Object|null}
     */
    function resolveEditComposerModule() {
        if (injectedEditComposer) {
            return injectedEditComposer;
        }
        if (typeof window !== 'undefined') {
            return window.NovelReaderBlockDiscussionEditComposer ||
                (window.KiemLai && window.KiemLai.NovelReaderBlockDiscussionEditComposer) ||
                null;
        }
        return null;
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

    let injectedCommentsModule = null;

    let nodeCommentsModule = null;
    if (typeof require === 'function') {
        try {
            nodeCommentsModule = require('./reader-chapter-comments.js');
        } catch (_) {}
    }

    /**
     * Resolves the bottom chapter comments module instance.
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
        if (typeof globalThis !== 'undefined' && globalThis.NovelReaderChapterComments) {
            return globalThis.NovelReaderChapterComments;
        }
        return nodeCommentsModule;
    }

    /**
     * Checks whether a thread root is currently loaded in the bottom comments feed
     * using authoritative rootPageMap membership.
     *
     * @param {Object|null} bottomModule
     * @param {string} rootId
     * @returns {boolean}
     */
    function isBottomRootLoaded(bottomModule, rootId) {
        if (!bottomModule || !rootId || typeof bottomModule.getState !== 'function') {
            return false;
        }
        try {
            const state = bottomModule.getState();
            if (!state || !state.rootPageMap) {
                return false;
            }
            const strId = String(rootId).trim();
            return Object.prototype.hasOwnProperty.call(state.rootPageMap, strId) || (strId in state.rootPageMap);
        } catch (_) {
            return false;
        }
    }

    /**
     * Synchronizes bottom comments feed after successful reply creation.
     * If the root thread is loaded in Bottom, performs authoritative single-root refresh
     * with newly created comment reveal depth.
     * If the root thread is NOT loaded, falls back to page-0 refresh to update active comment counts.
     * Best-effort: errors are silently caught.
     *
     * @param {string} rootId
     * @param {string|null} newCommentId
     */
    function synchronizeBottomReply(rootId, newCommentId) {
        try {
            const bottomMod = resolveCommentsModule();
            if (!bottomMod) return;
            if (isBottomRootLoaded(bottomMod, rootId)) {
                if (typeof bottomMod.refreshRootThread === 'function') {
                    bottomMod.refreshRootThread(rootId, {
                        revealCommentId: newCommentId || null
                    }).catch(function () {});
                }
            } else {
                if (typeof bottomMod.refreshFromPageZero === 'function') {
                    bottomMod.refreshFromPageZero().catch(function () {});
                }
            }
        } catch (_) {}
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
     * Determines whether the current user is a guest (unauthenticated).
     *
     * @param {Document} [doc]
     * @returns {boolean}
     */
    function isGuestUser(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return true;
        }

        const drawer = d.getElementById ? d.getElementById('novelBlockDiscussionDrawer') : null;
        if (drawer && typeof drawer.getAttribute === 'function') {
            const authAttr = drawer.getAttribute('data-authenticated');
            if (authAttr === 'true') {
                return false;
            }
            if (authAttr === 'false') {
                return true;
            }
        }

        // If anonymous prompt is visible in DOM
        if (d.querySelector && d.querySelector('.novel-block-discussion-anonymous-prompt')) {
            return true;
        }

        // If authenticated root composer exists in DOM
        if (d.getElementById && d.getElementById('novelBlockDiscussionComposer')) {
            return false;
        }

        return false;
    }

    /**
     * Resolves the current chapter slug or path.
     *
     * @param {Document} [doc]
     * @returns {string}
     */
    function resolveChapterPath(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (d) {
            const drawer = d.getElementById ? d.getElementById('novelBlockDiscussionDrawer') : null;
            if (drawer && typeof drawer.getAttribute === 'function') {
                const slug = (drawer.getAttribute('data-chapter-slug') || '').trim();
                if (slug) {
                    return '/novel/chapters/' + encodeURIComponent(slug);
                }
            }
        }

        if (typeof window !== 'undefined' && window.location && typeof window.location.pathname === 'string') {
            const path = window.location.pathname.trim();
            if (path.startsWith('/novel/chapters/')) {
                return path;
            }
        }

        return '/novel/chapters';
    }

    /**
     * Builds the semantic guest return URL.
     *
     * @param {string} chapterPath
     * @param {string} blockKey
     * @param {string} rootId
     * @param {string} commentId
     * @returns {string}
     */
    function buildGuestReturnUrl(chapterPath, blockKey, rootId, commentId) {
        const safePath = (chapterPath && chapterPath.startsWith('/')) ? chapterPath : '/novel/chapters';
        const params = new URLSearchParams();
        if (blockKey) params.set('discussionBlock', blockKey);
        if (rootId) params.set('threadId', rootId);
        if (commentId) params.set('replyTo', commentId);
        params.set('intent', 'reply');

        return safePath + '?' + params.toString();
    }

    /**
     * Updates the status message on a reply composer.
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
     * Closes the guest auth modal.
     */
    function closeAuthModal() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;

        const modal = doc.getElementById ? doc.getElementById(AUTH_MODAL_ID) : doc.querySelector('.' + AUTH_MODAL_ID);
        if (modal) {
            modal.hidden = true;
            if (typeof modal.setAttribute === 'function') {
                modal.setAttribute('aria-hidden', 'true');
            }
            if (modal.classList && typeof modal.classList.remove === 'function') {
                modal.classList.remove('is-open');
            }
        }

        if (priorFocusedReplyBtn && typeof priorFocusedReplyBtn.focus === 'function') {
            try {
                priorFocusedReplyBtn.focus();
            } catch (_) {}
        }
        priorFocusedReplyBtn = null;
    }

    /**
     * Handles guest click on Reply action: displays auth modal with semantic returnTo target.
     *
     * @param {{commentId: string, rootId: string, authorName: string, chapterId: string, blockKey: string, replyBtn: Element|null}} targetInfo
     */
    function handleGuestReply(targetInfo) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;

        priorFocusedReplyBtn = targetInfo.replyBtn;

        const chapterPath = resolveChapterPath(doc);
        const returnTarget = buildGuestReturnUrl(
            chapterPath,
            targetInfo.blockKey,
            targetInfo.rootId,
            targetInfo.commentId
        );

        let modal = doc.getElementById ? doc.getElementById(AUTH_MODAL_ID) : null;
        if (!modal) {
            modal = createAuthModal(doc);
        }

        const loginLink = doc.getElementById ? doc.getElementById(AUTH_LOGIN_LINK_ID) : modal.querySelector('.' + AUTH_LOGIN_LINK_ID);
        const registerLink = doc.getElementById ? doc.getElementById(AUTH_REGISTER_LINK_ID) : modal.querySelector('.' + AUTH_REGISTER_LINK_ID);

        if (loginLink) {
            loginLink.href = '/login?returnTo=' + encodeURIComponent(returnTarget);
            loginLink.setAttribute('href', '/login?returnTo=' + encodeURIComponent(returnTarget));
        }
        if (registerLink) {
            registerLink.href = '/register?returnTo=' + encodeURIComponent(returnTarget);
            registerLink.setAttribute('href', '/register?returnTo=' + encodeURIComponent(returnTarget));
        }

        modal.hidden = false;
        if (typeof modal.setAttribute === 'function') {
            modal.setAttribute('aria-hidden', 'false');
        }
        if (modal.classList && typeof modal.classList.add === 'function') {
            modal.classList.add('is-open');
        }

        const cancelBtn = doc.getElementById ? doc.getElementById(AUTH_CANCEL_BTN_ID) : modal.querySelector('.' + AUTH_CANCEL_BTN_ID);
        if (cancelBtn && typeof cancelBtn.focus === 'function') {
            try {
                cancelBtn.focus();
            } catch (_) {}
        }
    }

    /**
     * Creates dynamic guest auth modal if not pre-rendered in DOM.
     *
     * @param {Document} doc
     * @returns {Element}
     */
    function createAuthModal(doc) {
        const modal = doc.createElement('div');
        modal.id = AUTH_MODAL_ID;
        modal.className = 'novel-comment-auth-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-labelledby', 'novelCommentAuthModalTitle');
        modal.hidden = true;

        const backdrop = doc.createElement('div');
        backdrop.className = 'novel-comment-auth-modal-backdrop';
        backdrop.setAttribute('data-action', 'close-auth-modal');

        const card = doc.createElement('div');
        card.className = 'novel-comment-auth-modal-card';

        const title = doc.createElement('h3');
        title.id = 'novelCommentAuthModalTitle';
        title.className = 'novel-comment-auth-modal-title';
        title.textContent = 'Đăng nhập để trả lời';

        const text = doc.createElement('p');
        text.className = 'novel-comment-auth-modal-message';
        text.textContent = 'Vui lòng đăng nhập hoặc tạo tài khoản để phản hồi bình luận.';

        const actions = doc.createElement('div');
        actions.className = 'novel-comment-auth-modal-actions';

        const loginLink = doc.createElement('a');
        loginLink.id = AUTH_LOGIN_LINK_ID;
        loginLink.className = 'novel-comment-auth-modal-btn novel-comment-auth-modal-btn--primary';
        loginLink.textContent = 'Đăng nhập';

        const registerLink = doc.createElement('a');
        registerLink.id = AUTH_REGISTER_LINK_ID;
        registerLink.className = 'novel-comment-auth-modal-btn novel-comment-auth-modal-btn--secondary';
        registerLink.textContent = 'Tạo tài khoản';

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.id = AUTH_CANCEL_BTN_ID;
        cancelBtn.className = 'novel-comment-auth-modal-btn novel-comment-auth-modal-btn--cancel';
        cancelBtn.textContent = 'Hủy';

        actions.appendChild(loginLink);
        actions.appendChild(registerLink);
        actions.appendChild(cancelBtn);

        card.appendChild(title);
        card.appendChild(text);
        card.appendChild(actions);

        modal.appendChild(backdrop);
        modal.appendChild(card);

        const drawer = doc.getElementById ? doc.getElementById('novelBlockDiscussionDrawer') : null;
        if (drawer) {
            drawer.appendChild(modal);
        } else if (doc.body) {
            doc.body.appendChild(modal);
        }

        return modal;
    }

    /**
     * Closes and removes any active reply composer form from DOM.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeReplyComposer(restoreFocus) {
        currentMutationToken++;
        if (activeComposerEl && activeComposerEl.parentNode) {
            try {
                activeComposerEl.parentNode.removeChild(activeComposerEl);
            } catch (_) {}
        }
        activeComposerEl = null;

        const prevTarget = activeTarget;
        activeTarget = null;
        isSubmitting = false;

        if (restoreFocus && prevTarget && prevTarget.replyBtn && typeof prevTarget.replyBtn.focus === 'function') {
            try {
                prevTarget.replyBtn.focus();
            } catch (_) {}
        }
    }

    /**
     * Opens or switches reply composer for an exact comment target.
     *
     * @param {{commentId: string, rootId: string, authorName: string, chapterId: string, blockKey: string, commentEl: Element, replyBtn: Element|null}} targetInfo
     */
    function openReplyComposer(targetInfo) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || !targetInfo || !targetInfo.commentEl) {
            return;
        }

        // Close any active edit composer before establishing reply composer
        const editMod = resolveEditComposerModule();
        if (editMod && typeof editMod.closeEditComposer === 'function') {
            try {
                editMod.closeEditComposer(false);
            } catch (_) {}
        }

        // Close any currently active composer (clearing previous target's draft)
        closeReplyComposer(false);
        currentMutationToken++;

        activeTarget = {
            commentId: targetInfo.commentId,
            rootId: targetInfo.rootId,
            authorName: targetInfo.authorName || 'Người dùng',
            chapterId: targetInfo.chapterId,
            blockKey: targetInfo.blockKey,
            replyBtn: targetInfo.replyBtn
        };

        const form = doc.createElement('form');
        form.id = REPLY_COMPOSER_FORM_ID;
        form.className = REPLY_COMPOSER_CLASS;
        form.setAttribute('novalidate', '');

        // 1. Context header ("Đang trả lời <author>")
        const contextDiv = doc.createElement('div');
        contextDiv.className = REPLY_CONTEXT_CLASS;
        contextDiv.textContent = 'Đang trả lời ';

        const authorSpan = doc.createElement('strong');
        authorSpan.className = 'novel-reply-composer-target-name';
        authorSpan.textContent = activeTarget.authorName;
        contextDiv.appendChild(authorSpan);

        // 2. Textarea
        const textarea = doc.createElement('textarea');
        textarea.className = REPLY_INPUT_CLASS;
        textarea.setAttribute('placeholder', 'Viết phản hồi...');
        textarea.setAttribute('rows', '2');
        textarea.setAttribute('aria-label', 'Nội dung phản hồi');

        // 3. Status container
        const statusDiv = doc.createElement('div');
        statusDiv.className = REPLY_STATUS_CLASS;
        statusDiv.setAttribute('role', 'status');
        statusDiv.setAttribute('aria-live', 'polite');

        // 4. Action buttons
        const actionsDiv = doc.createElement('div');
        actionsDiv.className = 'novel-reply-composer-actions';

        const submitBtn = doc.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = REPLY_SUBMIT_CLASS;
        submitBtn.textContent = 'Trả lời';

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = REPLY_CANCEL_CLASS;
        cancelBtn.textContent = 'Hủy';

        actionsDiv.appendChild(cancelBtn);
        actionsDiv.appendChild(submitBtn);

        form.appendChild(contextDiv);
        form.appendChild(textarea);
        form.appendChild(statusDiv);
        form.appendChild(actionsDiv);

        // Append composer inside or immediately after target comment
        targetInfo.commentEl.appendChild(form);
        activeComposerEl = form;

        // Focus textarea
        if (typeof textarea.focus === 'function') {
            try {
                textarea.focus();
            } catch (_) {}
        }
    }

    /**
     * Checks dynamically whether mutation ownership is still valid.
     *
     * @param {number} mutationToken
     * @param {{chapterId: string, parentCommentId: string, blockKey: string}} snapshot
     * @returns {boolean}
     */
    function isMutationContextCurrent(mutationToken, snapshot) {
        if (mutationToken !== currentMutationToken) {
            return false;
        }
        if (!activeTarget) {
            return false;
        }
        return activeTarget.chapterId === snapshot.chapterId &&
            activeTarget.commentId === snapshot.parentCommentId &&
            activeTarget.blockKey === snapshot.blockKey;
    }

    /**
     * Handles reply submission.
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

        if (!activeTarget || !activeComposerEl) {
            return;
        }

        const textarea = activeComposerEl.querySelector('.' + REPLY_INPUT_CLASS);
        const statusEl = activeComposerEl.querySelector('.' + REPLY_STATUS_CLASS);
        const submitBtn = activeComposerEl.querySelector('.' + REPLY_SUBMIT_CLASS);
        const cancelBtn = activeComposerEl.querySelector('.' + REPLY_CANCEL_CLASS);

        if (!textarea) {
            return;
        }

        const rawBody = (textarea.value || '').trim();
        if (!rawBody) {
            setStatus(statusEl, 'Vui lòng nhập nội dung phản hồi.', 'error');
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
            chapterId: activeTarget.chapterId,
            parentCommentId: activeTarget.commentId,
            rootId: activeTarget.rootId,
            blockKey: activeTarget.blockKey
        };

        textarea.disabled = true;
        if (submitBtn) submitBtn.disabled = true;
        if (cancelBtn) cancelBtn.disabled = true;
        setStatus(statusEl, 'Đang gửi...', 'info');

        const fetchImpl = injectedFetch || (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchImpl) {
            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            setStatus(statusEl, 'Không thể gửi phản hồi. Trình duyệt không hỗ trợ fetch.', 'error');
            return;
        }

        const commentMutations = resolveCommentMutations();
        if (!commentMutations || typeof commentMutations.createReply !== 'function') {
            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            setStatus(statusEl, 'Không thể gửi phản hồi. Trình duyệt không hỗ trợ mutation.', 'error');
            return;
        }

        try {
            const res = await commentMutations.createReply({
                chapterId: snapshot.chapterId,
                parentCommentId: snapshot.parentCommentId,
                body: rawBody
            }, {
                fetch: fetchImpl,
                document: currentDoc,
                csrf: csrf
            });

            if (isMutationContextCurrent(mutationToken, snapshot)) {
                isSubmitting = false;
                closeReplyComposer(false);

                // Refresh authoritative drawer (preserves open drawer and updates replies)
                const drawer = resolveDrawerModule();
                if (drawer && typeof drawer.refreshActiveDiscussion === 'function') {
                    try {
                        drawer.refreshActiveDiscussion().catch(function () {});
                    } catch (_) {}
                }

                // Refresh block indicators so paragraph badge updates with new commentCount
                const indicators = resolveIndicatorsModule();
                if (indicators && typeof indicators.refreshChapterIndicators === 'function') {
                    try {
                        indicators.refreshChapterIndicators().catch(function () {});
                    } catch (_) {}
                }

                // Secondary Bottom feed synchronization:
                // If root is loaded in Bottom, refreshRootThread(rootId, { revealCommentId });
                // If root is NOT loaded in Bottom, refreshFromPageZero() to update active comment count.
                const newCommentId = (res && res.commentId) || (res && res.data && res.data.commentId) || null;
                synchronizeBottomReply(snapshot.rootId, newCommentId);
            }
        } catch (err) {
            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }
            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            const status = err && typeof err.status === 'number' ? err.status : 0;
            if (status === 401 || status === 403) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc không có quyền. Vui lòng đăng nhập lại.', 'error');
            } else if (status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else if (status > 0) {
                setStatus(statusEl, 'Không thể gửi phản hồi. Vui lòng thử lại.', 'error');
            } else {
                setStatus(statusEl, 'Lỗi kết nối mạng. Vui lòng thử lại.', 'error');
            }
        }
    }

    /**
     * Resolves target comment info from a clicked reply button or element.
     *
     * @param {Element} replyBtn
     * @returns {Object|null}
     */
    function resolveTargetFromButton(replyBtn) {
        if (!replyBtn) return null;

        const commentId = replyBtn.getAttribute('data-comment-id');
        if (!commentId) return null;

        let rootId = replyBtn.getAttribute('data-root-id');
        let authorName = replyBtn.getAttribute('data-author-name') || '';

        let commentEl = null;
        if (typeof replyBtn.closest === 'function') {
            commentEl = replyBtn.closest('.novel-comment');
        } else {
            let cur = replyBtn.parentElement;
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

        if (!authorName && commentEl.querySelector) {
            const authorEl = commentEl.querySelector('.novel-comment-author');
            if (authorEl && authorEl.textContent) {
                authorName = authorEl.textContent.trim();
            }
        }

        const drawer = resolveDrawerModule();
        const activeCtx = drawer && typeof drawer.getActiveContext === 'function' ? drawer.getActiveContext() : null;
        const chapterId = activeCtx ? activeCtx.chapterId : '';
        const blockKey = activeCtx ? activeCtx.blockKey : '';

        return {
            commentId: commentId,
            rootId: rootId,
            authorName: authorName || 'Người dùng',
            chapterId: chapterId,
            blockKey: blockKey,
            commentEl: commentEl,
            replyBtn: replyBtn
        };
    }

    /**
     * Checks whether an element belongs inside the Block Discussion Drawer DOM surface (#novelBlockDiscussionDrawer).
     *
     * @param {Element} el
     * @returns {boolean}
     */
    function isInsideDrawer(el) {
        if (!el) return false;
        if (typeof el.closest === 'function') {
            return el.closest('#novelBlockDiscussionDrawer') !== null;
        }
        let cur = el;
        while (cur) {
            if (cur.id === 'novelBlockDiscussionDrawer' ||
                (typeof cur.getAttribute === 'function' && cur.getAttribute('id') === 'novelBlockDiscussionDrawer')) {
                return true;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    /**
     * Handles reply button click event.
     * Only processes clicks from inside #novelBlockDiscussionDrawer; safely ignores outside surfaces.
     *
     * @param {Event|Element} e
     */
    function handleReplyButtonClick(e) {
        if (!e) return;
        const target = e.target || e;
        if (!target) return;

        let replyBtn = null;
        if (typeof target.closest === 'function') {
            replyBtn = target.closest('.novel-comment-reply-btn') || target.closest('button[data-action="reply"]');
        } else if (target.classList && target.classList.contains('novel-comment-reply-btn')) {
            replyBtn = target;
        }

        if (!replyBtn || !isInsideDrawer(replyBtn)) return;

        if (typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const targetInfo = resolveTargetFromButton(replyBtn);
        if (!targetInfo) return;

        if (isGuestUser()) {
            handleGuestReply(targetInfo);
        } else {
            openReplyComposer(targetInfo);
        }
    }

    /**
     * Handles A3 restore resume request event.
     *
     * @param {Event} e
     */
    function handleReplyResumeRequested(e) {
        const detail = (e && e.detail) ? e.detail : {};
        const commentId = (typeof detail.commentId === 'string') ? detail.commentId.trim() : '';
        if (!commentId) return;

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;

        const contentEl = doc.getElementById ? doc.getElementById('novelBlockDiscussionContent') : doc.querySelector('.novel-block-discussion-content');
        if (!contentEl || typeof contentEl.querySelectorAll !== 'function') return;

        const comments = contentEl.querySelectorAll('.novel-comment');
        let matchedComment = null;

        for (let i = 0; i < comments.length; i++) {
            const c = comments[i];
            const cId = (typeof c.getAttribute === 'function' ? c.getAttribute('data-comment-id') : null) ||
                (typeof c.getAttribute === 'function' ? c.getAttribute('data-reply-id') : null);
            if (cId === commentId) {
                matchedComment = c;
                break;
            }
        }

        if (!matchedComment) {
            // Missing target: do NOT silently retarget root or another comment
            return;
        }

        const isTombstone = matchedComment.classList && (
            matchedComment.classList.contains('is-tombstone') ||
            (matchedComment.querySelector && matchedComment.querySelector('.novel-comment-body--tombstone'))
        );
        if (isTombstone) {
            // Tombstoned target: do NOT attach reply composer
            return;
        }

        let replyBtn = null;
        if (matchedComment.querySelector) {
            replyBtn = matchedComment.querySelector('.novel-comment-reply-btn');
        }

        let authorName = 'Người dùng';
        if (matchedComment.querySelector) {
            const authorEl = matchedComment.querySelector('.novel-comment-author');
            if (authorEl && authorEl.textContent) {
                authorName = authorEl.textContent.trim();
            }
        }

        const drawer = resolveDrawerModule();
        const activeCtx = drawer && typeof drawer.getActiveContext === 'function' ? drawer.getActiveContext() : null;
        const chapterId = (activeCtx && activeCtx.chapterId) ? activeCtx.chapterId : (detail.chapterId || '');
        const blockKey = (activeCtx && activeCtx.blockKey) ? activeCtx.blockKey : (detail.blockKey || '');
        const rootId = detail.threadId || commentId;

        const targetInfo = {
            commentId: commentId,
            rootId: rootId,
            authorName: authorName,
            chapterId: chapterId,
            blockKey: blockKey,
            commentEl: matchedComment,
            replyBtn: replyBtn
        };

        if (isGuestUser()) {
            handleGuestReply(targetInfo);
        } else {
            openReplyComposer(targetInfo);
        }
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

        // 1. Reply button click & Modal click
        doc.addEventListener('click', function (e) {
            const target = e.target;
            if (!target) return;

            // Reply button
            let replyBtn = null;
            if (typeof target.closest === 'function') {
                replyBtn = target.closest('.novel-comment-reply-btn') || target.closest('button[data-action="reply"]');
            } else if (target.classList && target.classList.contains('novel-comment-reply-btn')) {
                replyBtn = target;
            }
            if (replyBtn) {
                if (!isInsideDrawer(replyBtn)) {
                    return;
                }
                handleReplyButtonClick(e);
                return;
            }

            // Cancel reply composer
            if (typeof target.closest === 'function' && target.closest('.' + REPLY_CANCEL_CLASS)) {
                if (typeof e.preventDefault === 'function') e.preventDefault();
                closeReplyComposer(true);
                return;
            }

            // Close auth modal (cancel button or backdrop)
            if (typeof target.closest === 'function' && (target.closest('#' + AUTH_CANCEL_BTN_ID) || target.closest('[data-action="close-auth-modal"]'))) {
                if (typeof e.preventDefault === 'function') e.preventDefault();
                closeAuthModal();
                return;
            }
        });

        // 2. Submit reply composer form
        doc.addEventListener('submit', function (e) {
            const target = e.target;
            if (target && target.classList && target.classList.contains(REPLY_COMPOSER_CLASS)) {
                handleSubmit(e);
            }
        });

        // 3. A3 post-auth resume event
        doc.addEventListener(EVENT_REPLY_RESUME_REQUESTED, handleReplyResumeRequested);

        // 4. Chapter transition: clear state and pending UI
        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            currentMutationToken++;
            closeReplyComposer(false);
            closeAuthModal();
        });

        // 5. Drawer closed: clear composer and auth modal
        doc.addEventListener(EVENT_DISCUSSION_CLOSED, function () {
            currentMutationToken++;
            closeReplyComposer(false);
            closeAuthModal();
        });

        // 6. Block discussion requested: new block requested
        doc.addEventListener(EVENT_DISCUSSION_REQUESTED, function () {
            currentMutationToken++;
            closeReplyComposer(false);
            closeAuthModal();
        });

        // 7. Keyboard Escape closes auth modal if open
        doc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                const modal = doc.getElementById ? doc.getElementById(AUTH_MODAL_ID) : null;
                if (modal && !modal.hidden) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeAuthModal();
                }
            }
        });
    }

    /**
     * Initializes the Novel Block Discussion Reply Composer module.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderBlockDiscussionReplyComposer(targetDoc, options) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return null;
        currentDoc = doc;

        if (options && options.fetchFn) {
            injectedFetch = options.fetchFn;
        }
        if (options && options.drawerModule) {
            injectedDrawer = options.drawerModule;
        }
        if (options && options.indicatorsModule) {
            injectedIndicators = options.indicatorsModule;
        }
        if (options && options.editComposerModule) {
            injectedEditComposer = options.editComposerModule;
        }
        if (options && options.commentMutations) {
            injectedCommentMutations = options.commentMutations;
        }
        if (options && (options.commentsModule || options.bottomCommentsModule)) {
            injectedCommentsModule = options.commentsModule || options.bottomCommentsModule;
        }

        bindEvents(doc);

        return {
            openReplyComposer,
            closeReplyComposer,
            closeAuthModal,
            handleSubmit
        };
    }

    /**
     * Resets module state (for test teardown).
     */
    function resetReplyComposerState() {
        closeReplyComposer(false);
        closeAuthModal();
        currentDoc = null;
        boundDoc = null;
        injectedFetch = null;
        injectedDrawer = null;
        injectedIndicators = null;
        injectedEditComposer = null;
        injectedCommentMutations = null;
        injectedCommentsModule = null;
        isSubmitting = false;
        currentMutationToken++;
        activeTarget = null;
        activeComposerEl = null;
        priorFocusedReplyBtn = null;
    }

    // Auto-init on browser DOMContentLoaded
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderBlockDiscussionReplyComposer(document);
            });
        } else {
            initReaderBlockDiscussionReplyComposer(document);
        }
    }

    return {
        REPLY_COMPOSER_CLASS,
        REPLY_COMPOSER_FORM_ID,
        REPLY_INPUT_CLASS,
        REPLY_STATUS_CLASS,
        REPLY_SUBMIT_CLASS,
        REPLY_CANCEL_CLASS,
        REPLY_CONTEXT_CLASS,
        AUTH_MODAL_ID,
        AUTH_LOGIN_LINK_ID,
        AUTH_REGISTER_LINK_ID,
        AUTH_CANCEL_BTN_ID,
        initReaderBlockDiscussionReplyComposer,
        resetReplyComposerState,
        openReplyComposer,
        closeReplyComposer,
        closeAuthModal,
        handleSubmit,
        handleReplyButtonClick,
        handleReplyResumeRequested,
        handleGuestReply,
        buildGuestReturnUrl,
        isGuestUser,
        getActiveReplyTarget: function () { return activeTarget; },
        getActiveComposerEl: function () { return activeComposerEl; },
        isSubmittingReply: function () { return isSubmitting; },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setDrawerModule: function (mod) { injectedDrawer = mod; },
        setCommentMutations: function (mod) { injectedCommentMutations = mod; },
        setCommentsModule: function (mod) { injectedCommentsModule = mod; },
        resolveCommentMutations: resolveCommentMutations,
        resolveCommentsModule: resolveCommentsModule
    };
});
