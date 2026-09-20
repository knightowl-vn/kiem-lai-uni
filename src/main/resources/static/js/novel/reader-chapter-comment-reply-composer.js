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
    const EVENT_FEED_RENDERED = 'kiemlai:chapter-comments-feed-rendered';
    const EVENT_FLUSH_DRAFTS = 'kiemlai:novel-comment-drafts-flush';
    const DEBOUNCE_DELAY_MS = 400;

    // Module-lifetime state: survives destroy/re-init across composer instances
    let globalEditGeneration = 0;
    const keyEditGenerations = new Map();
    const acceptedEditGenerations = new Map();

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

    let activeDraftChapterId = null;
    let activeDraftTargetCommentId = null;
    let draftDebounceTimer = null;

    let delegatedClickHandler = null;
    let chapterChangedHandler = null;
    let feedReplacingHandler = null;
    let feedRenderedHandler = null;
    let flushDraftsHandler = null;

    let injectedFetch = null;
    let injectedMutations = null;
    let injectedCommentsModule = null;
    let injectedAuthenticated = null;
    let injectedEditComposer = null;
    let injectedDeleteModule = null;
    let injectedIndicatorsModule = null;
    let injectedDrawerModule = null;
    let injectedDraftAdapter = null;
    let injectedDraftStore = null;

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
     * Resolves the edit composer module.
     *
     * @returns {Object|null}
     */
    function resolveEditComposerModule() {
        if (injectedEditComposer) {
            return injectedEditComposer;
        }
        if (typeof window !== 'undefined') {
            const edit = window.NovelReaderChapterCommentEditComposer ||
                (window.KiemLai && window.KiemLai.NovelReaderChapterCommentEditComposer);
            if (edit) return edit;
        }
        if (typeof globalThis !== 'undefined') {
            const edit = globalThis.NovelReaderChapterCommentEditComposer ||
                (globalThis.KiemLai && globalThis.KiemLai.NovelReaderChapterCommentEditComposer);
            if (edit) return edit;
        }
        if (typeof require === 'function') {
            try {
                return require('./reader-chapter-comment-edit-composer.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Checks whether a bottom edit composer is currently active.
     *
     * @param {Element} [sectionEl]
     * @returns {boolean}
     */
    function isEditComposerActive(sectionEl) {
        const editMod = resolveEditComposerModule();
        if (editMod) {
            try {
                if (typeof editMod.getActiveComposerEl === 'function') {
                    const el = editMod.getActiveComposerEl();
                    if (el) return true;
                }
                if (typeof editMod.getActiveEditTarget === 'function') {
                    const target = editMod.getActiveEditTarget();
                    if (target) return true;
                }
            } catch (_) {}
        }
        if (sectionEl && typeof sectionEl.querySelector === 'function') {
            try {
                const el = sectionEl.querySelector('.novel-chapter-comment-edit-composer');
                if (el) return true;
            } catch (_) {}
        }
        return false;
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
     * Resolves the Novel Comment Drafts adapter module.
     *
     * @returns {Object|null}
     */
    function resolveDraftAdapter() {
        if (injectedDraftAdapter) return injectedDraftAdapter;
        if (typeof window !== 'undefined') {
            if (window.NovelReaderCommentDrafts) return window.NovelReaderCommentDrafts;
            if (window.KiemLai && window.KiemLai.NovelReaderCommentDrafts) return window.KiemLai.NovelReaderCommentDrafts;
        }
        if (typeof globalThis !== 'undefined') {
            if (globalThis.NovelReaderCommentDrafts) return globalThis.NovelReaderCommentDrafts;
            if (globalThis.KiemLai && globalThis.KiemLai.NovelReaderCommentDrafts) return globalThis.KiemLai.NovelReaderCommentDrafts;
        }
        if (typeof require === 'function') {
            try {
                return require('./reader-comment-drafts.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Resolves the EphemeralDraftStore implementation.
     *
     * @returns {Object|null}
     */
    function resolveDraftStore() {
        if (injectedDraftStore) return injectedDraftStore;
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.resolveDraftStore === 'function') {
            const resolved = adapter.resolveDraftStore();
            if (resolved) return resolved;
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
     * Resolves the canonical Reply draft key.
     *
     * @param {string} chapterId
     * @param {string} targetCommentId
     * @returns {string|null}
     */
    function getReplyDraftKey(chapterId, targetCommentId) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getChapterReplyDraftKey === 'function') {
            return adapter.getChapterReplyDraftKey(chapterId, targetCommentId);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim() ||
            typeof targetCommentId !== 'string' || !targetCommentId.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':reply:' + encodeURIComponent(targetCommentId.trim());
    }

    /**
     * Resolves the canonical active inline marker key.
     *
     * @param {string} chapterId
     * @returns {string|null}
     */
    function getMarkerKey(chapterId) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getChapterActiveInlineMarkerKey === 'function') {
            return adapter.getChapterActiveInlineMarkerKey(chapterId);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':active-inline';
    }

    /**
     * Validates active inline marker for reply.
     * Removes corrupted JSON marker from storage safely.
     *
     * @param {string} rawMarker
     * @param {string} markerKey
     * @param {Object} [store]
     * @returns {{type: string, targetCommentId: string}|null}
     */
    function validateReplyMarker(rawMarker, markerKey, store) {
        if (!rawMarker || typeof rawMarker !== 'string') return null;
        let parsed;
        try {
            parsed = JSON.parse(rawMarker);
        } catch (_) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        if (parsed.type !== 'reply') {
            // Non-reply marker (e.g. edit) - safely ignore without removing
            return null;
        }
        if (typeof parsed.targetCommentId !== 'string' || !parsed.targetCommentId.trim()) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        return {
            type: 'reply',
            targetCommentId: parsed.targetCommentId.trim()
        };
    }

    /**
     * Removes the active marker only if it still matches the specified reply target.
     *
     * @param {string} chapterId
     * @param {string} targetCommentId
     */
    function removeMarkerIfMatching(chapterId, targetCommentId) {
        if (!chapterId || !targetCommentId) return;
        const store = resolveDraftStore();
        if (!store) return;
        const markerKey = getMarkerKey(chapterId);
        if (!markerKey) return;
        const raw = store.load(markerKey);
        if (!raw) return;
        try {
            const parsed = JSON.parse(raw);
            if (parsed && parsed.type === 'reply' && parsed.targetCommentId === targetCommentId) {
                store.remove(markerKey);
            }
        } catch (_) {
            store.remove(markerKey);
        }
    }

    /**
     * Cancels any pending debounced draft save timer.
     */
    function cancelDebounce() {
        if (draftDebounceTimer) {
            clearTimeout(draftDebounceTimer);
            draftDebounceTimer = null;
        }
    }

    /**
     * Synchronously flushes the currently mounted reply draft to storage.
     * Does not re-save if the text belongs to an already accepted generation.
     */
    function flushActiveDraft() {
        cancelDebounce();
        if (!activeComposerEl || !activeDraftChapterId || !activeDraftTargetCommentId) {
            return;
        }
        const input = activeComposerEl.querySelector('.novel-chapter-comment-reply-composer-input');
        if (!input) return;
        const store = resolveDraftStore();
        if (!store || typeof store.save !== 'function') return;
        const key = getReplyDraftKey(activeDraftChapterId, activeDraftTargetCommentId);
        if (!key) return;

        const currentEditGen = (key && keyEditGenerations.get(key)) || 0;
        const acceptedGen = (key && acceptedEditGenerations.get(key)) || 0;
        if (acceptedGen > 0 && currentEditGen <= acceptedGen) {
            if (typeof store.remove === 'function') {
                store.remove(key);
            }
            return;
        }

        store.save(key, input.value);
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
     * Always targets the canonical #novelChapterComments anchor.
     *
     * @param {Document} [doc]
     * @param {Element} [sectionEl]
     * @returns {string}
     */
    function resolveLoginReturnUrl(doc, sectionEl) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        let loc = null;
        if (d && d.defaultView && d.defaultView.location && d.defaultView.location.pathname) {
            loc = d.defaultView.location;
        } else if (typeof window !== 'undefined' && window.location && window.location.pathname) {
            loc = window.location;
        }

        if (loc && loc.pathname) {
            return loc.pathname + (loc.search || '') + '#' + SECTION_ID;
        }

        const sec = sectionEl || resolveSection(doc);
        if (sec) {
            const slug = (typeof sec.getAttribute === 'function' ? sec.getAttribute('data-chapter-slug') : null) ||
                (sec.dataset && sec.dataset.chapterSlug);
            if (slug) {
                return '/novel/chapters/' + encodeURIComponent(slug) + '#' + SECTION_ID;
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
     * Finds comment DOM element inside section by comment ID.
     * Safe walking/iteration without constructing unsafe dynamic CSS selectors.
     *
     * @param {Element} sectionEl
     * @param {string} targetCommentId
     * @returns {Element|null}
     */
    function findCommentElementByCommentId(sectionEl, targetCommentId) {
        if (!sectionEl || !targetCommentId) return null;
        const comments = (typeof sectionEl.querySelectorAll === 'function')
            ? sectionEl.querySelectorAll('.novel-comment')
            : [];
        for (let i = 0; i < comments.length; i++) {
            const c = comments[i];
            const cid = (typeof c.getAttribute === 'function' ? c.getAttribute('data-comment-id') : null) ||
                (c.dataset && c.dataset.commentId);
            if (cid && String(cid).trim() === String(targetCommentId).trim()) {
                return c;
            }
        }
        return null;
    }

    /**
     * Finds the live Reply button for a target comment element.
     *
     * @param {Element} commentEl
     * @param {string} targetCommentId
     * @returns {Element|null}
     */
    function findReplyButtonForComment(commentEl, targetCommentId) {
        if (!commentEl || !targetCommentId) return null;
        let buttons = [];
        if (typeof commentEl.querySelectorAll === 'function') {
            buttons = commentEl.querySelectorAll('.novel-comment-reply-btn');
            if (!buttons || buttons.length === 0) {
                buttons = commentEl.querySelectorAll('button[data-action="reply"]');
            }
            if (!buttons || buttons.length === 0) {
                buttons = commentEl.querySelectorAll('[data-action="reply"]');
            }
        }
        for (let i = 0; i < buttons.length; i++) {
            const btn = buttons[i];
            const cid = (typeof btn.getAttribute === 'function' ? btn.getAttribute('data-comment-id') : null) ||
                (btn.dataset && btn.dataset.commentId);
            if (cid && String(cid).trim() === String(targetCommentId).trim()) {
                return btn;
            }
        }
        return null;
    }

    /**
     * Derives root ID from the LIVE DOM hierarchy by walking up to enclosing thread element.
     *
     * @param {Element} commentEl
     * @returns {string|null}
     */
    function findEnclosingRootId(commentEl) {
        let cur = commentEl;
        while (cur) {
            if (cur.classList && typeof cur.classList.contains === 'function' &&
                cur.classList.contains('novel-block-discussion-thread')) {
                const rid = (typeof cur.getAttribute === 'function' ? cur.getAttribute('data-root-id') : null) ||
                    (cur.dataset && cur.dataset.rootId);
                if (rid && String(rid).trim()) {
                    return String(rid).trim();
                }
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return null;
    }

    /**
     * Closes the active inline composer and optionally restores focus to trigger button.
     *
     * @param {boolean} [restoreFocus=false]
     * @param {Object} [options]
     * @param {boolean} [options.discard=false] - If true, discards draft and removes matching marker. Default: false (PRESERVE).
     * @param {boolean} [options.skipFlush=false] - If true, skips flushing draft (e.g. on 201).
     */
    function closeActiveComposer(restoreFocus, options) {
        const discard = Boolean(options && options.discard);
        const skipFlush = Boolean(options && options.skipFlush);
        const targetCommentId = activeDraftTargetCommentId || activeCommentId;
        const chapterId = activeDraftChapterId || resolveChapterId(currentDoc, resolveSection(currentDoc));

        cancelDebounce();

        if (discard) {
            if (chapterId && targetCommentId) {
                const store = resolveDraftStore();
                if (store) {
                    const draftKey = getReplyDraftKey(chapterId, targetCommentId);
                    if (draftKey && typeof store.remove === 'function') {
                        store.remove(draftKey);
                    }
                }
                removeMarkerIfMatching(chapterId, targetCommentId);
            }
        } else if (!skipFlush) {
            flushActiveDraft();
        }

        currentMutationToken++;
        isSubmitting = false;
        if (activeComposerEl && activeComposerEl.parentNode) {
            activeComposerEl.parentNode.removeChild(activeComposerEl);
        }
        activeComposerEl = null;
        activeCommentId = null;
        activeRootId = null;
        activeDraftChapterId = null;
        activeDraftTargetCommentId = null;

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
        const editMod = resolveEditComposerModule();
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
     * @param {Object} [options]
     * @param {boolean} [options.isRestore=false]
     */
    function openInlineComposer(triggerBtn, commentContainer, doc, options) {
        const commentId = triggerBtn.getAttribute('data-comment-id');
        const authoritativeRootId = (options && typeof options.authoritativeRootId === 'string' && options.authoritativeRootId.trim())
            ? options.authoritativeRootId.trim()
            : null;
        const rootId = authoritativeRootId || triggerBtn.getAttribute('data-root-id');
        const authorName = triggerBtn.getAttribute('data-author-name') || '';
        const isRestore = Boolean(options && options.isRestore);

        if (activeComposerEl && activeCommentId === commentId) {
            closeActiveComposer(true, { discard: true });
            return;
        }

        closeAllActive();

        // Close any active actions menu
        const commentsMod = resolveCommentsModule();
        if (commentsMod && typeof commentsMod.closeActiveMenu === 'function') {
            commentsMod.closeActiveMenu(false);
        }

        const sectionEl = resolveSection(doc);
        const chapterId = resolveChapterId(doc, sectionEl);
        if (!chapterId || !commentId) return;

        activeCommentId = commentId;
        activeRootId = rootId;
        activeDraftChapterId = chapterId;
        activeDraftTargetCommentId = commentId;
        lastTriggerBtn = triggerBtn;

        // Persist active-inline marker under current chapter
        const store = resolveDraftStore();
        const markerKey = getMarkerKey(chapterId);
        if (store && markerKey) {
            store.save(markerKey, JSON.stringify({ type: 'reply', targetCommentId: commentId }));
        }

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
            closeActiveComposer(true, { discard: true });
        });

        headerDiv.appendChild(targetLabel);
        headerDiv.appendChild(closeBtn);

        // Input
        const input = doc.createElement('textarea');
        input.className = 'novel-chapter-comment-reply-composer-input';
        input.setAttribute('rows', '2');
        input.setAttribute('placeholder', authorName ? 'Viết phản hồi cho @' + authorName + '...' : 'Viết phản hồi...');
        input.setAttribute('aria-label', 'Nội dung phản hồi');

        // Restore saved draft into textarea if available
        const draftKey = getReplyDraftKey(chapterId, commentId);
        if (store && draftKey) {
            const savedDraft = store.load(draftKey);
            if (typeof savedDraft === 'string' && savedDraft.length > 0) {
                input.value = savedDraft;
            }
        }

        // Debounced typing listener
        input.addEventListener('input', function () {
            cancelDebounce();
            if (draftKey) {
                const nextGen = ++globalEditGeneration;
                keyEditGenerations.set(draftKey, nextGen);
                if (acceptedEditGenerations.has(draftKey)) {
                    acceptedEditGenerations.delete(draftKey);
                }
            }
            draftDebounceTimer = setTimeout(function () {
                draftDebounceTimer = null;
                flushActiveDraft();
            }, DEBOUNCE_DELAY_MS);
        });

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
            closeActiveComposer(true, { discard: true });
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

        if (!isRestore && typeof input.focus === 'function') {
            input.focus();
        }
    }

    /**
     * Restores an active inline reply composer from persisted active-inline marker.
     *
     * @param {Document} [doc]
     * @param {string} [chapterId]
     */
    function restoreActiveInlineComposer(doc, chapterId) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;
        const sectionEl = resolveSection(d);
        if (!sectionEl) return;

        if (!isUserAuthenticated(d, sectionEl)) return;

        // Do not mount duplicate if active reply composer is already mounted
        if (activeComposerEl && activeComposerEl.parentNode) return;

        // DO NOT restore Reply over an active Bottom Edit composer
        if (isEditComposerActive(sectionEl)) return;

        const currentChapter = resolveChapterId(d, sectionEl);
        if (chapterId && currentChapter && chapterId !== currentChapter) {
            return;
        }
        const effectiveChapter = chapterId || currentChapter;
        if (!effectiveChapter) return;

        const store = resolveDraftStore();
        if (!store) return;

        const markerKey = getMarkerKey(effectiveChapter);
        if (!markerKey) return;

        const rawMarker = store.load(markerKey);
        if (!rawMarker) return;

        const marker = validateReplyMarker(rawMarker, markerKey, store);
        if (!marker || marker.type !== 'reply') return;

        const targetCommentEl = findCommentElementByCommentId(sectionEl, marker.targetCommentId);
        if (!targetCommentEl) return;

        // Reject tombstone / deleted target
        if (targetCommentEl.classList && typeof targetCommentEl.classList.contains === 'function') {
            if (targetCommentEl.classList.contains('novel-comment--tombstone')) {
                return;
            }
        }
        if (!isInsideBottomComments(targetCommentEl)) return;

        const replyBtn = findReplyButtonForComment(targetCommentEl, marker.targetCommentId);
        if (!replyBtn) return;

        // Authoritative root ID derived strictly from enclosing LIVE thread DOM hierarchy
        const liveRootId = findEnclosingRootId(targetCommentEl);
        const rootId = liveRootId ||
            (typeof replyBtn.getAttribute === 'function' ? replyBtn.getAttribute('data-root-id') : null);
        if (!rootId) return;

        // Overwrite reply button data-root-id with live thread root
        if (typeof replyBtn.setAttribute === 'function') {
            replyBtn.setAttribute('data-root-id', rootId);
        }

        openInlineComposer(replyBtn, targetCommentEl, d, {
            isRestore: true,
            authoritativeRootId: rootId
        });
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

        const chapterId = activeDraftChapterId || resolveChapterId(doc, resolveSection(doc));
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

        const draftKey = getReplyDraftKey(chapterId, parentCommentId);
        const draftStore = resolveDraftStore();
        let submittedEditGeneration = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        if (draftKey && submittedEditGeneration === 0) {
            submittedEditGeneration = ++globalEditGeneration;
            keyEditGenerations.set(draftKey, submittedEditGeneration);
        }

        // Synchronously save rawBody before dispatching POST
        cancelDebounce();
        if (draftStore && draftKey) {
            draftStore.save(draftKey, rawVal);
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
            draftKey: draftKey,
            draftStore: draftStore,
            editGeneration: submittedEditGeneration,
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

            const isCurrent = isSubmissionCurrent(snapshot, doc);

            if (!isCurrent) {
                // Stale response handling
                if (res && (res.status === 201 || (res.ok && res.commentId))) {
                    const latestGeneration = (snapshot.draftKey && keyEditGenerations.get(snapshot.draftKey)) || 0;
                    if (latestGeneration <= snapshot.editGeneration) {
                        if (snapshot.draftKey) {
                            acceptedEditGenerations.set(snapshot.draftKey, snapshot.editGeneration);
                        }
                        if (snapshot.draftStore && snapshot.draftKey && typeof snapshot.draftStore.remove === 'function') {
                            snapshot.draftStore.remove(snapshot.draftKey);
                        }
                    }
                }
                return;
            }

            if (!res || (!res.ok && res.status !== 201)) {
                const errMsg = (res && res.status === 401)
                    ? 'Vui lòng đăng nhập để phản hồi.'
                    : 'Không thể gửi phản hồi. Vui lòng thử lại.';
                throw new Error(errMsg);
            }

            // Current 201 success:
            const latestGeneration = (snapshot.draftKey && keyEditGenerations.get(snapshot.draftKey)) || 0;
            if (latestGeneration <= snapshot.editGeneration) {
                if (snapshot.draftKey) {
                    acceptedEditGenerations.set(snapshot.draftKey, snapshot.editGeneration);
                }
                if (snapshot.draftStore && snapshot.draftKey && typeof snapshot.draftStore.remove === 'function') {
                    snapshot.draftStore.remove(snapshot.draftKey);
                }
            }
            removeMarkerIfMatching(snapshot.chapterId, snapshot.parentCommentId);

            // Close composer without re-flushing
            isSubmitting = false;
            closeActiveComposer(false, { discard: false, skipFlush: true });

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
     * Feed rendered event listener.
     *
     * @param {CustomEvent} [evt]
     */
    function handleFeedRendered(evt) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;
        const sectionEl = resolveSection(doc);
        const currentChapter = resolveChapterId(doc, sectionEl);

        const eventChapterId = (evt && evt.detail && typeof evt.detail.chapterId === 'string' && evt.detail.chapterId.trim())
            ? evt.detail.chapterId.trim()
            : null;

        // If event specifies a nonblank chapterId that does not match current Bottom chapter, ignore completely
        if (eventChapterId && currentChapter && eventChapterId !== currentChapter) {
            return;
        }

        const effectiveChapterId = eventChapterId || currentChapter;
        restoreActiveInlineComposer(doc, effectiveChapterId);
    }

    /**
     * Flush drafts event listener (pagehide bridge).
     */
    function handleFlushDrafts() {
        flushActiveDraft();
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
        if (opts.editComposerModule || opts.editComposer) injectedEditComposer = opts.editComposerModule || opts.editComposer;
        if (opts.deleteModule) injectedDeleteModule = opts.deleteModule;
        if (opts.indicatorsModule || opts.indicatorModule) injectedIndicatorsModule = opts.indicatorsModule || opts.indicatorModule;
        if (opts.drawerModule) injectedDrawerModule = opts.drawerModule;
        if (typeof opts.authenticated === 'boolean') injectedAuthenticated = opts.authenticated;
        if (opts.draftAdapter) injectedDraftAdapter = opts.draftAdapter;
        if (opts.draftStore) injectedDraftStore = opts.draftStore;

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
        feedRenderedHandler = function (e) {
            handleFeedRendered(e);
        };
        flushDraftsHandler = function () {
            handleFlushDrafts();
        };

        if (typeof currentDoc.addEventListener === 'function') {
            currentDoc.addEventListener('click', delegatedClickHandler);
            currentDoc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            currentDoc.addEventListener(EVENT_FEED_REPLACING, feedReplacingHandler);
            currentDoc.addEventListener(EVENT_FEED_RENDERED, feedRenderedHandler);
            currentDoc.addEventListener(EVENT_FLUSH_DRAFTS, flushDraftsHandler);
        }

        // Attempt to restore if feed is already rendered
        restoreActiveInlineComposer(d);
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
            if (feedRenderedHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_FEED_RENDERED, feedRenderedHandler);
            }
            if (flushDraftsHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_FLUSH_DRAFTS, flushDraftsHandler);
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
        injectedDraftAdapter = null;
        injectedDraftStore = null;
        delegatedClickHandler = null;
        chapterChangedHandler = null;
        feedReplacingHandler = null;
        feedRenderedHandler = null;
        flushDraftsHandler = null;
        lastTriggerBtn = null;
        activeDraftChapterId = null;
        activeDraftTargetCommentId = null;
        cancelDebounce();
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
        setDraftAdapterImplementation: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftStoreImplementation: function (store) { injectedDraftStore = store; },
        getKeyEditGeneration: function (key) { return keyEditGenerations.get(key) || 0; },
        getAcceptedEditGeneration: function (key) { return acceptedEditGenerations.get(key) || 0; },
        getActiveDraftChapterId: function () { return activeDraftChapterId; },
        getActiveDraftTargetCommentId: function () { return activeDraftTargetCommentId; },
        restoreActiveInlineComposer: restoreActiveInlineComposer,
        flushActiveDraft: flushActiveDraft,
        handleReplyButtonClick: onDelegatedClick,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        EVENT_FEED_REPLACING: EVENT_FEED_REPLACING,
        EVENT_FEED_RENDERED: EVENT_FEED_RENDERED,
        EVENT_FLUSH_DRAFTS: EVENT_FLUSH_DRAFTS
    };
});
