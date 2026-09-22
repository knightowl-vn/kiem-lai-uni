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
    const EVENT_FEED_RENDERED = 'kiemlai:chapter-comments-feed-rendered';
    const EVENT_FLUSH_DRAFTS = 'kiemlai:novel-comment-drafts-flush';
    const DEBOUNCE_DELAY_MS = 400;

    // Module-lifetime state: survives destroy/re-init across composer instances
    let globalEditGeneration = 0;
    const keyEditGenerations = new Map();
    const acceptedEditGenerations = new Map();

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeEditTarget = null;
    let activeComposerEl = null;
    let activeOriginalBody = '';
    let isDirty = false;
    let draftDebounceTimer = null;

    let injectedFetch = null;
    let injectedMutations = null;
    let injectedCommentsModule = null;
    let injectedReplyComposer = null;
    let injectedDeleteModule = null;
    let injectedDrawerModule = null;
    let injectedDraftAdapter = null;
    let injectedDraftStore = null;

    // Stable listener references
    let delegatedClickHandler = null;
    let submitHandler = null;
    let chapterChangedHandler = null;
    let feedReplacingHandler = null;
    let feedRenderedHandler = null;
    let flushDraftsHandler = null;
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
     * Synchronizes open block discussion drawer after edit mutation.
     * Best-effort: errors are silently caught.
     * Edit does NOT change counts, so indicators are never refreshed.
     *
     * @param {string} chapterId
     * @param {{rootId: string, anchorStatus: string, blockKey: string|null, isCanonicalAnchored: boolean}|null} rootSyncContext
     */
    function synchronizeSecondaryDrawer(chapterId, rootSyncContext) {
        if (!rootSyncContext || !rootSyncContext.isCanonicalAnchored || !chapterId) {
            return;
        }

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
     * Resolves the reply composer module.
     *
     * @returns {Object|null}
     */
    function resolveReplyComposerModule() {
        if (injectedReplyComposer) return injectedReplyComposer;
        if (typeof window !== 'undefined') {
            const reply = window.NovelReaderChapterCommentReplyComposer ||
                (window.KiemLai && window.KiemLai.NovelReaderChapterCommentReplyComposer);
            if (reply) return reply;
        }
        if (typeof globalThis !== 'undefined') {
            const reply = globalThis.NovelReaderChapterCommentReplyComposer ||
                (globalThis.KiemLai && globalThis.KiemLai.NovelReaderChapterCommentReplyComposer);
            if (reply) return reply;
        }
        if (typeof require === 'function') {
            try {
                return require('./reader-chapter-comment-reply-composer.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Checks whether a bottom reply composer is currently active.
     *
     * @param {Element} [sectionEl]
     * @returns {boolean}
     */
    function isReplyComposerActive(sectionEl) {
        const replyMod = resolveReplyComposerModule();
        if (replyMod) {
            try {
                if (typeof replyMod.getActiveComposer === 'function') {
                    const el = replyMod.getActiveComposer();
                    if (el) return true;
                }
                if (typeof replyMod.getState === 'function') {
                    const state = replyMod.getState();
                    if (state && state.activeCommentId) return true;
                }
            } catch (_) {}
        }
        if (sectionEl && typeof sectionEl.querySelector === 'function') {
            try {
                const el = sectionEl.querySelector('.novel-chapter-comment-reply-composer');
                if (el) return true;
            } catch (_) {}
        }
        return false;
    }

    /**
     * Resolves the comment delete module.
     *
     * @returns {Object|null}
     */
    function resolveDeleteModule() {
        if (injectedDeleteModule) return injectedDeleteModule;
        if (typeof window !== 'undefined') {
            const del = window.NovelReaderChapterCommentDelete ||
                (window.KiemLai && window.KiemLai.NovelReaderChapterCommentDelete);
            if (del) return del;
        }
        if (typeof globalThis !== 'undefined') {
            const del = globalThis.NovelReaderChapterCommentDelete ||
                (globalThis.KiemLai && globalThis.KiemLai.NovelReaderChapterCommentDelete);
            if (del) return del;
        }
        if (typeof require === 'function') {
            try {
                return require('./reader-chapter-comment-delete.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Checks whether a bottom delete confirmation is currently active.
     *
     * @param {Element} [sectionEl]
     * @returns {boolean}
     */
    function isDeleteActive(sectionEl) {
        const delMod = resolveDeleteModule();
        if (delMod) {
            try {
                if (typeof delMod.getActiveConfirmationEl === 'function' && delMod.getActiveConfirmationEl()) {
                    return true;
                }
                if (typeof delMod.getActiveDeleteTarget === 'function' && delMod.getActiveDeleteTarget()) {
                    return true;
                }
                if (typeof delMod.isDeletingComment === 'function' && delMod.isDeletingComment()) {
                    return true;
                }
            } catch (_) {}
        }
        if (sectionEl && typeof sectionEl.querySelector === 'function') {
            try {
                const el = sectionEl.querySelector('.novel-chapter-comment-delete-confirmation');
                if (el) return true;
            } catch (_) {}
        }
        return false;
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
     * Resolves the canonical Edit draft key.
     *
     * @param {string} chapterId
     * @param {string} commentId
     * @returns {string|null}
     */
    function getEditDraftKey(chapterId, commentId) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getChapterEditDraftKey === 'function') {
            return adapter.getChapterEditDraftKey(chapterId, commentId);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim() ||
            typeof commentId !== 'string' || !commentId.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':edit:' + encodeURIComponent(commentId.trim());
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
     * Validates active inline marker for edit.
     * Removes corrupted JSON marker from storage safely.
     * Non-edit markers (e.g. reply) are safely ignored without removing.
     *
     * @param {string} rawMarker
     * @param {string} markerKey
     * @param {Object} [store]
     * @returns {{type: string, commentId: string}|null}
     */
    function validateEditMarker(rawMarker, markerKey, store) {
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
        if (parsed.type !== 'edit') {
            // Non-edit marker (e.g. reply) - safely ignore without removing
            return null;
        }
        if (typeof parsed.commentId !== 'string' || !parsed.commentId.trim()) {
            if (store && markerKey && typeof store.remove === 'function') {
                store.remove(markerKey);
            }
            return null;
        }
        return {
            type: 'edit',
            commentId: parsed.commentId.trim()
        };
    }

    /**
     * Removes the active marker only if it still matches the specified edit target.
     *
     * @param {string} chapterId
     * @param {string} commentId
     */
    function removeMarkerIfMatching(chapterId, commentId) {
        if (!chapterId || !commentId) return;
        const store = resolveDraftStore();
        if (!store) return;
        const markerKey = getMarkerKey(chapterId);
        if (!markerKey) return;
        const raw = store.load(markerKey);
        if (!raw) return;
        try {
            const parsed = JSON.parse(raw);
            if (parsed && parsed.type === 'edit' && parsed.commentId === commentId) {
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
     * Synchronously flushes the currently mounted edit draft to storage.
     * Checks equality with originalBody (removes if clean), checks whitespace (removes if blank),
     * and respects acceptedEditGenerations (resurrection prevention).
     */
    function flushActiveDraft() {
        cancelDebounce();
        if (!activeComposerEl || !activeEditTarget) {
            return;
        }
        const textarea = activeComposerEl.querySelector('.' + EDIT_INPUT_CLASS);
        if (!textarea) return;

        const store = resolveDraftStore();
        if (!store) return;

        const draftKey = getEditDraftKey(activeEditTarget.chapterId, activeEditTarget.commentId);
        if (!draftKey) return;

        const currentVal = textarea.value;

        // Clean (untouched server body) or empty/whitespace -> remove draft
        if (currentVal === activeOriginalBody || currentVal.trim().length === 0) {
            isDirty = false;
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            return;
        }

        // Resurrection prevention against accepted submission generation
        const currentEditGen = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        const acceptedGen = (draftKey && acceptedEditGenerations.get(draftKey)) || 0;
        if (acceptedGen > 0 && currentEditGen <= acceptedGen) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            return;
        }

        if (typeof store.save === 'function') {
            store.save(draftKey, currentVal);
        }
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
     * Finds the live Edit button for a target comment element.
     *
     * @param {Element} commentEl
     * @param {string} targetCommentId
     * @returns {Element|null}
     */
    function findEditButtonForComment(commentEl, targetCommentId) {
        if (!commentEl || !targetCommentId) return null;
        let buttons = [];
        if (typeof commentEl.querySelectorAll === 'function') {
            buttons = commentEl.querySelectorAll('.novel-comment-edit-btn');
            if (!buttons || buttons.length === 0) {
                buttons = commentEl.querySelectorAll('button[data-action="edit"]');
            }
            if (!buttons || buttons.length === 0) {
                buttons = commentEl.querySelectorAll('[data-action="edit"]');
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
     * Restores an active inline edit composer from persisted active-inline marker.
     *
     * @param {Document} [doc]
     * @param {string} [chapterId]
     */
    function restoreActiveInlineComposer(doc, chapterId) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;
        const sectionEl = d.getElementById ? d.getElementById(SECTION_ID) : null;
        if (!sectionEl) return;

        // Do not mount duplicate if active edit composer is already mounted
        if (activeComposerEl && activeComposerEl.parentNode) return;

        // DO NOT restore Edit over an active Bottom Reply composer
        if (isReplyComposerActive(sectionEl)) return;

        // DO NOT restore Edit over an active Bottom Delete interaction
        if (isDeleteActive(sectionEl)) return;

        const currentChapter = resolveChapterId(d);
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

        const marker = validateEditMarker(rawMarker, markerKey, store);
        if (!marker || marker.type !== 'edit') return;

        const targetCommentEl = findCommentElementByCommentId(sectionEl, marker.commentId);
        if (!targetCommentEl) return;

        // Reject tombstone / deleted target
        if (targetCommentEl.classList && typeof targetCommentEl.classList.contains === 'function') {
            if (targetCommentEl.classList.contains('novel-comment--tombstone')) {
                return;
            }
        }
        if (!isInsideBottomComments(targetCommentEl)) return;

        // Must have live edit button (ensures user has permission to edit)
        const editBtn = findEditButtonForComment(targetCommentEl, marker.commentId);
        if (!editBtn) return;

        // Authoritative root ID derived strictly from enclosing LIVE thread DOM hierarchy
        const liveRootId = findEnclosingRootId(targetCommentEl);
        const rootId = liveRootId ||
            (typeof editBtn.getAttribute === 'function' ? editBtn.getAttribute('data-root-id') : null) ||
            marker.commentId;

        if (liveRootId && typeof editBtn.setAttribute === 'function') {
            editBtn.setAttribute('data-root-id', liveRootId);
        }

        const bodyEl = targetCommentEl.querySelector ? targetCommentEl.querySelector('.novel-comment-body') : null;
        const cleanBody = extractCommentBody(targetCommentEl);

        const targetInfo = {
            commentId: marker.commentId,
            rootId: rootId,
            chapterId: effectiveChapter,
            cleanBody: cleanBody,
            commentEl: targetCommentEl,
            bodyEl: bodyEl,
            editBtn: editBtn
        };

        openEditComposer(targetInfo, { isRestore: true });
    }

    /**
     * Closes other Bottom interactions (Reply composer and Delete confirmation) if active.
     */
    function closeOtherInteractions() {
        const replyMod = resolveReplyComposerModule();
        if (replyMod && typeof replyMod.closeActiveComposer === 'function') {
            try {
                replyMod.closeActiveComposer(false);
            } catch (_) {}
        }

        const deleteMod = resolveDeleteModule();
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
    /**
     * Closes and removes any active edit composer from DOM, restoring hidden body elements.
     *
     * @param {boolean} [restoreFocus=false]
     * @param {Object} [options]
     * @param {boolean} [options.discard=false] - If true, discards draft and removes matching marker. Default: false (PRESERVE).
     * @param {boolean} [options.skipFlush=false] - If true, skips flushing draft (e.g. on 200/204).
     */
    function closeEditComposer(restoreFocus, options) {
        const discard = Boolean(options && options.discard);
        const skipFlush = Boolean(options && options.skipFlush);
        const targetCommentId = activeEditTarget ? activeEditTarget.commentId : null;
        const chapterId = activeEditTarget ? activeEditTarget.chapterId : null;

        cancelDebounce();

        if (discard) {
            if (chapterId && targetCommentId) {
                const store = resolveDraftStore();
                if (store) {
                    const draftKey = getEditDraftKey(chapterId, targetCommentId);
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
        activeOriginalBody = '';
        isDirty = false;
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

        // Authoritative root ID derived strictly from live enclosing thread DOM hierarchy
        const liveRootId = findEnclosingRootId(commentEl);
        let rootId = liveRootId || editBtn.getAttribute('data-root-id');
        if (!rootId) {
            rootId = commentId;
        }
        if (liveRootId && typeof editBtn.setAttribute === 'function') {
            editBtn.setAttribute('data-root-id', liveRootId);
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
     * @param {Object} [options]
     * @param {boolean} [options.isRestore=false]
     */
    function openEditComposer(targetInfo, options) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc || !targetInfo || !targetInfo.commentEl) return;

        const isRestore = Boolean(options && options.isRestore);

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

        activeOriginalBody = (typeof targetInfo.cleanBody === 'string') ? targetInfo.cleanBody : '';
        isDirty = false;

        const store = resolveDraftStore();
        const markerKey = getMarkerKey(targetInfo.chapterId);
        if (store && markerKey) {
            store.save(markerKey, JSON.stringify({ type: 'edit', commentId: targetInfo.commentId }));
        }

        const draftKey = getEditDraftKey(targetInfo.chapterId, targetInfo.commentId);
        let initialText = activeOriginalBody;
        if (store && draftKey) {
            const savedDraft = store.load(draftKey);
            if (typeof savedDraft === 'string') {
                if (savedDraft === activeOriginalBody) {
                    // Redundant draft equal to server body -> remove it, remain clean
                    if (typeof store.remove === 'function') {
                        store.remove(draftKey);
                    }
                    isDirty = false;
                    initialText = activeOriginalBody;
                } else if (savedDraft.trim().length > 0) {
                    initialText = savedDraft;
                    isDirty = true;
                }
            }
        }

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
        textarea.maxLength = 2000;
        textarea.value = initialText;

        textarea.addEventListener('input', function () {
            cancelDebounce();
            const currentVal = textarea.value;
            if (currentVal === activeOriginalBody) {
                isDirty = false;
                if (store && draftKey && typeof store.remove === 'function') {
                    store.remove(draftKey);
                }
            } else if (currentVal.trim().length === 0) {
                isDirty = false;
                if (store && draftKey && typeof store.remove === 'function') {
                    store.remove(draftKey);
                }
            } else {
                isDirty = true;
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
            }
        });

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
        cancelBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') e.preventDefault();
            closeEditComposer(true, { discard: true });
        });

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

        if (!isRestore && typeof textarea.focus === 'function') {
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

        const rawVal = textarea ? textarea.value : '';
        const newBody = rawVal.trim();
        if (!newBody) {
            setStatus(statusEl, 'Vui lòng nhập nội dung bình luận.', 'error');
            if (textarea && typeof textarea.focus === 'function') {
                try { textarea.focus(); } catch (_) {}
            }
            return;
        }

        cancelDebounce();

        const draftKey = getEditDraftKey(activeEditTarget.chapterId, activeEditTarget.commentId);
        const draftStore = resolveDraftStore();
        const currentGeneration = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        const acceptedGeneration = (draftKey && acceptedEditGenerations.get(draftKey)) || 0;
        let submittedEditGeneration = currentGeneration;

        if (rawVal === activeOriginalBody || newBody.length === 0) {
            isDirty = false;
            if (draftStore && draftKey && typeof draftStore.remove === 'function') {
                draftStore.remove(draftKey);
            }
        } else {
            isDirty = true;
            if (draftKey) {
                if (submittedEditGeneration <= acceptedGeneration || submittedEditGeneration === 0) {
                    submittedEditGeneration = ++globalEditGeneration;
                    keyEditGenerations.set(draftKey, submittedEditGeneration);
                    acceptedEditGenerations.delete(draftKey);
                }
            }
            if (draftStore && draftKey && typeof draftStore.save === 'function') {
                draftStore.save(draftKey, rawVal);
            }
        }

        isSubmitting = true;
        const mutationToken = ++currentMutationToken;
        const commentsModule = resolveCommentsModule();
        const rootSyncContext = resolveRootSyncContext(activeEditTarget.rootId, commentsModule);
        const snapshot = {
            chapterId: activeEditTarget.chapterId,
            commentId: activeEditTarget.commentId,
            rootId: activeEditTarget.rootId,
            draftKey: draftKey,
            draftStore: draftStore,
            editGeneration: submittedEditGeneration,
            rootSyncContext: rootSyncContext
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
                    const latestGeneration = (snapshot.draftKey && keyEditGenerations.get(snapshot.draftKey)) || 0;
                    if (latestGeneration <= snapshot.editGeneration) {
                        if (snapshot.draftKey) {
                            acceptedEditGenerations.set(snapshot.draftKey, snapshot.editGeneration);
                        }
                        if (snapshot.draftStore && snapshot.draftKey && typeof snapshot.draftStore.remove === 'function') {
                            snapshot.draftStore.remove(snapshot.draftKey);
                        }
                    }
                    removeMarkerIfMatching(snapshot.chapterId, snapshot.commentId);

                    isSubmitting = false;
                    closeEditComposer(false, { discard: false, skipFlush: true });

                    // Secondary Drawer synchronization (best-effort, only if canonical anchored and same block open)
                    synchronizeSecondaryDrawer(snapshot.chapterId, snapshot.rootSyncContext);

                    // Authoritative refresh of the root thread
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
                } else {
                    // Stale success handling
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
     * Handles feed rendered event to restore active inline edit composer if marker exists.
     *
     * @param {CustomEvent} [evt]
     */
    function handleFeedRendered(evt) {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;
        const currentChapter = resolveChapterId(doc);

        const eventChapterId = (evt && evt.detail && typeof evt.detail.chapterId === 'string' && evt.detail.chapterId.trim())
            ? evt.detail.chapterId.trim()
            : null;

        // If event specifies a chapter that does not match current Bottom chapter, ignore completely
        if (eventChapterId && currentChapter && eventChapterId !== currentChapter) {
            return;
        }

        const effectiveChapterId = eventChapterId || currentChapter;
        restoreActiveInlineComposer(doc, effectiveChapterId);
    }

    /**
     * Handles flush drafts event (pagehide bridge).
     */
    function handleFlushDrafts() {
        flushActiveDraft();
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
                if (feedRenderedHandler) {
                    d.removeEventListener(EVENT_FEED_RENDERED, feedRenderedHandler);
                }
                if (flushDraftsHandler) {
                    d.removeEventListener(EVENT_FLUSH_DRAFTS, flushDraftsHandler);
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
        feedRenderedHandler = null;
        flushDraftsHandler = null;
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
                    closeEditComposer(true, { discard: true });
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
            isSubmitting = false;
            closeEditComposer(false);
        };

        feedReplacingHandler = function () {
            currentMutationToken++;
            isSubmitting = false;
            closeEditComposer(false);
        };

        feedRenderedHandler = function (e) {
            handleFeedRendered(e);
        };

        flushDraftsHandler = function () {
            handleFlushDrafts();
        };

        keydownHandler = function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (activeComposerEl) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeEditComposer(true, { discard: true });
                }
            }
        };

        doc.addEventListener('click', delegatedClickHandler);
        doc.addEventListener('submit', submitHandler);
        doc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
        doc.addEventListener(EVENT_FEED_REPLACING, feedReplacingHandler);
        doc.addEventListener(EVENT_FEED_RENDERED, feedRenderedHandler);
        doc.addEventListener(EVENT_FLUSH_DRAFTS, flushDraftsHandler);
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
        activeOriginalBody = '';
        isDirty = false;
        cancelDebounce();

        injectedFetch = null;
        injectedMutations = null;
        injectedCommentsModule = null;
        injectedReplyComposer = null;
        injectedDeleteModule = null;
        injectedDrawerModule = null;
        injectedDraftAdapter = null;
        injectedDraftStore = null;
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
            if (options.replyComposerModule || options.replyComposer) injectedReplyComposer = options.replyComposerModule || options.replyComposer;
            if (options.deleteModule) injectedDeleteModule = options.deleteModule;
            if (options.drawerModule) injectedDrawerModule = options.drawerModule;
            if (options.fetchFn || options.fetch) injectedFetch = options.fetchFn || options.fetch;
            if (options.draftAdapter) injectedDraftAdapter = options.draftAdapter;
            if (options.draftStore) injectedDraftStore = options.draftStore;
        }

        bindEvents(doc);

        restoreActiveInlineComposer(doc);

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
                currentMutationToken: currentMutationToken,
                isDirty: isDirty,
                activeOriginalBody: activeOriginalBody
            };
        },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setMutationsClient: function (m) { injectedMutations = m; },
        setCommentsModule: function (m) { injectedCommentsModule = m; },
        setReplyComposerModule: function (m) { injectedReplyComposer = m; },
        setDeleteModule: function (m) { injectedDeleteModule = m; },
        setDrawerModule: function (m) { injectedDrawerModule = m; },
        setDraftAdapterImplementation: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftAdapter: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftStoreImplementation: function (store) { injectedDraftStore = store; },
        setDraftStore: function (store) { injectedDraftStore = store; },
        getKeyEditGeneration: function (key) { return keyEditGenerations.get(key) || 0; },
        getAcceptedEditGeneration: function (key) { return acceptedEditGenerations.get(key) || 0; },
        isDirty: function () { return isDirty; },
        getActiveOriginalBody: function () { return activeOriginalBody; },
        restoreActiveInlineComposer: restoreActiveInlineComposer,
        flushActiveDraft: flushActiveDraft,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        EVENT_FEED_REPLACING: EVENT_FEED_REPLACING,
        EVENT_FEED_RENDERED: EVENT_FEED_RENDERED,
        EVENT_FLUSH_DRAFTS: EVENT_FLUSH_DRAFTS
    };
});
