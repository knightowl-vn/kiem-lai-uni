/**
 * KiemLai Universe — Novel Block Discussion Root Comment Composer (MS-05E5G3)
 *
 * Responsibilities:
 * - Manages root inline comment composition UX inside the discussion drawer.
 * - Listens for 'kiemlai:block-discussion-loaded' to acquire authoritative context
 *   (chapterId, contentVersion, blockKey, threadCount) and enable the composer.
 * - Listens for 'kiemlai:block-discussion-requested' and 'kiemlai:chapter-changed'
 *   to disable the composer and manage draft lifecycle.
 * - Extracts CSRF token and header name from <meta> tags in the document head.
 * - Client-side validation: ensures non-blank comment body before submitting.
 * - Issues POST /api/novel/chapters/{chapterId}/comments/inline with exact payload:
 *     { "body": "...", "anchor": { "contentVersion": N, "blockKey": "..." } }
 * - Double-submit guard and mutation in-flight UI state management.
 * - On HTTP 201: clears draft, clears status, calls drawer refreshActiveDiscussion(),
 *   and calls indicators refreshChapterIndicators().
 * - On HTTP 409 (version conflict): preserves draft, triggers drawer refresh to acquire
 *   authoritative version, and displays descriptive conflict message.
 * - On HTTP 401/403: preserves draft, displays session expired message.
 * - On HTTP 400/404/500/network error: preserves draft, displays user-friendly error.
 * - Mutation race safety: late responses from previous blocks/chapters cannot alter
 *   the active composer state or clear a different block's draft.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelBlockDiscussionComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelBlockDiscussionComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const COMPOSER_FORM_ID = 'novelBlockDiscussionComposer';
    const INPUT_ID = 'novelBlockDiscussionComposerInput';
    const STATUS_ID = 'novelBlockDiscussionComposerStatus';
    const SUBMIT_ID = 'novelBlockDiscussionComposerSubmit';

    const EVENT_DISCUSSION_REQUESTED = 'kiemlai:block-discussion-requested';
    const EVENT_DISCUSSION_LOADED = 'kiemlai:block-discussion-loaded';
    const EVENT_DISCUSSION_CLOSED = 'kiemlai:block-discussion-closed';
    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_FLUSH_DRAFTS = 'kiemlai:novel-comment-drafts-flush';

    const DEBOUNCE_DELAY_MS = 400;

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let boundForm = null;
    let boundInput = null;
    let isInitialized = false;
    let isSubmitting = false;
    let authoritativeContext = null;
    let currentMutationToken = 0;

    let injectedFetch = null;
    let injectedDrawer = null;
    let injectedIndicators = null;
    let injectedCommentsModule = null;
    let injectedDraftAdapter = null;
    let injectedDraftStore = null;

    let draftDebounceTimer = null;

    let globalEditGeneration = 0;
    const keyEditGenerations = new Map();
    const acceptedEditGenerations = new Map();

    let nodeCommentsModule = null;
    if (typeof require === 'function') {
        try {
            nodeCommentsModule = require('./reader-chapter-comments.js');
        } catch (_) {}
    }

    let nodeDraftAdapter = null;
    if (typeof require === 'function') {
        try {
            nodeDraftAdapter = require('./reader-comment-drafts.js');
        } catch (_) {}
    }

    /**
     * Resolves the NovelReaderCommentDrafts adapter instance.
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
     * Resolves the EphemeralDraftStore instance across multiple runtime contexts.
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
     * Canonical Block Drawer Root Draft Key.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @returns {string|null}
     */
    function getRootDraftKey(chapterId, blockKey) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getBlockRootDraftKey === 'function') {
            return adapter.getBlockRootDraftKey(chapterId, blockKey);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim() ||
            typeof blockKey !== 'string' || !blockKey.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':block:' + encodeURIComponent(blockKey.trim()) + ':root';
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
     * Removes the chapter-scoped active-block marker only if it matches type 'root' and the given blockKey.
     * Known future markers (type 'reply' or 'edit') are ignored without removal.
     * Corrupted JSON markers are removed safely.
     *
     * @param {string} chapterId
     * @param {string} blockKey
     */
    function removeRootMarkerIfMatching(chapterId, blockKey) {
        if (!chapterId || !blockKey) return;
        const store = resolveDraftStore();
        if (!store) return;
        const markerKey = getActiveMarkerKey(chapterId);
        if (!markerKey) return;
        const raw = store.load(markerKey);
        if (!raw || typeof raw !== 'string') return;
        try {
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                if (parsed.type === 'reply' || parsed.type === 'edit') {
                    return; // Known future markers must not be touched
                }
                if (parsed.type === 'root' && typeof parsed.blockKey === 'string' && parsed.blockKey.trim() === String(blockKey).trim()) {
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
     * Synchronously flushes draft for a given authoritative block context.
     *
     * @param {{chapterId: string, blockKey: string}} context
     * @param {string} currentValue
     * @param {boolean} [isLeaving=false]
     */
    function flushDraftForContext(context, currentValue, isLeaving) {
        cancelDebounce();
        if (!context || !context.chapterId || !context.blockKey) {
            return;
        }
        const store = resolveDraftStore();
        if (!store) return;

        const draftKey = getRootDraftKey(context.chapterId, context.blockKey);
        const markerKey = getActiveMarkerKey(context.chapterId);
        if (!draftKey || !markerKey) return;

        const text = currentValue || '';

        if (text.trim().length === 0) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            removeRootMarkerIfMatching(context.chapterId, context.blockKey);
            return;
        }

        const currentGen = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        const acceptedGen = (draftKey && acceptedEditGenerations.get(draftKey)) || 0;
        if (acceptedGen > 0 && currentGen <= acceptedGen) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            if (isLeaving) {
                removeRootMarkerIfMatching(context.chapterId, context.blockKey);
            }
            return;
        }

        if (typeof store.save === 'function') {
            store.save(draftKey, text);
            if (isLeaving) {
                removeRootMarkerIfMatching(context.chapterId, context.blockKey);
            } else {
                store.save(markerKey, JSON.stringify({
                    type: 'root',
                    blockKey: context.blockKey
                }));
            }
        }
    }

    /**
     * Flushes currently active authoritative block draft to store.
     */
    function flushActiveDraft() {
        if (!authoritativeContext) return;
        const elements = getElements();
        const val = elements.input ? elements.input.value : '';
        flushDraftForContext(authoritativeContext, val, false);
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
     * Synchronizes bottom comments feed after successful root creation.
     * Root creation changes threadCount, commentCount, and root ordering/pagination,
     * so it always requests an authoritative page-0 reset.
     * Best-effort: errors are silently caught.
     */
    function synchronizeBottomFeed() {
        try {
            const bottomMod = resolveCommentsModule();
            if (bottomMod && typeof bottomMod.refreshFromPageZero === 'function') {
                bottomMod.refreshFromPageZero().catch(function () {});
            }
        } catch (_) {}
    }

    /**
     * Resolves the drawer module instance, preferring an injected module or falling back to the browser global.
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
     * Look up composer elements in the active document.
     *
     * @param {Document} doc
     * @returns {{form: Element|null, input: Element|null, statusEl: Element|null, submitBtn: Element|null}}
     */
    function getElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d || typeof d.getElementById !== 'function') {
            return { form: null, input: null, statusEl: null, submitBtn: null };
        }
        return {
            form: d.getElementById(COMPOSER_FORM_ID),
            input: d.getElementById(INPUT_ID),
            statusEl: d.getElementById(STATUS_ID),
            submitBtn: d.getElementById(SUBMIT_ID)
        };
    }

    /**
     * Extracts CSRF token and header name from document <meta> tags.
     *
     * @param {Document} doc
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
     * Updates the status/error message element safely via textContent.
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
     * Enables or disables composer interactive elements.
     *
     * @param {{input: Element|null, submitBtn: Element|null}} elements
     * @param {boolean} enabled
     */
    function setComposerEnabled(elements, enabled) {
        if (elements.input) {
            elements.input.disabled = !enabled;
        }
        if (elements.submitBtn) {
            elements.submitBtn.disabled = !enabled;
        }
    }

    /**
     * Checks dynamically whether the current execution context still owns the active composer UI.
     *
     * @param {number} mutationToken
     * @param {{chapterId: string, blockKey: string, contentVersion: number}} activeSnapshot
     * @returns {boolean}
     */
    function isMutationContextCurrent(mutationToken, activeSnapshot) {
        return mutationToken === currentMutationToken &&
            authoritativeContext !== null &&
            authoritativeContext.chapterId === activeSnapshot.chapterId &&
            authoritativeContext.blockKey === activeSnapshot.blockKey;
    }

    /**
     * Handles block discussion requested event: disables composer, clears state, and invalidates previous mutation ownership.
     * Synchronously flushes old block draft before invalidating context and clearing textarea.
     *
     * @param {*} [detail]
     */
    function handleDiscussionRequested(detail) {
        const oldContext = authoritativeContext;
        cancelDebounce();

        const elements = getElements();
        const oldVal = elements.input ? elements.input.value : '';

        if (oldContext) {
            flushDraftForContext(oldContext, oldVal, true);
        }

        currentMutationToken++;
        isSubmitting = false;
        authoritativeContext = null;

        setComposerEnabled(elements, false);
        setStatus(elements.statusEl, '', '');
        if (elements.input) {
            elements.input.value = '';
        }
    }

    /**
     * Handles block discussion closed event: preserves block draft, removes active marker, and disables composer.
     */
    function handleDiscussionClosed() {
        const oldContext = authoritativeContext;
        cancelDebounce();

        const elements = getElements();
        const oldVal = elements.input ? elements.input.value : '';

        if (oldContext) {
            flushDraftForContext(oldContext, oldVal, true);
        }

        currentMutationToken++;
        isSubmitting = false;
        authoritativeContext = null;

        setComposerEnabled(elements, false);
    }

    /**
     * Handles block discussion loaded event: receives authoritative context, enables composer,
     * and restores any saved root comment draft.
     *
     * @param {*} detail
     */
    function handleDiscussionLoaded(detail) {
        if (!detail || typeof detail !== 'object') {
            return;
        }

        const chapterId = typeof detail.chapterId === 'string' ? detail.chapterId.trim() : '';
        const blockKey = typeof detail.blockKey === 'string' ? detail.blockKey.trim() : '';
        const rawVersion = detail.contentVersion;
        let contentVersion = null;

        if (typeof rawVersion === 'number' && Number.isSafeInteger(rawVersion) && rawVersion > 0) {
            contentVersion = rawVersion;
        } else if (typeof rawVersion === 'string' && /^\d+$/.test(rawVersion.trim())) {
            const num = Number(rawVersion.trim());
            if (Number.isSafeInteger(num) && num > 0) {
                contentVersion = num;
            }
        }

        if (!chapterId || !blockKey || contentVersion === null) {
            return;
        }

        authoritativeContext = {
            chapterId: chapterId,
            contentVersion: contentVersion,
            blockKey: blockKey,
            threadCount: typeof detail.threadCount === 'number' ? detail.threadCount : 0
        };

        const elements = getElements();
        setComposerEnabled(elements, true);
        setStatus(elements.statusEl, '', '');

        // Draft restoration
        const store = resolveDraftStore();
        const draftKey = getRootDraftKey(chapterId, blockKey);
        const markerKey = getActiveMarkerKey(chapterId);

        let savedDraft = null;
        if (store && draftKey && typeof store.load === 'function') {
            savedDraft = store.load(draftKey);
        }

        if (typeof savedDraft === 'string' && savedDraft.trim().length > 0) {
            if (elements.input) {
                elements.input.value = savedDraft;
            }
            if (store && markerKey && typeof store.save === 'function') {
                store.save(markerKey, JSON.stringify({
                    type: 'root',
                    blockKey: blockKey
                }));
            }
        } else {
            if (elements.input) {
                elements.input.value = '';
            }
            removeRootMarkerIfMatching(chapterId, blockKey);
        }
    }

    /**
     * Handles chapter-changed event: flushes old draft, clears context, and resets composer.
     */
    function handleChapterChanged() {
        const oldContext = authoritativeContext;
        cancelDebounce();

        const elements = getElements();
        const oldVal = elements.input ? elements.input.value : '';

        if (oldContext) {
            flushDraftForContext(oldContext, oldVal, true);
        }

        currentMutationToken++;
        isSubmitting = false;
        authoritativeContext = null;

        setComposerEnabled(elements, false);
        if (elements.input) {
            elements.input.value = '';
        }
        setStatus(elements.statusEl, '', '');
    }

    /**
     * Handles textarea input event: validates text, updates marker, and debounces draft persistence ~400ms.
     */
    function handleInput() {
        cancelDebounce();
        if (!authoritativeContext || !authoritativeContext.chapterId || !authoritativeContext.blockKey) {
            return;
        }
        const elements = getElements();
        if (!elements.input) return;

        const chapterId = authoritativeContext.chapterId;
        const blockKey = authoritativeContext.blockKey;
        const draftKey = getRootDraftKey(chapterId, blockKey);
        const markerKey = getActiveMarkerKey(chapterId);
        const store = resolveDraftStore();
        if (!store || !draftKey || !markerKey) return;

        const currentVal = elements.input.value;

        if (currentVal.trim().length === 0) {
            if (typeof store.remove === 'function') {
                store.remove(draftKey);
            }
            removeRootMarkerIfMatching(chapterId, blockKey);
        } else {
            const nextGen = ++globalEditGeneration;
            keyEditGenerations.set(draftKey, nextGen);
            if (acceptedEditGenerations.has(draftKey)) {
                acceptedEditGenerations.delete(draftKey);
            }

            if (typeof store.save === 'function') {
                store.save(markerKey, JSON.stringify({ type: 'root', blockKey: blockKey }));
            }

            draftDebounceTimer = setTimeout(function () {
                draftDebounceTimer = null;
                if (authoritativeContext &&
                    authoritativeContext.chapterId === chapterId &&
                    authoritativeContext.blockKey === blockKey) {
                    const latestVal = elements.input ? elements.input.value : currentVal;
                    if (latestVal.trim().length > 0) {
                        if (typeof store.save === 'function') {
                            store.save(draftKey, latestVal);
                        }
                    } else {
                        if (typeof store.remove === 'function') {
                            store.remove(draftKey);
                        }
                        removeRootMarkerIfMatching(chapterId, blockKey);
                    }
                }
            }, DEBOUNCE_DELAY_MS);
        }
    }

    /**
     * Handles global novel drafts flush event (pagehide bridge).
     */
    function handleFlushDrafts() {
        flushActiveDraft();
    }

    /**
     * Handles composer form submission.
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

        const elements = getElements();
        if (!elements.form || !elements.input) {
            return;
        }

        // Authoritative context check
        if (!authoritativeContext || !authoritativeContext.chapterId || !authoritativeContext.blockKey) {
            return;
        }

        const rawBody = elements.input.value || '';
        if (!rawBody.trim()) {
            setStatus(elements.statusEl, 'Vui lòng nhập nội dung bình luận.', 'error');
            if (typeof elements.input.focus === 'function') {
                try {
                    elements.input.focus();
                } catch (_) {}
            }
            return;
        }

        cancelDebounce();

        const chapterId = authoritativeContext.chapterId;
        const blockKey = authoritativeContext.blockKey;
        const contentVersion = authoritativeContext.contentVersion;
        const draftKey = getRootDraftKey(chapterId, blockKey);
        const markerKey = getActiveMarkerKey(chapterId);
        const draftStore = resolveDraftStore();

        const currentGeneration = (draftKey && keyEditGenerations.get(draftKey)) || 0;
        const acceptedGeneration = (draftKey && acceptedEditGenerations.get(draftKey)) || 0;
        let submittedGeneration = currentGeneration;

        if (submittedGeneration <= acceptedGeneration || submittedGeneration === 0) {
            submittedGeneration = ++globalEditGeneration;
            keyEditGenerations.set(draftKey, submittedGeneration);
            acceptedEditGenerations.delete(draftKey);
        }

        if (draftStore && draftKey && typeof draftStore.save === 'function') {
            draftStore.save(draftKey, rawBody);
        }
        if (draftStore && markerKey && typeof draftStore.save === 'function') {
            draftStore.save(markerKey, JSON.stringify({ type: 'root', blockKey: blockKey }));
        }

        // CSRF Check
        const csrf = getCsrf();
        if (!csrf) {
            setStatus(elements.statusEl, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.', 'error');
            return;
        }

        // Begin submit in-flight state
        isSubmitting = true;
        const mutationToken = ++currentMutationToken;
        const activeSnapshot = {
            chapterId: chapterId,
            blockKey: blockKey,
            contentVersion: contentVersion,
            draftKey: draftKey,
            markerKey: markerKey,
            draftStore: draftStore,
            editGeneration: submittedGeneration
        };

        setComposerEnabled(elements, false);
        setStatus(elements.statusEl, 'Đang gửi...', 'info');

        const url = '/api/novel/chapters/' + encodeURIComponent(activeSnapshot.chapterId) + '/comments/inline';
        const payload = {
            body: rawBody,
            anchor: {
                contentVersion: activeSnapshot.contentVersion,
                blockKey: activeSnapshot.blockKey
            }
        };

        const fetchImpl = injectedFetch || (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchImpl) {
            isSubmitting = false;
            setComposerEnabled(elements, true);
            setStatus(elements.statusEl, 'Không thể gửi bình luận. Trình duyệt không hỗ trợ fetch.', 'error');
            return;
        }

        try {
            const headers = {
                'Accept': 'application/json',
                'Content-Type': 'application/json'
            };
            headers[csrf.headerName] = csrf.token;

            const res = await fetchImpl(url, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(payload)
            });

            if (res.status === 201) {
                const data = await res.json().catch(function () { return null; });
                const validCommentId = data && typeof data.commentId === 'string' && data.commentId.trim().length > 0;

                if (!validCommentId) {
                    if (isMutationContextCurrent(mutationToken, activeSnapshot)) {
                        isSubmitting = false;
                        setComposerEnabled(elements, true);
                        setStatus(elements.statusEl, 'Không thể gửi bình luận. Phản hồi máy chủ không hợp lệ.', 'error');
                    }
                    return;
                }

                // Dynamic re-check of mutation ownership after await res.json()
                if (isMutationContextCurrent(mutationToken, activeSnapshot)) {
                    const latestGeneration = (activeSnapshot.draftKey && keyEditGenerations.get(activeSnapshot.draftKey)) || 0;
                    if (latestGeneration <= activeSnapshot.editGeneration) {
                        if (activeSnapshot.draftKey) {
                            acceptedEditGenerations.set(activeSnapshot.draftKey, activeSnapshot.editGeneration);
                        }
                        if (activeSnapshot.draftStore && activeSnapshot.draftKey && typeof activeSnapshot.draftStore.remove === 'function') {
                            activeSnapshot.draftStore.remove(activeSnapshot.draftKey);
                        }
                    }
                    removeRootMarkerIfMatching(activeSnapshot.chapterId, activeSnapshot.blockKey);

                    if (elements.input) {
                        elements.input.value = '';
                    }
                    setStatus(elements.statusEl, '', '');
                    isSubmitting = false;
                    setComposerEnabled(elements, true);

                    // Trigger drawer refresh for active discussion
                    const drawer = resolveDrawerModule();
                    if (drawer && typeof drawer.refreshActiveDiscussion === 'function') {
                        try {
                            drawer.refreshActiveDiscussion().catch(function () {});
                        } catch (_) {}
                    }

                    // Trigger indicator refresh for active discussion
                    const indicators = injectedIndicators ||
                        (typeof window !== 'undefined' ? (window.NovelChapterCommentIndicators || (window.KiemLai && window.KiemLai.NovelChapterCommentIndicators)) : null);
                    if (indicators && typeof indicators.refreshChapterIndicators === 'function') {
                        try {
                            indicators.refreshChapterIndicators().catch(function () {});
                        } catch (_) {}
                    }

                    // Secondary Bottom feed synchronization: Root create changes ordering and count -> refreshFromPageZero()
                    synchronizeBottomFeed();
                } else {
                    // Stale success handling
                    const latestGeneration = (activeSnapshot.draftKey && keyEditGenerations.get(activeSnapshot.draftKey)) || 0;
                    if (latestGeneration <= activeSnapshot.editGeneration) {
                        if (activeSnapshot.draftKey) {
                            acceptedEditGenerations.set(activeSnapshot.draftKey, activeSnapshot.editGeneration);
                        }
                        if (activeSnapshot.draftStore && activeSnapshot.draftKey && typeof activeSnapshot.draftStore.remove === 'function') {
                            activeSnapshot.draftStore.remove(activeSnapshot.draftKey);
                        }
                    }
                }

                return;
            }

            if (!isMutationContextCurrent(mutationToken, activeSnapshot)) {
                return;
            }

            isSubmitting = false;

            if (res.status === 409) {
                // Version conflict: preserve draft, trigger drawer refresh to fetch current contentVersion
                setStatus(elements.statusEl, 'Nội dung chương đã thay đổi. Thảo luận đã được làm mới; vui lòng kiểm tra và gửi lại.', 'error');

                const drawer = resolveDrawerModule();
                if (drawer && typeof drawer.refreshActiveDiscussion === 'function') {
                    try {
                        drawer.refreshActiveDiscussion().catch(function () {});
                    } catch (_) {}
                }
                return;
            }

            if (res.status === 401 || res.status === 403) {
                setComposerEnabled(elements, true);
                setStatus(elements.statusEl, 'Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.', 'error');
                return;
            }

            if (res.status === 400) {
                setComposerEnabled(elements, true);
                setStatus(elements.statusEl, 'Nội dung bình luận không hợp lệ.', 'error');
                return;
            }

            if (res.status === 404) {
                setComposerEnabled(elements, false);
                setStatus(elements.statusEl, 'Đoạn văn này không còn khả dụng để thảo luận.', 'error');
                return;
            }

            // 500 or any other HTTP error
            setComposerEnabled(elements, true);
            setStatus(elements.statusEl, 'Không thể gửi bình luận. Vui lòng thử lại.', 'error');

        } catch (err) {
            if (!isMutationContextCurrent(mutationToken, activeSnapshot)) {
                return;
            }
            isSubmitting = false;
            setComposerEnabled(elements, true);
            setStatus(elements.statusEl, 'Không thể gửi bình luận. Vui lòng thử lại.', 'error');
        }
    }

    function onDiscussionRequested(evt) {
        handleDiscussionRequested(evt ? evt.detail : null);
    }

    function onDiscussionLoaded(evt) {
        handleDiscussionLoaded(evt ? evt.detail : null);
    }

    function onDiscussionClosed() {
        handleDiscussionClosed();
    }

    function onChapterChanged() {
        handleChapterChanged();
    }

    function onFlushDrafts() {
        handleFlushDrafts();
    }

    /**
     * Initializes the root comment composer module.
     *
     * @param {Document} [doc]
     * @param {Object} [options]
     */
    function initReaderBlockDiscussionComposer(doc, options) {
        if (isInitialized) {
            return;
        }

        currentDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (options) {
            if (options.fetchFn) injectedFetch = options.fetchFn;
            if (options.drawerModule) injectedDrawer = options.drawerModule;
            if (options.indicatorsModule) injectedIndicators = options.indicatorsModule;
            if (options.commentsModule || options.bottomCommentsModule) {
                injectedCommentsModule = options.commentsModule || options.bottomCommentsModule;
            }
            if (options.draftAdapter) injectedDraftAdapter = options.draftAdapter;
            if (options.draftStore) injectedDraftStore = options.draftStore;
        }

        if (!currentDoc) {
            return;
        }

        const elements = getElements(currentDoc);
        // If composer markup is absent (e.g. anonymous user), safely return without binding
        if (!elements.form) {
            isInitialized = true;
            return;
        }

        // Initially disabled until authoritative response loads
        setComposerEnabled(elements, false);

        elements.form.addEventListener('submit', handleSubmit);
        if (elements.input) {
            elements.input.addEventListener('input', handleInput);
            boundInput = elements.input;
        }

        boundDoc = currentDoc;
        boundForm = elements.form;
        currentDoc.addEventListener(EVENT_DISCUSSION_REQUESTED, onDiscussionRequested);
        currentDoc.addEventListener(EVENT_DISCUSSION_LOADED, onDiscussionLoaded);
        currentDoc.addEventListener(EVENT_DISCUSSION_CLOSED, onDiscussionClosed);
        currentDoc.addEventListener(EVENT_CHAPTER_CHANGED, onChapterChanged);
        currentDoc.addEventListener(EVENT_FLUSH_DRAFTS, onFlushDrafts);

        isInitialized = true;
    }

    /**
     * Resets module state (intended for isolated test suites).
     */
    function resetComposerState() {
        cancelDebounce();
        isInitialized = false;
        isSubmitting = false;
        authoritativeContext = null;
        currentMutationToken = 0;
        injectedFetch = null;
        injectedDrawer = null;
        injectedIndicators = null;
        injectedCommentsModule = null;
        injectedDraftAdapter = null;
        injectedDraftStore = null;

        if (boundInput) {
            try {
                boundInput.removeEventListener('input', handleInput);
            } catch (_) {}
            boundInput = null;
        }

        if (boundForm) {
            try {
                boundForm.removeEventListener('submit', handleSubmit);
            } catch (_) {}
            boundForm = null;
        }

        if (boundDoc) {
            try {
                boundDoc.removeEventListener(EVENT_DISCUSSION_REQUESTED, onDiscussionRequested);
                boundDoc.removeEventListener(EVENT_DISCUSSION_LOADED, onDiscussionLoaded);
                boundDoc.removeEventListener(EVENT_DISCUSSION_CLOSED, onDiscussionClosed);
                boundDoc.removeEventListener(EVENT_CHAPTER_CHANGED, onChapterChanged);
                boundDoc.removeEventListener(EVENT_FLUSH_DRAFTS, onFlushDrafts);
            } catch (_) {}
            boundDoc = null;
        }

        currentDoc = null;
    }

    // Auto-bootstrap in browser environment
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderBlockDiscussionComposer(document);
            });
        } else {
            initReaderBlockDiscussionComposer(document);
        }
    }

    return {
        COMPOSER_FORM_ID,
        INPUT_ID,
        STATUS_ID,
        SUBMIT_ID,
        EVENT_DISCUSSION_REQUESTED,
        EVENT_DISCUSSION_LOADED,
        EVENT_DISCUSSION_CLOSED,
        EVENT_CHAPTER_CHANGED,
        EVENT_FLUSH_DRAFTS,
        init: initReaderBlockDiscussionComposer,
        initReaderBlockDiscussionComposer,
        destroy: resetComposerState,
        resetComposerState,
        handleSubmit,
        handleDiscussionRequested,
        handleDiscussionLoaded,
        handleDiscussionClosed,
        handleChapterChanged,
        handleFlushDrafts,
        flushActiveDraft,
        isMutationContextCurrent,
        resolveDrawerModule,
        resolveCommentsModule,
        getAuthoritativeContext: function () { return authoritativeContext; },
        isSubmitting: function () { return isSubmitting; },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setDrawerImplementation: function (d) { injectedDrawer = d; },
        setIndicatorsImplementation: function (ind) { injectedIndicators = ind; },
        setCommentsModule: function (mod) { injectedCommentsModule = mod; },
        setCommentsModuleImplementation: function (mod) { injectedCommentsModule = mod; },
        setDraftAdapter: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftAdapterImplementation: function (adapter) { injectedDraftAdapter = adapter; },
        setDraftStore: function (store) { injectedDraftStore = store; },
        setDraftStoreImplementation: function (store) { injectedDraftStore = store; },
        getKeyEditGeneration: function (key) { return keyEditGenerations.get(key) || 0; },
        getAcceptedEditGeneration: function (key) { return acceptedEditGenerations.get(key) || 0; },
        removeRootMarkerIfMatching: removeRootMarkerIfMatching
    };
});
