/**
 * KiemLai Universe — Bottom Chapter Comment Reply UI (MS-05E5H2F2B)
 *
 * Responsibilities:
 * - Provides single-instance inline reply composer across the bottom chapter comments section.
 * - Delegates click on "Phản hồi" buttons ([data-action="reply"], .novel-comment-reply-btn).
 * - Distinguishes unauthenticated guests (shows .novel-chapter-comment-reply-guest-prompt)
 *   and authenticated members (mounts .novel-chapter-comment-reply-composer).
 * - Targets either active root (parentCommentId = rootId) or nested reply (parentCommentId = reply.id).
 * - Renders reply target mention in composer header (@AuthorName).
 * - Enforces client-side validation (non-empty body).
 * - Enforces single-flight submission with monotonic token invalidation (currentMutationToken++).
 * - Delegates actual Reply creation transport to shared NovelReaderCommentMutations.createReply(...).
 * - On 201 Created: closes the composer and invokes commentsModule.refreshRootThread(rootId, { revealCommentId }).
 * - If refreshRootThread fails after 201 Created, sets fallback status on #novelChapterCommentsStatus
 *   ("Phản hồi đã được gửi, nhưng chưa thể tải lại thảo luận.") without re-POSTing.
 * - On POST failure: preserves textarea draft and displays error message in composer status.
 * - Responds to 'kiemlai:chapter-changed' and 'kiemlai:chapter-comments-feed-replacing' to close
 *   active composer / guest prompt and invalidate pending mutation tokens.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderChapterCommentReplyComposer = exports;
        root.NovelChapterCommentReplyComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderChapterCommentReplyComposer = exports;
        root.KiemLai.NovelChapterCommentReplyComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const SECTION_ID = 'novelChapterComments';
    const STATUS_ID = 'novelChapterCommentsStatus';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_FEED_REPLACING = 'kiemlai:chapter-comments-feed-replacing';

    // Module State
    let currentDoc = null;
    let boundSection = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeCommentId = null;
    let activeRootId = null;
    let activeComposerEl = null;
    let activeGuestPromptEl = null;
    let lastTriggerBtn = null;

    let delegatedClickHandler = null;
    let chapterChangedHandler = null;
    let feedReplacingHandler = null;

    let injectedFetch = null;
    let injectedMutations = null;
    let injectedCommentsModule = null;
    let injectedAuthenticated = null;
    let injectedEditComposer = null;
    let injectedDeleteModule = null;
    let injectedIndicatorsModule = null;
    let injectedDrawerModule = null;

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
        if (typeof require === 'function') {
            try {
                return require('./reader-comment-mutations.js');
            } catch (_) {}
        }
        return null;
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
     * Resolves the comment indicators module.
     *
     * @returns {Object|null}
     */
    function resolveIndicatorsModule() {
        if (injectedIndicatorsModule) {
            return injectedIndicatorsModule;
        }
        if (typeof window !== 'undefined') {
            const ind = window.NovelChapterCommentIndicators ||
                (window.KiemLai && window.KiemLai.NovelChapterCommentIndicators);
            if (ind) return ind;
        }
        if (typeof require === 'function') {
            try {
                return require('./chapter-comment-indicators.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Resolves the block discussion drawer module.
     *
     * @returns {Object|null}
     */
    function resolveDrawerModule() {
        if (injectedDrawerModule) {
            return injectedDrawerModule;
        }
        if (typeof window !== 'undefined') {
            const drawer = window.NovelReaderBlockDiscussionDrawer ||
                (window.KiemLai && window.KiemLai.NovelReaderBlockDiscussionDrawer);
            if (drawer) return drawer;
        }
        if (typeof require === 'function') {
            try {
                return require('./reader-block-discussion-drawer.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Resolves synchronization context for a root thread (canonical anchoring and block key).
     *
     * @param {string} rootId
     * @param {Object} [commentsMod]
     * @returns {{rootId: string, anchorStatus: string, blockKey: string|null, isCanonicalAnchored: boolean}|null}
     */
    function resolveRootSyncContext(rootId, commentsMod) {
        if (!rootId) return null;
        const mod = commentsMod || resolveCommentsModule();
        if (!mod || typeof mod.getState !== 'function') return null;
        try {
            const state = mod.getState();
            const items = (state && Array.isArray(state.items)) ? state.items : [];
            const strRootId = String(rootId).trim();
            const found = items.find(function (it) {
                return it && String(it.rootCommentId || it.id).trim() === strRootId;
            });
            if (!found) return null;
            const anchorStatus = (typeof found.anchorStatus === 'string') ? found.anchorStatus.trim() : '';
            const rawBlockKey = (typeof found.blockKey === 'string') ? found.blockKey.trim() : '';
            const isCanonicalAnchored = (anchorStatus === 'CURRENT' || anchorStatus === 'RELOCATED') && rawBlockKey.length > 0;
            return {
                rootId: strRootId,
                anchorStatus: anchorStatus,
                blockKey: rawBlockKey || null,
                isCanonicalAnchored: isCanonicalAnchored
            };
        } catch (_) {
            return null;
        }
    }

    /**
     * Synchronizes paragraph indicators and/or open block discussion drawer after mutation.
     * Best-effort: errors are silently caught.
     *
     * @param {string} chapterId
     * @param {{rootId: string, anchorStatus: string, blockKey: string|null, isCanonicalAnchored: boolean}|null} rootSyncContext
     * @param {boolean} isCountChanging
     * @param {Document} [doc]
     */
    function synchronizeSecondarySurfaces(chapterId, rootSyncContext, isCountChanging, doc) {
        if (!rootSyncContext || !rootSyncContext.isCanonicalAnchored || !chapterId) {
            return;
        }

        // 1. Synchronize Paragraph Comment Indicators (for count-changing mutations)
        if (isCountChanging) {
            try {
                const indMod = resolveIndicatorsModule();
                if (indMod && typeof indMod.refreshChapterIndicators === 'function') {
                    const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
                    const chapterBody = (d && typeof d.querySelector === 'function')
                        ? d.querySelector('.novel-reader-chapter-body')
                        : null;
                    indMod.refreshChapterIndicators(chapterBody).catch(function () {});
                }
            } catch (_) {}
        }

        // 2. Synchronize Block Discussion Drawer (if open on the SAME canonical block)
        try {
            const drawerMod = resolveDrawerModule();
            if (drawerMod && typeof drawerMod.isDrawerOpen === 'function' && drawerMod.isDrawerOpen()) {
                const activeCtx = (typeof drawerMod.getActiveContext === 'function')
                    ? drawerMod.getActiveContext()
                    : null;
                if (activeCtx &&
                    String(activeCtx.chapterId).trim() === String(chapterId).trim() &&
                    String(activeCtx.blockKey).trim() === String(rootSyncContext.blockKey).trim()) {
                    if (typeof drawerMod.refreshActiveDiscussion === 'function') {
                        drawerMod.refreshActiveDiscussion().catch(function () {});
                    }
                }
            }
        } catch (_) {}
    }

    /**
     * Resolves the comments section container.
     *
     * @param {Document} [doc]
     * @returns {Element|null}
     */
    function resolveSection(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.getElementById !== 'function') return null;
        return d.getElementById(SECTION_ID);
    }

    /**
     * Resolves current chapter ID from section or reader body.
     *
     * @param {Document} [doc]
     * @param {Element} [sectionEl]
     * @returns {string|null}
     */
    function resolveChapterId(doc, sectionEl) {
        const sec = sectionEl || resolveSection(doc);
        if (sec) {
            const attrId = (typeof sec.getAttribute === 'function' ? sec.getAttribute('data-chapter-id') : null) ||
                (sec.dataset && sec.dataset.chapterId);
            if (attrId && String(attrId).trim()) {
                return String(attrId).trim();
            }
        }
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (d && typeof d.querySelector === 'function') {
            const bodyEl = d.querySelector('.novel-reader-chapter-body[data-chapter-id]');
            if (bodyEl) {
                const bId = (typeof bodyEl.getAttribute === 'function' ? bodyEl.getAttribute('data-chapter-id') : null) ||
                    (bodyEl.dataset && bodyEl.dataset.chapterId);
                if (bId && String(bId).trim()) {
                    return String(bId).trim();
                }
            }
        }
        return null;
    }

    /**
     * Checks if current session is authenticated.
     *
     * @param {Document} [doc]
     * @param {Element} [sectionEl]
     * @returns {boolean}
     */
    function isUserAuthenticated(doc, sectionEl) {
        if (typeof injectedAuthenticated === 'boolean') {
            return injectedAuthenticated;
        }
        const sec = sectionEl || resolveSection(doc);
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
     * Resolves returnTo parameter for guest login link.
     *
     * @param {Document} [doc]
     * @param {Element} [sectionEl]
     * @returns {string}
     */
    function resolveLoginReturnUrl(doc, sectionEl) {
        if (typeof window !== 'undefined' && window.location && window.location.pathname) {
            return window.location.pathname + (window.location.search || '');
        }
        const sec = sectionEl || resolveSection(doc);
        if (sec) {
            const slug = (typeof sec.getAttribute === 'function' ? sec.getAttribute('data-chapter-slug') : null) ||
                (sec.dataset && sec.dataset.chapterSlug);
            if (slug) {
                return '/novel/chapters/' + encodeURIComponent(slug);
            }
        }
        return '';
    }

    /**
     * Checks whether an element belongs inside the Bottom Comments section (#novelChapterComments).
     *
     * @param {Element} el
     * @returns {boolean}
     */
    function isInsideBottomComments(el) {
        if (!el) return false;
        if (typeof el.closest === 'function') {
            return el.closest('#' + SECTION_ID) !== null;
        }
        let cur = el;
        while (cur) {
            if (cur.id === SECTION_ID ||
                (typeof cur.getAttribute === 'function' && cur.getAttribute('id') === SECTION_ID)) {
                return true;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    /**
     * Walks up parent nodes to find enclosing .novel-comment container.
     *
     * @param {Element} btn
     * @returns {Element|null}
     */
    function findCommentContainer(btn) {
        let cur = btn;
        while (cur) {
            if (cur.classList && typeof cur.classList.contains === 'function' && cur.classList.contains('novel-comment')) {
                return cur;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return null;
    }

    /**
     * Closes the active inline composer and optionally restores focus to trigger button.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeActiveComposer(restoreFocus) {
        currentMutationToken++;
        isSubmitting = false;
        if (activeComposerEl && activeComposerEl.parentNode) {
            activeComposerEl.parentNode.removeChild(activeComposerEl);
        }
        activeComposerEl = null;
        activeCommentId = null;
        activeRootId = null;

        if (restoreFocus && lastTriggerBtn && typeof lastTriggerBtn.focus === 'function') {
            lastTriggerBtn.focus();
        }
    }

    /**
     * Closes the active guest login prompt.
     */
    function closeActiveGuestPrompt() {
        if (activeGuestPromptEl && activeGuestPromptEl.parentNode) {
            activeGuestPromptEl.parentNode.removeChild(activeGuestPromptEl);
        }
        activeGuestPromptEl = null;
    }

    /**
     * Closes active edit composer and active delete confirmation if present.
     */
    function closeOtherBottomInteractions() {
        const editMod = injectedEditComposer || (typeof window !== 'undefined' && (
            window.NovelReaderChapterCommentEditComposer ||
            (window.KiemLai && window.KiemLai.NovelReaderChapterCommentEditComposer)
        )) || null;
        if (editMod && typeof editMod.closeEditComposer === 'function') {
            try {
                editMod.closeEditComposer(false);
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
     * Closes both composer and guest prompt, as well as active edit and delete UI.
     */
    function closeAllActive() {
        closeOtherBottomInteractions();
        closeActiveComposer(false);
        closeActiveGuestPrompt();
    }

    /**
     * Renders and opens guest prompt under target comment.
     *
     * @param {Element} triggerBtn
     * @param {Element} commentContainer
     * @param {Document} doc
     */
    function openGuestPrompt(triggerBtn, commentContainer, doc) {
        const commentId = triggerBtn.getAttribute('data-comment-id');

        if (activeGuestPromptEl && activeCommentId === commentId) {
            closeActiveGuestPrompt();
            activeCommentId = null;
            return;
        }

        closeAllActive();

        activeCommentId = commentId;
        lastTriggerBtn = triggerBtn;

        const promptDiv = doc.createElement('div');
        promptDiv.className = 'novel-chapter-comment-reply-guest-prompt';
        promptDiv.setAttribute('role', 'region');
        promptDiv.setAttribute('aria-label', 'Yêu cầu đăng nhập');

        const returnUrl = resolveLoginReturnUrl(doc, resolveSection(doc));
        const loginUrl = returnUrl ? '/login?returnTo=' + encodeURIComponent(returnUrl) : '/login';

        const link = doc.createElement('a');
        link.className = 'novel-chapter-comment-login-link';
        link.setAttribute('href', loginUrl);
        link.textContent = 'Đăng nhập';

        const suffix = doc.createElement('span');
        suffix.textContent = ' để phản hồi.';

        promptDiv.appendChild(link);
        promptDiv.appendChild(suffix);

        commentContainer.appendChild(promptDiv);
        activeGuestPromptEl = promptDiv;
    }

    /**
     * Opens inline reply composer under target comment.
     *
     * @param {Element} triggerBtn
     * @param {Element} commentContainer
     * @param {Document} doc
     */
    function openInlineComposer(triggerBtn, commentContainer, doc) {
        const commentId = triggerBtn.getAttribute('data-comment-id');
        const rootId = triggerBtn.getAttribute('data-root-id');
        const authorName = triggerBtn.getAttribute('data-author-name') || '';

        if (activeComposerEl && activeCommentId === commentId) {
            closeActiveComposer(true);
            return;
        }

        closeAllActive();

        // Close any active actions menu
        const commentsMod = resolveCommentsModule();
        if (commentsMod && typeof commentsMod.closeActiveMenu === 'function') {
            commentsMod.closeActiveMenu(false);
        }

        activeCommentId = commentId;
        activeRootId = rootId;
        lastTriggerBtn = triggerBtn;

        const form = doc.createElement('form');
        form.className = 'novel-chapter-comment-reply-composer';
        form.setAttribute('novalidate', '');

        // Header
        const headerDiv = doc.createElement('div');
        headerDiv.className = 'novel-chapter-comment-reply-composer-header';

        const targetLabel = doc.createElement('span');
        targetLabel.className = 'novel-chapter-comment-reply-target-label';
        targetLabel.textContent = authorName ? 'Phản hồi @' + authorName : 'Phản hồi';

        const closeBtn = doc.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'novel-chapter-comment-reply-close-btn';
        closeBtn.setAttribute('aria-label', 'Đóng khung phản hồi');
        closeBtn.textContent = '✕';
        closeBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            closeActiveComposer(true);
        });

        headerDiv.appendChild(targetLabel);
        headerDiv.appendChild(closeBtn);

        // Input
        const input = doc.createElement('textarea');
        input.className = 'novel-chapter-comment-reply-composer-input';
        input.setAttribute('rows', '2');
        input.setAttribute('placeholder', authorName ? 'Viết phản hồi cho @' + authorName + '...' : 'Viết phản hồi...');
        input.setAttribute('aria-label', 'Nội dung phản hồi');

        // Status
        const statusEl = doc.createElement('div');
        statusEl.className = 'novel-chapter-comment-reply-composer-status';
        statusEl.setAttribute('role', 'status');
        statusEl.setAttribute('aria-live', 'polite');

        // Actions
        const actionsDiv = doc.createElement('div');
        actionsDiv.className = 'novel-chapter-comment-reply-composer-actions';

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'novel-chapter-comment-reply-cancel-btn';
        cancelBtn.textContent = 'Hủy';
        cancelBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            closeActiveComposer(true);
        });

        const submitBtn = doc.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = 'novel-chapter-comment-reply-submit-btn';
        submitBtn.textContent = 'Gửi';

        actionsDiv.appendChild(cancelBtn);
        actionsDiv.appendChild(submitBtn);

        form.appendChild(headerDiv);
        form.appendChild(input);
        form.appendChild(statusEl);
        form.appendChild(actionsDiv);

        form.addEventListener('submit', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            handleReplySubmit(form, input, statusEl, submitBtn, cancelBtn, commentId, rootId, doc);
        });

        commentContainer.appendChild(form);
        activeComposerEl = form;

        if (typeof input.focus === 'function') {
            input.focus();
        }
    }

    /**
     * Verifies whether a pending submission snapshot remains authoritative and active.
     *
     * @param {Object} snapshot
     * @param {Document} [doc]
     * @returns {boolean}
     */
    function isSubmissionCurrent(snapshot, doc) {
        if (!snapshot || typeof snapshot.token !== 'number') {
            return false;
        }
        if (snapshot.token !== currentMutationToken) {
            return false;
        }
        if (!snapshot.chapterId || snapshot.chapterId !== resolveChapterId(doc)) {
            return false;
        }
        if (activeCommentId !== snapshot.parentCommentId || activeRootId !== snapshot.rootId) {
            return false;
        }
        return true;
    }

    /**
     * Handles reply submission lifecycle with single-flight and token race safety.
     *
     * @param {Element} form
     * @param {Element} input
     * @param {Element} statusEl
     * @param {Element} submitBtn
     * @param {Element} cancelBtn
     * @param {string} parentCommentId
     * @param {string} rootId
     * @param {Document} doc
     */
    async function handleReplySubmit(form, input, statusEl, submitBtn, cancelBtn, parentCommentId, rootId, doc) {
        if (isSubmitting) return;

        const rawVal = input ? input.value : '';
        const body = (typeof rawVal === 'string') ? rawVal.trim() : '';

        if (!body) {
            statusEl.textContent = 'Vui lòng nhập nội dung phản hồi.';
            statusEl.className = 'novel-chapter-comment-reply-composer-status is-error';
            if (input && typeof input.focus === 'function') input.focus();
            return;
        }

        const chapterId = resolveChapterId(doc, resolveSection(doc));
        if (!chapterId) {
            statusEl.textContent = 'Không tìm thấy thông tin chương.';
            statusEl.className = 'novel-chapter-comment-reply-composer-status is-error';
            return;
        }

        const mutationsClient = resolveMutations();
        if (!mutationsClient || typeof mutationsClient.createReply !== 'function') {
            statusEl.textContent = 'Hệ thống gửi phản hồi chưa sẵn sàng.';
            statusEl.className = 'novel-chapter-comment-reply-composer-status is-error';
            return;
        }

        isSubmitting = true;
        const token = ++currentMutationToken;
        const targetChapterId = chapterId;
        const commentsMod = resolveCommentsModule();
        const rootSyncContext = resolveRootSyncContext(rootId, commentsMod);
        const snapshot = {
            token: token,
            chapterId: targetChapterId,
            parentCommentId: parentCommentId,
            rootId: rootId,
            rootSyncContext: rootSyncContext
        };

        if (input) input.disabled = true;
        if (submitBtn) submitBtn.disabled = true;
        statusEl.textContent = 'Đang gửi phản hồi...';
        statusEl.className = 'novel-chapter-comment-reply-composer-status is-info';

        try {
            const res = await mutationsClient.createReply(
                { chapterId: targetChapterId, parentCommentId: parentCommentId, body: body },
                { document: doc, fetch: injectedFetch }
            );

            if (!isSubmissionCurrent(snapshot, doc)) {
                return;
            }

            if (!res || (!res.ok && res.status !== 201)) {
                const errMsg = (res && res.status === 401)
                    ? 'Vui lòng đăng nhập để phản hồi.'
                    : 'Không thể gửi phản hồi. Vui lòng thử lại.';
                throw new Error(errMsg);
            }

            // Close composer and clean up
            isSubmitting = false;
            closeActiveComposer(false);

            // Secondary cross-surface synchronization (best-effort)
            synchronizeSecondarySurfaces(targetChapterId, snapshot.rootSyncContext, true, doc);

            // Authoritative root refresh
            if (commentsMod && typeof commentsMod.refreshRootThread === 'function') {
                try {
                    await commentsMod.refreshRootThread(rootId, {
                        revealCommentId: res.commentId || null
                    });
                } catch (_) {
                    // Fallback notice on feed status element
                    const commentsStatusEl = doc.getElementById ? doc.getElementById(STATUS_ID) : null;
                    if (commentsStatusEl && targetChapterId === resolveChapterId(doc)) {
                        commentsStatusEl.textContent = 'Phản hồi đã được gửi, nhưng chưa thể tải lại thảo luận.';
                    }
                }
            }
        } catch (err) {
            if (!isSubmissionCurrent(snapshot, doc)) {
                return;
            }
            isSubmitting = false;
            if (input) input.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;
            statusEl.textContent = (err && err.message) ? err.message : 'Không thể gửi phản hồi. Vui lòng thử lại.';
            statusEl.className = 'novel-chapter-comment-reply-composer-status is-error';
            if (input && typeof input.focus === 'function') input.focus();
        }
    }

    /**
     * Delegated click handler on comments section.
     * Only processes clicks from inside #novelChapterComments; safely ignores outside surfaces (e.g. Drawer).
     *
     * @param {Event|Element} e
     */
    function onDelegatedClick(e) {
        let target = e ? (e.target || e.srcElement || e) : null;
        if (!target) return;

        let replyBtn = null;
        let cur = target;
        while (cur) {
            if (cur.getAttribute && (cur.getAttribute('data-action') === 'reply' || (cur.classList && cur.classList.contains('novel-comment-reply-btn')))) {
                replyBtn = cur;
                break;
            }
            cur = cur.parentElement || cur.parentNode;
        }

        if (!replyBtn || !isInsideBottomComments(replyBtn)) return;

        if (e && typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const commentContainer = findCommentContainer(replyBtn);
        if (!commentContainer) return;

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const sectionEl = resolveSection(doc);
        const authenticated = isUserAuthenticated(doc, sectionEl);

        if (!authenticated) {
            openGuestPrompt(replyBtn, commentContainer, doc);
        } else {
            openInlineComposer(replyBtn, commentContainer, doc);
        }
    }

    /**
     * Chapter changed event listener.
     */
    function handleChapterChanged() {
        currentMutationToken++;
        isSubmitting = false;
        closeAllActive();
    }

    /**
     * Feed replacing event listener.
     */
    function handleFeedReplacing() {
        currentMutationToken++;
        isSubmitting = false;
        closeAllActive();
    }

    /**
     * Initializes the reply composer module.
     *
     * @param {Document} doc
     * @param {Object} [options]
     */
    function init(doc, options) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        destroy();

        currentDoc = d;
        const opts = (options && typeof options === 'object') ? options : {};

        if (typeof opts.fetch === 'function') injectedFetch = opts.fetch;
        if (opts.mutations || opts.mutationsClient) injectedMutations = opts.mutations || opts.mutationsClient;
        if (opts.commentsModule) injectedCommentsModule = opts.commentsModule;
        if (opts.editComposerModule) injectedEditComposer = opts.editComposerModule;
        if (opts.deleteModule) injectedDeleteModule = opts.deleteModule;
        if (opts.indicatorsModule || opts.indicatorModule) injectedIndicatorsModule = opts.indicatorsModule || opts.indicatorModule;
        if (opts.drawerModule) injectedDrawerModule = opts.drawerModule;
        if (typeof opts.authenticated === 'boolean') injectedAuthenticated = opts.authenticated;

        const sectionEl = resolveSection(d);
        if (!sectionEl) return;
        boundSection = sectionEl;

        delegatedClickHandler = function (e) {
            onDelegatedClick(e);
        };
        chapterChangedHandler = function () {
            handleChapterChanged();
        };
        feedReplacingHandler = function () {
            handleFeedReplacing();
        };

        if (typeof currentDoc.addEventListener === 'function') {
            currentDoc.addEventListener('click', delegatedClickHandler);
            currentDoc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            currentDoc.addEventListener(EVENT_FEED_REPLACING, feedReplacingHandler);
        }
    }

    /**
     * Destroys module listeners and active DOM elements.
     */
    function destroy() {
        currentMutationToken++;
        isSubmitting = false;
        closeAllActive();

        if (currentDoc) {
            if (delegatedClickHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('click', delegatedClickHandler);
            }
            if (chapterChangedHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            }
            if (feedReplacingHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_FEED_REPLACING, feedReplacingHandler);
            }
        }

        currentDoc = null;
        boundSection = null;
        injectedFetch = null;
        injectedMutations = null;
        injectedCommentsModule = null;
        injectedAuthenticated = null;
        injectedEditComposer = null;
        injectedDeleteModule = null;
        injectedIndicatorsModule = null;
        injectedDrawerModule = null;
        delegatedClickHandler = null;
        chapterChangedHandler = null;
        feedReplacingHandler = null;
        lastTriggerBtn = null;
    }

    /**
     * Returns current state snapshot.
     *
     * @returns {Object}
     */
    function getState() {
        return {
            isSubmitting: isSubmitting,
            activeCommentId: activeCommentId,
            activeRootId: activeRootId,
            currentMutationToken: currentMutationToken
        };
    }

    // Auto-init in browser if DOM is ready
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                init(document);
            });
        } else {
            init(document);
        }
    }

    return {
        init: init,
        destroy: destroy,
        closeActiveComposer: closeActiveComposer,
        closeComposer: function () { closeActiveComposer(false); },
        getActiveComposer: function () { return activeComposerEl; },
        getActiveGuestPrompt: function () { return activeGuestPromptEl; },
        getState: getState,
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setMutationsImplementation: function (m) { injectedMutations = m; },
        setCommentsModuleImplementation: function (mod) { injectedCommentsModule = mod; },
        setEditComposerModule: function (mod) { injectedEditComposer = mod; },
        setDeleteModule: function (mod) { injectedDeleteModule = mod; },
        setIndicatorsModule: function (mod) { injectedIndicatorsModule = mod; },
        setDrawerModule: function (mod) { injectedDrawerModule = mod; },
        handleReplyButtonClick: onDelegatedClick
    };
});
