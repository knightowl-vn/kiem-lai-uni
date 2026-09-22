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

    const DRAWER_ID = 'novelBlockDiscussionDrawer';
    const EDIT_COMPOSER_CLASS = 'novel-edit-composer';
    const EDIT_COMPOSER_FORM_ID = 'novelBlockDiscussionEditComposerForm';
    const EDIT_INPUT_CLASS = 'novel-edit-composer-input';
    const EDIT_STATUS_CLASS = 'novel-edit-composer-status';
    const EDIT_SUBMIT_CLASS = 'novel-edit-composer-submit';
    const EDIT_CANCEL_CLASS = 'novel-edit-composer-cancel';
    const EDIT_ACTIONS_CLASS = 'novel-edit-composer-actions';

    /**
     * Checks whether an element belongs inside the Discussion Drawer (#novelBlockDiscussionDrawer).
     *
     * @param {Element} el
     * @returns {boolean}
     */
    function isInsideDrawer(el) {
        if (!el) return false;
        if (typeof el.closest === 'function') {
            return el.closest('#' + DRAWER_ID) !== null;
        }
        let cur = el;
        while (cur) {
            if (cur.id === DRAWER_ID || (typeof cur.getAttribute === 'function' && cur.getAttribute('id') === DRAWER_ID)) {
                return true;
            }
            cur = cur.parentElement || cur.parentNode;
        }
        return false;
    }

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_EDIT_RESUME_REQUESTED = 'kiemlai:comment-edit-resume-requested';
    const EVENT_FLUSH_DRAFTS = 'kiemlai:novel-comment-drafts-flush';

    const DEBOUNCE_DELAY_MS = 400;

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let injectedFetch = null;
    let injectedDrawer = null;
    let injectedReplyComposer = null;
    let injectedDraftAdapter = null;
    let injectedDraftStore = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeEditTarget = null;
    let activeComposerEl = null;

    let draftDebounceTimer = null;

    let globalEditGeneration = 0;
    const keyEditGenerations = new Map();
    const acceptedEditGenerations = new Map();

    let nodeCommentMutations = null;
    if (typeof require === 'function') {
        try {
            nodeCommentMutations = require('./reader-comment-mutations.js');
        } catch (_) {}
    }

    let injectedCommentMutations = null;

    let nodeCommentsModule = null;
    if (typeof require === 'function') {
        try {
            nodeCommentsModule = require('./reader-chapter-comments.js');
        } catch (_) {}
    }

    let injectedCommentsModule = null;

    let nodeDraftAdapter = null;
    if (typeof require === 'function') {
        try {
            nodeDraftAdapter = require('./reader-comment-drafts.js');
        } catch (_) {}
    }

    /**
     * Resolves NovelReaderCommentDrafts adapter instance.
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
        return nodeDraftAdapter;
    }

    /**
     * Resolves EphemeralDraftStore instance across multiple runtime contexts.
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
     * Canonical Block Drawer Edit Draft Key.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @param {string} commentId
     * @returns {string|null}
     */
    function getEditDraftKey(chapterId, blockKey, commentId) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getBlockEditDraftKey === 'function') {
            return adapter.getBlockEditDraftKey(chapterId, blockKey, commentId);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim() ||
            typeof blockKey !== 'string' || !blockKey.trim() ||
            typeof commentId !== 'string' || !commentId.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':block:' + encodeURIComponent(blockKey.trim()) + ':edit:' + encodeURIComponent(commentId.trim());
    }

    /**
     * Canonical Block Drawer Active Marker Key (chapter-scoped).
     *
     * @param {string} chapterId
     * @returns {string|null}
     */
    function getActiveMarkerKey(chapterId) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getBlockActiveMarkerKey === 'function') {
            return adapter.getBlockActiveMarkerKey(chapterId);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':active-block';
    }

    /**
     * Saves chapter-scoped active-block Edit marker.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @param {string} commentId
     */
    function saveEditMarker(chapterId, blockKey, commentId) {
        if (!chapterId || !blockKey || !commentId) return;
        const store = resolveDraftStore();
        if (!store || typeof store.save !== 'function') return;
        const markerKey = getActiveMarkerKey(chapterId);
        if (!markerKey) return;
        store.save(markerKey, JSON.stringify({
            type: 'edit',
            blockKey: String(blockKey).trim(),
            commentId: String(commentId).trim()
        }));
    }

    /**
     * Removes the chapter-scoped active-block marker only if it matches type 'edit',
     * the given blockKey, and the given commentId.
     * Known other markers (type 'root' or 'reply') are preserved untouched.
     * Corrupted JSON markers are removed safely.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @param {string} commentId
     */
    function removeEditMarkerIfMatching(chapterId, blockKey, commentId) {
        if (!chapterId || !blockKey || !commentId) return;
        const store = resolveDraftStore();
        if (!store || typeof store.load !== 'function' || typeof store.remove !== 'function') return;
        const markerKey = getActiveMarkerKey(chapterId);
        if (!markerKey) return;
        const raw = store.load(markerKey);
        if (!raw || typeof raw !== 'string') return;
        try {
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                if (parsed.type === 'root' || parsed.type === 'reply') {
                    return; // Known other markers must not be touched
                }
                if (parsed.type === 'edit' &&
                    typeof parsed.blockKey === 'string' && parsed.blockKey.trim() === String(blockKey).trim() &&
                    typeof parsed.commentId === 'string' && parsed.commentId.trim() === String(commentId).trim()) {
                    store.remove(markerKey);
                }
            } else {
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
     * Synchronously flushes draft for a given edit target context.
     *
     * @param {{chapterId: string, blockKey: string, commentId: string, activeOriginalBody?: string}} context
     * @param {string} currentValue
     * @param {boolean} [isLeaving=false]
     */
    function flushDraftForContext(context, currentValue, isLeaving) {
        cancelDebounce();
        if (!context || !context.chapterId || !context.blockKey || !context.commentId) {
            return;
        }
        const store = resolveDraftStore();
        if (!store) return;

        const draftKey = getEditDraftKey(context.chapterId, context.blockKey, context.commentId);
        if (!draftKey) return;

        const text = currentValue || '';
        const originalBody = context.activeOriginalBody != null ? context.activeOriginalBody : '';

        if (text === originalBody || text.trim().length === 0) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            removeEditMarkerIfMatching(context.chapterId, context.blockKey, context.commentId);
            return;
        }

        const currentGen = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        const acceptedGen = (draftKey && acceptedEditGenerations.get(draftKey)) || 0;
        if (acceptedGen > 0 && currentGen <= acceptedGen) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            if (isLeaving) {
                removeEditMarkerIfMatching(context.chapterId, context.blockKey, context.commentId);
            }
            return;
        }

        if (typeof store.save === 'function') {
            store.save(draftKey, text);
            if (isLeaving) {
                removeEditMarkerIfMatching(context.chapterId, context.blockKey, context.commentId);
            } else {
                saveEditMarker(context.chapterId, context.blockKey, context.commentId);
            }
        }
    }

    /**
     * Flushes currently active edit draft to store.
     */
    function flushActiveDraft() {
        if (!activeEditTarget || !activeComposerEl) return;
        const textarea = activeComposerEl.querySelector('.' + EDIT_INPUT_CLASS);
        const val = textarea ? textarea.value : '';
        flushDraftForContext(activeEditTarget, val, false);
    }

    /**
     * Handles global novel drafts flush event (pagehide bridge).
     */
    function handleFlushDrafts() {
        flushActiveDraft();
    }

    /**
     * Resolves the bottom chapter comments module.
     *
     * @returns {Object|null}
     */
    function resolveCommentsModule() {
        if (injectedCommentsModule) {
            return injectedCommentsModule;
        }
        if (typeof window !== 'undefined') {
            return window.NovelReaderChapterComments ||
                (window.KiemLai && window.KiemLai.NovelReaderChapterComments) ||
                null;
        }
        return nodeCommentsModule;
    }

    /**
     * Checks if a root thread is loaded in the Bottom comments module.
     *
     * @param {Object} bottomMod
     * @param {string} rootId
     * @returns {boolean}
     */
    function isBottomRootLoaded(bottomMod, rootId) {
        if (!bottomMod || typeof bottomMod.getState !== 'function' || !rootId) {
            return false;
        }
        try {
            const state = bottomMod.getState();
            if (!state || !state.rootPageMap) {
                return false;
            }
            const strId = String(rootId);
            return Object.prototype.hasOwnProperty.call(state.rootPageMap, strId) || (strId in state.rootPageMap);
        } catch (_) {
            return false;
        }
    }

    /**
     * Synchronizes Bottom comments feed after Drawer edit success.
     * If rootId is loaded in Bottom, triggers authoritative refreshRootThread(rootId).
     * If rootId is NOT loaded in Bottom, this is a NO-OP (not count changing).
     *
     * @param {string} rootId
     */
    function synchronizeBottomEdit(rootId) {
        const bottomMod = resolveCommentsModule();
        if (!bottomMod) return;

        if (isBottomRootLoaded(bottomMod, rootId)) {
            if (typeof bottomMod.refreshRootThread === 'function') {
                try {
                    bottomMod.refreshRootThread(rootId).catch(function () {});
                } catch (_) {}
            }
        }
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
        return nodeCommentMutations;
    }

    /**
     * Resolves the reply composer module instance.
     *
     * @returns {Object|null}
     */
    function resolveReplyComposerModule() {
        if (injectedReplyComposer) {
            return injectedReplyComposer;
        }
        if (typeof window !== 'undefined') {
            return window.NovelReaderBlockDiscussionReplyComposer ||
                (window.KiemLai && window.KiemLai.NovelReaderBlockDiscussionReplyComposer) ||
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
     * @param {Object} [options]
     * @param {boolean} [options.discard=false]
     * @param {boolean} [options.skipFlush=false]
     */
    function closeEditComposer(restoreFocus, options) {
        const discard = Boolean(options && options.discard);
        const skipFlush = Boolean(options && options.skipFlush);
        const prevTarget = activeEditTarget;
        const prevComposer = activeComposerEl;

        cancelDebounce();

        if (discard) {
            if (prevTarget) {
                const store = resolveDraftStore();
                const draftKey = getEditDraftKey(prevTarget.chapterId, prevTarget.blockKey, prevTarget.commentId);
                if (store && draftKey && typeof store.remove === 'function') {
                    store.remove(draftKey);
                }
                removeEditMarkerIfMatching(prevTarget.chapterId, prevTarget.blockKey, prevTarget.commentId);
            }
        } else if (!skipFlush) {
            if (prevTarget && prevComposer) {
                const textarea = prevComposer.querySelector('.' + EDIT_INPUT_CLASS);
                const val = textarea ? textarea.value : '';
                flushDraftForContext(prevTarget, val, true);
            }
        }

        currentMutationToken++;
        if (activeComposerEl && activeComposerEl.parentNode) {
            try {
                activeComposerEl.parentNode.removeChild(activeComposerEl);
            } catch (_) {}
        }
        activeComposerEl = null;

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
     * Handles textarea input event: validates text against activeOriginalBody baseline,
     * updates marker, and debounces draft persistence ~400ms.
     */
    function handleInput() {
        cancelDebounce();
        if (!activeEditTarget || !activeComposerEl) return;
        const textarea = activeComposerEl.querySelector('.' + EDIT_INPUT_CLASS);
        if (!textarea) return;

        const chapterId = activeEditTarget.chapterId;
        const blockKey = activeEditTarget.blockKey;
        const commentId = activeEditTarget.commentId;
        const originalBody = activeEditTarget.activeOriginalBody != null ? activeEditTarget.activeOriginalBody : '';
        const draftKey = getEditDraftKey(chapterId, blockKey, commentId);
        const store = resolveDraftStore();
        if (!store || !draftKey) return;

        const currentVal = textarea.value;

        if (currentVal === originalBody || currentVal.trim().length === 0) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            removeEditMarkerIfMatching(chapterId, blockKey, commentId);
        } else {
            const nextGen = ++globalEditGeneration;
            keyEditGenerations.set(draftKey, nextGen);
            if (acceptedEditGenerations.has(draftKey)) {
                acceptedEditGenerations.delete(draftKey);
            }

            saveEditMarker(chapterId, blockKey, commentId);

            draftDebounceTimer = setTimeout(function () {
                draftDebounceTimer = null;
                if (activeEditTarget &&
                    activeEditTarget.chapterId === chapterId &&
                    activeEditTarget.blockKey === blockKey &&
                    activeEditTarget.commentId === commentId) {
                    const latestVal = textarea.value;
                    if (latestVal !== originalBody && latestVal.trim().length > 0) {
                        if (typeof store.save === 'function') {
                            store.save(draftKey, latestVal);
                        }
                    } else {
                        if (typeof store.remove === 'function') {
                            store.remove(draftKey);
                        }
                        removeEditMarkerIfMatching(chapterId, blockKey, commentId);
                    }
                }
            }, DEBOUNCE_DELAY_MS);
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

        // Close any active reply composer before establishing edit composer
        const replyMod = resolveReplyComposerModule();
        if (replyMod && typeof replyMod.closeReplyComposer === 'function') {
            try {
                replyMod.closeReplyComposer(false);
            } catch (_) {}
        }

        // Close any currently active composer (clearing previous target's draft and unhiding its body)
        closeEditComposer(false);
        currentMutationToken++;

        // Derive chapterId and blockKey with safe fallbacks
        let chapterId = (targetInfo.chapterId || '').trim();
        let blockKey = (targetInfo.blockKey || '').trim();
        if (!chapterId || !blockKey) {
            const drawer = resolveDrawerModule();
            const activeCtx = drawer && typeof drawer.getActiveContext === 'function' ? drawer.getActiveContext() : null;
            if (activeCtx) {
                chapterId = chapterId || (activeCtx.chapterId || '').trim();
                blockKey = blockKey || (activeCtx.blockKey || '').trim();
            }
        }
        if (!chapterId && doc) {
            const drawerEl = doc.getElementById ? doc.getElementById('novelBlockDiscussionDrawer') : null;
            if (drawerEl && typeof drawerEl.getAttribute === 'function') {
                chapterId = (drawerEl.getAttribute('data-chapter-id') || '').trim();
                blockKey = blockKey || (drawerEl.getAttribute('data-block-key') || '').trim();
            }
            if (!chapterId) {
                const bodyEl = doc.querySelector ? doc.querySelector('.novel-reader-chapter-body') : null;
                if (bodyEl && typeof bodyEl.getAttribute === 'function') {
                    chapterId = (bodyEl.getAttribute('data-chapter-id') || '').trim();
                }
            }
        }

        const originalBody = targetInfo.cleanBody || '';

        activeEditTarget = {
            commentId: targetInfo.commentId,
            rootId: targetInfo.rootId,
            chapterId: chapterId,
            blockKey: blockKey,
            cleanBody: originalBody,
            activeOriginalBody: originalBody,
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

        // 1. Textarea
        const textarea = doc.createElement('textarea');
        textarea.className = EDIT_INPUT_CLASS;
        textarea.setAttribute('rows', '3');
        textarea.setAttribute('aria-label', 'Chỉnh sửa bình luận');
        textarea.maxLength = 2000;

        // Check draft store
        const store = resolveDraftStore();
        const draftKey = getEditDraftKey(chapterId, blockKey, targetInfo.commentId);
        let savedDraft = null;
        if (store && draftKey && typeof store.load === 'function') {
            savedDraft = store.load(draftKey);
        }

        if (typeof savedDraft === 'string' && savedDraft !== originalBody && savedDraft.trim().length > 0) {
            textarea.value = savedDraft;
            saveEditMarker(chapterId, blockKey, targetInfo.commentId);
        } else {
            textarea.value = originalBody;
            if (store && draftKey && typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            removeEditMarkerIfMatching(chapterId, blockKey, targetInfo.commentId);
        }

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

        // Attach input listener
        textarea.addEventListener('input', handleInput);

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

        const rawBody = textarea.value || '';
        const submittedBody = rawBody.trim();
        if (!submittedBody) {
            setStatus(statusEl, 'Vui lòng nhập nội dung bình luận.', 'error');
            if (typeof textarea.focus === 'function') {
                try {
                    textarea.focus();
                } catch (_) {}
            }
            return;
        }

        cancelDebounce();

        const chapterId = activeEditTarget.chapterId;
        const blockKey = activeEditTarget.blockKey;
        const commentId = activeEditTarget.commentId;
        const rootId = activeEditTarget.rootId;
        const originalBody = activeEditTarget.activeOriginalBody != null ? activeEditTarget.activeOriginalBody : '';
        const draftKey = getEditDraftKey(chapterId, blockKey, commentId);
        const draftStore = resolveDraftStore();

        const currentGeneration = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        const acceptedGeneration = (draftKey && acceptedEditGenerations.get(draftKey)) || 0;
        let submittedGeneration = currentGeneration;

        if (submittedGeneration <= acceptedGeneration || submittedGeneration === 0) {
            submittedGeneration = ++globalEditGeneration;
            keyEditGenerations.set(draftKey, submittedGeneration);
            acceptedEditGenerations.delete(draftKey);
        }

        if (rawBody !== originalBody && rawBody.trim().length > 0) {
            if (draftStore && draftKey && typeof draftStore.save === 'function') {
                draftStore.save(draftKey, rawBody);
            }
            saveEditMarker(chapterId, blockKey, commentId);
        }

        isSubmitting = true;
        const mutationToken = ++currentMutationToken;
        const snapshot = {
            chapterId: chapterId,
            commentId: commentId,
            rootId: rootId,
            blockKey: blockKey,
            draftKey: draftKey,
            draftStore: draftStore,
            editGeneration: submittedGeneration
        };

        textarea.disabled = true;
        if (submitBtn) submitBtn.disabled = true;
        if (cancelBtn) cancelBtn.disabled = true;
        setStatus(statusEl, 'Đang lưu...', 'info');

        const mutationsClient = resolveCommentMutations();
        if (!mutationsClient || typeof mutationsClient.editComment !== 'function') {
            isSubmitting = false;
            textarea.disabled = false;
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
                    body: submittedBody
                },
                {
                    document: currentDoc,
                    fetch: injectedFetch
                }
            );

            if (res && (res.status === 204 || res.status === 200 || res.ok)) {
                // Clean accepted draft if current generation has not advanced past submitted generation
                const latestGeneration = (snapshot.draftKey && keyEditGenerations.get(snapshot.draftKey)) || 0;
                if (latestGeneration <= snapshot.editGeneration) {
                    if (snapshot.draftKey) {
                        acceptedEditGenerations.set(snapshot.draftKey, snapshot.editGeneration);
                    }
                    if (snapshot.draftStore && snapshot.draftKey && typeof snapshot.draftStore.remove === 'function') {
                        snapshot.draftStore.remove(snapshot.draftKey);
                    }
                    removeEditMarkerIfMatching(snapshot.chapterId, snapshot.blockKey, snapshot.commentId);

                    if (isMutationContextCurrent(mutationToken, snapshot)) {
                        isSubmitting = false;
                        closeEditComposer(false, { skipFlush: true });

                        // Refresh authoritative drawer (preserves open drawer and updates comment body)
                        const drawer = resolveDrawerModule();
                        if (drawer && typeof drawer.refreshActiveDiscussion === 'function') {
                            try {
                                drawer.refreshActiveDiscussion().catch(function () {});
                            } catch (_) {}
                        }

                        // Authoritative secondary synchronization: Bottom Comments feed
                        if (snapshot.rootId) {
                            synchronizeBottomEdit(snapshot.rootId);
                        }
                    }
                } else {
                    // Newer draft was typed while in flight: keep composer open and draft preserved
                    if (isMutationContextCurrent(mutationToken, snapshot)) {
                        isSubmitting = false;
                        textarea.disabled = false;
                        if (submitBtn) submitBtn.disabled = false;
                        if (cancelBtn) cancelBtn.disabled = false;
                        setStatus(statusEl, '', '');
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

            const status = res ? res.status : 0;
            if (status === 400) {
                setStatus(statusEl, 'Nội dung bình luận không hợp lệ.', 'error');
            } else if (status === 401 || status === 403) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.', 'error');
            } else if (status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else {
                setStatus(statusEl, 'Không thể lưu thay đổi. Vui lòng thử lại.', 'error');
            }
        } catch (err) {
            if (!isMutationContextCurrent(mutationToken, snapshot)) {
                return;
            }
            isSubmitting = false;
            textarea.disabled = false;
            if (submitBtn) submitBtn.disabled = false;
            if (cancelBtn) cancelBtn.disabled = false;

            if (err && err.code === 'CSRF_MISSING') {
                setStatus(statusEl, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.', 'error');
            } else if (err && err.code === 'FETCH_UNAVAILABLE') {
                setStatus(statusEl, 'Không thể lưu bình luận. Trình duyệt không hỗ trợ fetch.', 'error');
            } else if (err && err.status === 400) {
                setStatus(statusEl, 'Nội dung bình luận không hợp lệ.', 'error');
            } else if (err && (err.status === 401 || err.status === 403)) {
                setStatus(statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền chỉnh sửa bình luận này.', 'error');
            } else if (err && err.status === 404) {
                setStatus(statusEl, 'Bình luận không còn tồn tại.', 'error');
            } else if (err && err.status) {
                setStatus(statusEl, 'Không thể lưu thay đổi. Vui lòng thử lại.', 'error');
            } else {
                setStatus(statusEl, 'Lỗi kết nối mạng. Vui lòng thử lại.', 'error');
            }
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

        let rootId = null;
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
            const threadRoot = threadCard.getAttribute('data-root-id') || threadCard.getAttribute('data-comment-id');
            if (threadRoot && typeof threadRoot === 'string' && threadRoot.trim()) {
                rootId = threadRoot.trim();
            }
        }
        if (!rootId) {
            const btnRoot = editBtn.getAttribute('data-root-id');
            if (btnRoot && typeof btnRoot === 'string' && btnRoot.trim()) {
                rootId = btnRoot.trim();
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
     * @param {Event|Element} e
     */
    function handleEditButtonClick(e) {
        const target = e ? (e.target || e.srcElement || e) : null;
        if (!target) return;

        let editBtn = null;
        if (typeof target.closest === 'function') {
            editBtn = target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]');
        } else {
            let cur = target;
            while (cur) {
                if (cur.getAttribute && (cur.getAttribute('data-action') === 'edit' || (cur.classList && cur.classList.contains('novel-comment-edit-btn')))) {
                    editBtn = cur;
                    break;
                }
                cur = cur.parentElement || cur.parentNode;
            }
        }

        if (!editBtn || !isInsideDrawer(editBtn)) return;

        if (typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const targetInfo = resolveTargetFromButton(editBtn);
        if (!targetInfo) return;

        openEditComposer(targetInfo);
    }

    /**
     * Handles restore resume request event for comment edit.
     *
     * @param {Event} e
     */
    function handleEditResumeRequested(e) {
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
            // Tombstoned target: do NOT attach edit composer
            return;
        }

        let editBtn = null;
        if (matchedComment.querySelector) {
            editBtn = matchedComment.querySelector('.novel-comment-edit-btn') ||
                matchedComment.querySelector('button[data-action="edit"]') ||
                matchedComment.querySelector('[data-action="edit"]');
        }
        if (!editBtn) {
            // Non-editable target: do NOT mount edit composer
            return;
        }

        const bodyEl = matchedComment.querySelector ? matchedComment.querySelector('.novel-comment-body') : null;
        if (!bodyEl) {
            return;
        }
        const cleanBody = extractCommentBody(matchedComment);
        if (cleanBody === null || cleanBody === undefined) {
            return;
        }

        let rootId = null;
        let threadCard = null;
        if (typeof matchedComment.closest === 'function') {
            threadCard = matchedComment.closest('.novel-block-discussion-thread');
        } else {
            let cur = matchedComment.parentElement;
            while (cur) {
                if (cur.classList && cur.classList.contains('novel-block-discussion-thread')) {
                    threadCard = cur;
                    break;
                }
                cur = cur.parentElement;
            }
        }
        if (threadCard && typeof threadCard.getAttribute === 'function') {
            const rawRoot = threadCard.getAttribute('data-root-id') || threadCard.getAttribute('data-comment-id');
            if (rawRoot && typeof rawRoot === 'string' && rawRoot.trim()) {
                rootId = rawRoot.trim();
            }
        }
        if (!rootId) {
            // Live enclosing thread/root is missing: do NOT mount edit composer, do NOT fabricate rootId = commentId
            return;
        }

        const drawer = resolveDrawerModule();
        const activeCtx = drawer && typeof drawer.getActiveContext === 'function' ? drawer.getActiveContext() : null;
        let chapterId = (activeCtx && activeCtx.chapterId) ? String(activeCtx.chapterId).trim() : (detail.chapterId ? String(detail.chapterId).trim() : '');
        let blockKey = (activeCtx && activeCtx.blockKey) ? String(activeCtx.blockKey).trim() : (detail.blockKey ? String(detail.blockKey).trim() : '');
        if (!chapterId && doc) {
            const drawerEl = doc.getElementById ? doc.getElementById('novelBlockDiscussionDrawer') : null;
            if (drawerEl && typeof drawerEl.getAttribute === 'function') {
                chapterId = (drawerEl.getAttribute('data-chapter-id') || '').trim();
                blockKey = blockKey || (drawerEl.getAttribute('data-block-key') || '').trim();
            }
        }
        if (!chapterId || !blockKey) {
            return;
        }

        const targetInfo = {
            commentId: commentId,
            rootId: rootId,
            chapterId: chapterId,
            blockKey: blockKey,
            cleanBody: cleanBody,
            commentEl: matchedComment,
            bodyEl: bodyEl,
            editBtn: editBtn
        };

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
            let editBtn = null;
            if (typeof target.closest === 'function') {
                editBtn = target.closest('.novel-comment-edit-btn') || target.closest('button[data-action="edit"]');
            } else if (target.classList && target.classList.contains('novel-comment-edit-btn')) {
                editBtn = target;
            }
            if (editBtn) {
                if (isInsideDrawer(editBtn)) {
                    handleEditButtonClick(e);
                }
                return;
            }

            // Cancel edit composer
            let cancelBtn = null;
            if (typeof target.closest === 'function') {
                cancelBtn = target.closest('.' + EDIT_CANCEL_CLASS);
            } else if (target.classList && target.classList.contains(EDIT_CANCEL_CLASS)) {
                cancelBtn = target;
            }
            if (cancelBtn) {
                if (isInsideDrawer(cancelBtn)) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeEditComposer(true, { discard: true });
                }
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

        // 3. Edit resume requested event
        doc.addEventListener(EVENT_EDIT_RESUME_REQUESTED, handleEditResumeRequested);

        // 4. Chapter transition: clear state and pending UI
        doc.addEventListener(EVENT_CHAPTER_CHANGED, function () {
            currentMutationToken++;
            closeEditComposer(false);
        });

        // 5. Drawer closed: clear edit composer
        doc.addEventListener(EVENT_DISCUSSION_CLOSED, function () {
            currentMutationToken++;
            closeEditComposer(false);
        });

        // 6. Block discussion requested: new block requested
        doc.addEventListener(EVENT_DISCUSSION_REQUESTED, function () {
            currentMutationToken++;
            closeEditComposer(false);
        });

        // 7. Global flush bridge (pagehide bridge)
        doc.addEventListener(EVENT_FLUSH_DRAFTS, function () {
            flushActiveDraft();
        });

        // 8. Keyboard Escape cancels active edit composer
        doc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
                if (activeComposerEl) {
                    if (typeof e.preventDefault === 'function') e.preventDefault();
                    closeEditComposer(true, { discard: true });
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

        if (options) {
            if (options.commentMutations || options.mutationsClient || options.mutations) {
                injectedCommentMutations = options.commentMutations || options.mutationsClient || options.mutations;
            }
            if (options.fetchFn || options.fetch) {
                injectedFetch = options.fetchFn || options.fetch;
            }
            if (options.drawerModule) {
                injectedDrawer = options.drawerModule;
            }
            if (options.replyComposerModule) {
                injectedReplyComposer = options.replyComposerModule;
            }
            if (options.commentsModule || options.chapterCommentsModule) {
                injectedCommentsModule = options.commentsModule || options.chapterCommentsModule;
            }
            if (options.draftAdapter) {
                injectedDraftAdapter = options.draftAdapter;
            }
            if (options.draftStore) {
                injectedDraftStore = options.draftStore;
            }
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
        cancelDebounce();
        keyEditGenerations.clear();
        acceptedEditGenerations.clear();
        globalEditGeneration = 0;
        closeEditComposer(false, { skipFlush: true });
        currentDoc = null;
        boundDoc = null;
        injectedFetch = null;
        injectedDrawer = null;
        injectedReplyComposer = null;
        injectedCommentMutations = null;
        injectedCommentsModule = null;
        injectedDraftAdapter = null;
        injectedDraftStore = null;
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
        handleEditResumeRequested,
        resolveTargetFromButton,
        extractCommentBody,
        getActiveEditTarget: function () { return activeEditTarget; },
        getActiveComposerEl: function () { return activeComposerEl; },
        isSubmittingEdit: function () { return isSubmitting; },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setDrawerModule: function (mod) { injectedDrawer = mod; },
        setCommentMutations: function (m) { injectedCommentMutations = m; },
        setCommentsModule: function (mod) { injectedCommentsModule = mod; },
        setReplyComposerModule: function (mod) { injectedReplyComposer = mod; },
        resolveCommentMutations: resolveCommentMutations,
        resolveCommentsModule: resolveCommentsModule,
        getEditDraftKey: getEditDraftKey,
        getActiveMarkerKey: getActiveMarkerKey,
        saveEditMarker: saveEditMarker,
        removeEditMarkerIfMatching: removeEditMarkerIfMatching,
        flushDraftForContext: flushDraftForContext,
        flushActiveDraft: flushActiveDraft,
        cancelDebounce: cancelDebounce,
        setDraftAdapter: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftAdapterImplementation: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftStore: function (store) { injectedDraftStore = store; },
        setDraftStoreImplementation: function (store) { injectedDraftStore = store; },
        EVENT_EDIT_RESUME_REQUESTED: EVENT_EDIT_RESUME_REQUESTED,
        EVENT_FLUSH_DRAFTS: EVENT_FLUSH_DRAFTS
    };
});
