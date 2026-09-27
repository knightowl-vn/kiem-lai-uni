/**
 * KiemLai Universe — Bottom Chapter Root Comment Composer (MS-05E5H2F1 / UX-DRAFT-01D1)
 *
 * Responsibilities:
 * - Owns the whole-chapter root comment composer UX at the bottom of the chapter reader.
 * - Integrates EphemeralDraftStore for ephemeral unsent draft persistence across reloads.
 * - Restores saved draft on initialization if textarea is empty.
 * - Debounces draft auto-saving (~400ms) on user input; preserves exact raw text.
 * - Listens for 'kiemlai:novel-comment-drafts-flush' to synchronously flush current draft on pagehide.
 * - On 'kiemlai:chapter-changed': synchronously saves old chapter's draft before updating
 *   chapter context, clearing textarea, and restoring new chapter's draft.
 * - Makes valid detail.chapterId from 'kiemlai:chapter-changed' authoritative context for submissions.
 * - Prevents ABA draft deletion: captures user-edit generation snapshot at submit so stale HTTP 201
 *   only removes storage if no newer user edit exists for that exact draft key.
 * - Client-side validation (non-blank body) before submitting.
 * - Extracts CSRF token from document head meta tags.
 * - Submits POST /api/novel/chapters/{chapterId}/comments with { "body": "..." } (unanchored).
 * - Enforces single-flight submission while POST request is in-flight.
 * - On 201 Created: removes stored draft, clears draft textarea, and triggers authoritative
 *   feed refresh via reader-chapter-comments.js refreshFromPageZero().
 * - If feed refresh fails after 201 Created, preserves cleared draft and presents a clear status
 *   ("Bình luận đã được gửi, nhưng chưa thể tải lại danh sách.") without retrying POST.
 * - On POST failure: preserves textarea draft and stored draft, displaying appropriate error.
 * - Race safety: stale responses from earlier chapters cannot mutate active UI, but a stale 201
 *   cleans up the submitted chapter's stored draft if no newer edit exists.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelChapterCommentComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelChapterCommentComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const COMPOSER_FORM_ID = 'novelChapterCommentComposer';
    const INPUT_ID = 'novelChapterCommentComposerInput';
    const STATUS_ID = 'novelChapterCommentComposerStatus';
    const SUBMIT_ID = 'novelChapterCommentComposerSubmit';
    const COMMENTS_SECTION_ID = 'novelChapterComments';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';
    const EVENT_FLUSH_DRAFTS = 'kiemlai:novel-comment-drafts-flush';
    const DEBOUNCE_DELAY_MS = 400;

    // Module-lifetime state: survives destroy/re-init across composer instances to prevent ABA deletion and resurrection
    let globalEditGeneration = 0;
    const keyEditGenerations = new Map();
    const acceptedEditGenerations = new Map();

    // Instance State
    let currentDoc = null;
    let boundDoc = null;
    let boundForm = null;
    let boundInput = null;
    let boundInputListener = null;
    let boundFlushListener = null;
    let isSubmitting = false;
    let currentMutationToken = 0;
    let activeDraftChapterId = null;
    let authoritativeEventChapterId = null;
    let draftDebounceTimer = null;

    let injectedFetch = null;
    let injectedCommentsModule = null;
    let injectedChapterIdResolver = null;
    let injectedDraftStore = null;
    let injectedDraftAdapter = null;

    /**
     * Resolves the Novel Comment Drafts adapter module.
     *
     * @returns {Object|null}
     */
    function resolveDraftAdapter() {
        if (injectedDraftAdapter) {
            return injectedDraftAdapter;
        }
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
        if (injectedDraftStore) {
            return injectedDraftStore;
        }
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
     * Resolves the canonical Bottom Root Draft Key.
     *
     * @param {string} chapterId
     * @returns {string|null}
     */
    function getRootDraftKey(chapterId) {
        const adapter = resolveDraftAdapter();
        if (adapter && typeof adapter.getChapterRootDraftKey === 'function') {
            return adapter.getChapterRootDraftKey(chapterId);
        }
        if (typeof chapterId !== 'string' || !chapterId.trim()) {
            return null;
        }
        return 'kiemlai:draft:novel-comment:' + encodeURIComponent(chapterId.trim()) + ':root';
    }

    /**
     * Resolves active chapter ID, prioritizing authoritative chapter-changed event context
     * over lagging DOM data-chapter-id attributes when present.
     *
     * @param {Document} [doc]
     * @returns {string|null}
     */
    function getActiveChapterId(doc) {
        if (authoritativeEventChapterId) {
            return authoritativeEventChapterId;
        }
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const domId = resolveChapterId(d);
        return domId || activeDraftChapterId;
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
     * Synchronously flushes the current textarea draft to storage.
     * Preserves exact raw text without trimming.
     * Does NOT increment user-edit generation.
     * Prevents resurrection of already accepted draft generations.
     *
     * @param {string} chapterId
     */
    function flushDraft(chapterId) {
        cancelDebounce();
        if (!chapterId) {
            return;
        }
        const elements = getElements();
        if (!elements.input) {
            return;
        }
        const key = getRootDraftKey(chapterId);
        if (!key) {
            return;
        }
        const store = resolveDraftStore();
        if (!store || typeof store.save !== 'function') {
            return;
        }

        const currentEditGen = (key && keyEditGenerations.get(key)) || 0;
        const acceptedGen = (key && acceptedEditGenerations.get(key)) || 0;
        if (acceptedGen > 0 && currentEditGen <= acceptedGen) {
            if (typeof store.remove === 'function') {
                store.remove(key);
            }
            return;
        }

        const rawValue = elements.input.value;
        store.save(key, rawValue);
    }

    /**
     * Restores draft from storage into textarea if textarea is currently empty.
     *
     * @param {string} chapterId
     */
    function restoreDraft(chapterId) {
        if (!chapterId) {
            return;
        }
        const elements = getElements();
        if (!elements.input) {
            return;
        }
        // Restore ONLY when textarea.value is EXACTLY empty
        if (elements.input.value !== '') {
            return;
        }
        const key = getRootDraftKey(chapterId);
        if (!key) {
            return;
        }
        const store = resolveDraftStore();
        if (!store || typeof store.load !== 'function') {
            return;
        }
        const saved = store.load(key);
        if (typeof saved === 'string' && saved.length > 0) {
            elements.input.value = saved;
        }
    }

    /**
     * Removes draft from storage for the specified chapter.
     *
     * @param {string} chapterId
     */
    function removeDraft(chapterId) {
        if (!chapterId) {
            return;
        }
        const key = getRootDraftKey(chapterId);
        if (!key) {
            return;
        }
        const store = resolveDraftStore();
        if (store && typeof store.remove === 'function') {
            store.remove(key);
        }
    }

    /**
     * Textarea input event listener: increments user-edit generation for the chapter draft key
     * and sets 400ms debounce timer for storage persistence.
     */
    function handleInput() {
        cancelDebounce();
        const doc = (boundForm && boundForm.ownerDocument) || currentDoc || (typeof document !== 'undefined' ? document : null);
        const chapterId = getActiveChapterId(doc);
        if (chapterId) {
            const key = getRootDraftKey(chapterId);
            if (key) {
                const nextGen = ++globalEditGeneration;
                keyEditGenerations.set(key, nextGen);
                if (acceptedEditGenerations.has(key)) {
                    acceptedEditGenerations.delete(key);
                }
            }
        }
        draftDebounceTimer = setTimeout(function () {
            draftDebounceTimer = null;
            const currentChapter = getActiveChapterId(doc);
            if (!currentChapter) {
                return;
            }
            flushDraft(currentChapter);
        }, DEBOUNCE_DELAY_MS);
    }

    /**
     * Synchronous flush listener triggered by Novel Draft Adapter pagehide bridge.
     */
    function handleFlushDrafts() {
        const doc = (boundForm && boundForm.ownerDocument) || currentDoc || (typeof document !== 'undefined' ? document : null);
        const chapterId = activeDraftChapterId || getActiveChapterId(doc);
        if (chapterId) {
            flushDraft(chapterId);
        }
    }

    /**
     * Resolves the reader chapter comments feed module.
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
        return null;
    }

    /**
     * DOM element lookup helper.
     *
     * @param {Document} [doc]
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
     * Resolves the current chapter ID from authoritative DOM state.
     *
     * @param {Document} [doc]
     * @returns {string|null}
     */
    function resolveChapterId(doc) {
        if (typeof injectedChapterIdResolver === 'function') {
            return injectedChapterIdResolver(doc);
        }
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return null;
        }

        const section = (typeof d.getElementById === 'function')
            ? d.getElementById(COMMENTS_SECTION_ID)
            : null;

        if (section) {
            const rawId = (typeof section.getAttribute === 'function' ? section.getAttribute('data-chapter-id') : null) ||
                (section.dataset && section.dataset.chapterId);
            if (rawId && String(rawId).trim()) {
                return String(rawId).trim();
            }
        }

        if (typeof d.querySelector === 'function') {
            const bodyEl = d.querySelector('.novel-reader-chapter-body[data-chapter-id]');
            if (bodyEl) {
                const rawId = (typeof bodyEl.getAttribute === 'function' ? bodyEl.getAttribute('data-chapter-id') : null) ||
                    (bodyEl.dataset && bodyEl.dataset.chapterId);
                if (rawId && String(rawId).trim()) {
                    return String(rawId).trim();
                }
            }
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

        return { token: token, headerName: headerName };
    }

    /**
     * Updates status message and styling safely via textContent.
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

        const doc = elements.form.ownerDocument || currentDoc || (typeof document !== 'undefined' ? document : null);
        const chapterId = getActiveChapterId(doc);
        if (!chapterId) {
            return;
        }

        const rawBody = elements.input.value || '';
        const submittedBody = rawBody.trim();
        if (!submittedBody) {
            setStatus(elements.statusEl, 'Vui lòng nhập nội dung bình luận.', 'error');
            if (typeof elements.input.focus === 'function') {
                try {
                    elements.input.focus();
                } catch (_) {}
            }
            return;
        }

        const csrf = getCsrf(doc);
        if (!csrf) {
            setStatus(elements.statusEl, 'Không thể xác thực yêu cầu bảo mật (CSRF). Vui lòng tải lại trang.', 'error');
            return;
        }

        isSubmitting = true;
        const mutationToken = ++currentMutationToken;
        const submittedChapterId = chapterId;
        const submittedDraftKey = getRootDraftKey(submittedChapterId);
        const submittedDraftStore = resolveDraftStore();
        let submittedEditGeneration = (submittedDraftKey && keyEditGenerations.get(submittedDraftKey)) || 0;
        if (submittedDraftKey && submittedEditGeneration === 0) {
            submittedEditGeneration = ++globalEditGeneration;
            keyEditGenerations.set(submittedDraftKey, submittedEditGeneration);
        }

        // Synchronously save exact raw text to draft store before sending POST
        cancelDebounce();
        if (submittedDraftStore && submittedDraftKey) {
            submittedDraftStore.save(submittedDraftKey, rawBody);
        }

        elements.input.disabled = true;
        if (elements.submitBtn) {
            elements.submitBtn.disabled = true;
        }
        setStatus(elements.statusEl, 'Đang gửi...', 'info');

        const url = '/api/novel/chapters/' + encodeURIComponent(submittedChapterId) + '/comments';
        const fetchImpl = injectedFetch || (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);

        if (!fetchImpl) {
            isSubmitting = false;
            elements.input.disabled = false;
            if (elements.submitBtn) {
                elements.submitBtn.disabled = false;
            }
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
                body: JSON.stringify({ body: submittedBody })
            });

            const isCurrent = (mutationToken === currentMutationToken && getActiveChapterId(doc) === submittedChapterId);

            if (!isCurrent) {
                // Stale response handling across chapter transition or ABA re-initialization
                if (res && res.status === 201) {
                    // Mutation for submittedChapterId succeeded on server.
                    // Clean up stored draft ONLY IF no newer user edit exists for that submitted draft key:
                    const latestGeneration = (submittedDraftKey && keyEditGenerations.get(submittedDraftKey)) || 0;
                    if (latestGeneration <= submittedEditGeneration) {
                        if (submittedDraftKey) {
                            acceptedEditGenerations.set(submittedDraftKey, submittedEditGeneration);
                        }
                        if (submittedDraftStore && submittedDraftKey && typeof submittedDraftStore.remove === 'function') {
                            submittedDraftStore.remove(submittedDraftKey);
                        }
                    }
                }
                return;
            }

            if (res && res.status === 201) {
                // Remove stored draft on successful creation if no newer user edit occurred
                const latestGeneration = (submittedDraftKey && keyEditGenerations.get(submittedDraftKey)) || 0;
                if (latestGeneration <= submittedEditGeneration) {
                    if (submittedDraftKey) {
                        acceptedEditGenerations.set(submittedDraftKey, submittedEditGeneration);
                    }
                    if (submittedDraftStore && submittedDraftKey && typeof submittedDraftStore.remove === 'function') {
                        submittedDraftStore.remove(submittedDraftKey);
                    }
                }

                // Clear textarea
                elements.input.value = '';
                elements.input.disabled = false;
                if (elements.submitBtn) {
                    elements.submitBtn.disabled = false;
                }
                isSubmitting = false;
                setStatus(elements.statusEl, '', '');

                // Authoritative feed refresh
                const commentsMod = resolveCommentsModule();
                if (commentsMod && typeof commentsMod.refreshFromPageZero === 'function') {
                    try {
                        await commentsMod.refreshFromPageZero();
                    } catch (_) {
                        if (mutationToken === currentMutationToken && getActiveChapterId(doc) === submittedChapterId) {
                            setStatus(elements.statusEl, 'Bình luận đã được gửi, nhưng chưa thể tải lại danh sách.', 'error');
                        }
                    }
                }
                return;
            }

            // POST failed (non-201)
            isSubmitting = false;
            elements.input.disabled = false;
            if (elements.submitBtn) {
                elements.submitBtn.disabled = false;
            }

            if (res && (res.status === 401 || res.status === 403)) {
                setStatus(elements.statusEl, 'Phiên đăng nhập đã hết hạn hoặc bạn không có quyền bình luận.', 'error');
            } else {
                setStatus(elements.statusEl, 'Không thể gửi bình luận. Vui lòng thử lại.', 'error');
            }
        } catch (_) {
            if (mutationToken !== currentMutationToken || getActiveChapterId(doc) !== submittedChapterId) {
                return;
            }
            isSubmitting = false;
            elements.input.disabled = false;
            if (elements.submitBtn) {
                elements.submitBtn.disabled = false;
            }
            setStatus(elements.statusEl, 'Không thể gửi bình luận. Vui lòng thử lại.', 'error');
        }
    }

    /**
     * Handles chapter transition event.
     * Synchronously saves old chapter draft before updating context,
     * clearing stale text, and restoring new chapter draft.
     * Valid detail.chapterId becomes authoritative context.
     *
     * @param {CustomEvent} [evt]
     */
    function handleChapterChanged(evt) {
        const doc = (boundForm && boundForm.ownerDocument) || currentDoc || (typeof document !== 'undefined' ? document : null);

        // 1. snapshot oldChapterId = activeDraftChapterId
        const oldChapterId = activeDraftChapterId;

        // 2. cancel debounce
        cancelDebounce();

        // 3. synchronously flush textarea under oldChapterId
        if (oldChapterId) {
            flushDraft(oldChapterId);
        }

        // 4. invalidate mutation state
        currentMutationToken++;
        isSubmitting = false;

        // Re-enable inputs if disabled and clear status
        const elements = getElements(doc);
        if (elements.input) {
            elements.input.disabled = false;
        }
        if (elements.submitBtn) {
            elements.submitBtn.disabled = false;
        }
        setStatus(elements.statusEl, '', '');

        // 5. derive NEW chapter:
        //    - valid evt.detail.chapterId first;
        //    - otherwise fallback resolveChapterId(doc);
        let newChapterId = null;
        let fromEventDetail = false;
        if (evt && evt.detail && typeof evt.detail === 'object') {
            const detailId = evt.detail.chapterId;
            if (typeof detailId === 'string' && detailId.trim()) {
                newChapterId = detailId.trim();
                fromEventDetail = true;
            }
        }
        if (!newChapterId) {
            newChapterId = resolveChapterId(doc);
            fromEventDetail = false;
        }

        // 6. update authoritativeEventChapterId / activeDraftChapterId
        if (fromEventDetail) {
            authoritativeEventChapterId = newChapterId;
        } else {
            authoritativeEventChapterId = null;
        }
        activeDraftChapterId = newChapterId;

        // 7. clear old textarea
        if (elements.input) {
            elements.input.value = '';
        }

        // 8. restore new chapter draft
        if (activeDraftChapterId) {
            restoreDraft(activeDraftChapterId);
        }
    }

    /**
     * Resets module state and detaches listeners.
     */
    function resetComposerState() {
        cancelDebounce();
        const oldChapterId = activeDraftChapterId || (currentDoc && getActiveChapterId(currentDoc));
        if (oldChapterId) {
            flushDraft(oldChapterId);
        }

        const elements = getElements(currentDoc);
        if (elements.input) {
            elements.input.disabled = false;
        }
        if (elements.submitBtn) {
            elements.submitBtn.disabled = false;
        }
        setStatus(elements.statusEl, '', '');

        isSubmitting = false;
        currentMutationToken++;
        injectedFetch = null;
        injectedCommentsModule = null;
        injectedChapterIdResolver = null;
        injectedDraftStore = null;
        injectedDraftAdapter = null;

        if (boundInput && boundInputListener) {
            try {
                boundInput.removeEventListener('input', boundInputListener);
            } catch (_) {}
        }
        boundInput = null;
        boundInputListener = null;

        if (boundForm) {
            try {
                boundForm.removeEventListener('submit', handleSubmit);
            } catch (_) {}
            boundForm = null;
        }

        if (boundDoc) {
            if (boundFlushListener) {
                try {
                    boundDoc.removeEventListener(EVENT_FLUSH_DRAFTS, boundFlushListener);
                } catch (_) {}
            }
            try {
                boundDoc.removeEventListener(EVENT_CHAPTER_CHANGED, handleChapterChanged);
            } catch (_) {}
            boundDoc = null;
        }
        boundFlushListener = null;
        activeDraftChapterId = null;
        authoritativeEventChapterId = null;
        currentDoc = null;
    }

    /**
     * Initializes the chapter comment composer module.
     *
     * @param {Document} [targetDoc]
     * @param {Object} [options]
     * @returns {Object}
     */
    function initReaderChapterCommentComposer(targetDoc, options) {
        const doc = targetDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) {
            return null;
        }

        resetComposerState();
        currentDoc = doc;
        boundDoc = doc;

        if (options) {
            if (typeof options.fetch === 'function') {
                injectedFetch = options.fetch;
            }
            if (options.commentsModule) {
                injectedCommentsModule = options.commentsModule;
            }
            if (typeof options.resolveChapterId === 'function') {
                injectedChapterIdResolver = options.resolveChapterId;
            }
            if (options.draftStore) {
                injectedDraftStore = options.draftStore;
            }
            if (options.draftAdapter) {
                injectedDraftAdapter = options.draftAdapter;
            }
        }

        activeDraftChapterId = resolveChapterId(doc);
        authoritativeEventChapterId = null;

        const elements = getElements(doc);
        if (elements.input) {
            elements.input.disabled = false;
        }
        if (elements.submitBtn) {
            elements.submitBtn.disabled = false;
        }
        setStatus(elements.statusEl, '', '');
        if (elements.form && typeof elements.form.addEventListener === 'function') {
            boundForm = elements.form;
            boundForm.addEventListener('submit', handleSubmit);
        }

        if (elements.input && typeof elements.input.addEventListener === 'function') {
            boundInput = elements.input;
            boundInputListener = handleInput;
            boundInput.addEventListener('input', boundInputListener);
        }

        if (typeof doc.addEventListener === 'function') {
            doc.addEventListener(EVENT_CHAPTER_CHANGED, handleChapterChanged);
            boundFlushListener = handleFlushDrafts;
            doc.addEventListener(EVENT_FLUSH_DRAFTS, boundFlushListener);
        }

        // Restore draft for current chapter if available
        if (activeDraftChapterId) {
            restoreDraft(activeDraftChapterId);
        }

        return {
            handleSubmit: handleSubmit,
            handleChapterChanged: handleChapterChanged,
            handleFlushDrafts: handleFlushDrafts,
            restoreDraft: restoreDraft,
            flushDraft: flushDraft,
            destroy: resetComposerState
        };
    }

    // Auto-bootstrap in browser environment
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderChapterCommentComposer(document);
            });
        } else {
            initReaderChapterCommentComposer(document);
        }
    }

    return {
        COMPOSER_FORM_ID: COMPOSER_FORM_ID,
        INPUT_ID: INPUT_ID,
        STATUS_ID: STATUS_ID,
        SUBMIT_ID: SUBMIT_ID,
        COMMENTS_SECTION_ID: COMMENTS_SECTION_ID,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        EVENT_FLUSH_DRAFTS: EVENT_FLUSH_DRAFTS,
        init: initReaderChapterCommentComposer,
        destroy: resetComposerState,
        handleSubmit: handleSubmit,
        handleChapterChanged: handleChapterChanged,
        handleFlushDrafts: handleFlushDrafts,
        restoreDraft: restoreDraft,
        flushDraft: flushDraft,
        isSubmitting: function () { return isSubmitting; },
        resolveChapterId: resolveChapterId,
        getActiveChapterId: getActiveChapterId,
        getActiveDraftChapterId: function () { return activeDraftChapterId; },
        getAuthoritativeEventChapterId: function () { return authoritativeEventChapterId; },
        getKeyEditGeneration: function (key) { return keyEditGenerations.get(key) || 0; },
        getAcceptedEditGeneration: function (key) { return acceptedEditGenerations.get(key) || 0; },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setCommentsModuleImplementation: function (mod) { injectedCommentsModule = mod; },
        setChapterIdResolver: function (fn) { injectedChapterIdResolver = fn; },
        setDraftStoreImplementation: function (store) { injectedDraftStore = store; },
        setDraftAdapterImplementation: function (adapter) { injectedDraftAdapter = adapter; }
    };
});
