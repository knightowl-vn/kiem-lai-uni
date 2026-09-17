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

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let boundForm = null;
    let isInitialized = false;
    let isSubmitting = false;
    let authoritativeContext = null;
    let currentMutationToken = 0;

    let injectedFetch = null;
    let injectedDrawer = null;
    let injectedIndicators = null;

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
     */
    function handleDiscussionRequested() {
        authoritativeContext = null;
        currentMutationToken++;
        isSubmitting = false;

        const elements = getElements();
        setComposerEnabled(elements, false);
        setStatus(elements.statusEl, '', '');
        if (elements.input) {
            elements.input.value = '';
        }
    }

    /**
     * Handles block discussion closed event: invalidates context, mutation ownership, and disables composer.
     */
    function handleDiscussionClosed() {
        authoritativeContext = null;
        currentMutationToken++;
        isSubmitting = false;

        const elements = getElements();
        setComposerEnabled(elements, false);
    }

    /**
     * Handles block discussion loaded event: receives authoritative context and enables composer.
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
    }

    /**
     * Handles chapter-changed event: invalidates context and clears draft.
     */
    function handleChapterChanged() {
        authoritativeContext = null;
        currentMutationToken++;
        isSubmitting = false;

        const elements = getElements();
        setComposerEnabled(elements, false);
        if (elements.input) {
            elements.input.value = '';
        }
        setStatus(elements.statusEl, '', '');
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
            chapterId: authoritativeContext.chapterId,
            blockKey: authoritativeContext.blockKey,
            contentVersion: authoritativeContext.contentVersion
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
                }

                // Trigger indicator refresh if user is still on the same chapter
                const isSameChapter = authoritativeContext && authoritativeContext.chapterId === activeSnapshot.chapterId;
                if (isSameChapter) {
                    const indicators = injectedIndicators ||
                        (typeof window !== 'undefined' ? (window.NovelChapterCommentIndicators || (window.KiemLai && window.KiemLai.NovelChapterCommentIndicators)) : null);
                    if (indicators && typeof indicators.refreshChapterIndicators === 'function') {
                        try {
                            indicators.refreshChapterIndicators().catch(function () {});
                        } catch (_) {}
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

        boundDoc = currentDoc;
        boundForm = elements.form;
        currentDoc.addEventListener(EVENT_DISCUSSION_REQUESTED, handleDiscussionRequested);
        currentDoc.addEventListener(EVENT_DISCUSSION_LOADED, onDiscussionLoaded);
        currentDoc.addEventListener(EVENT_DISCUSSION_CLOSED, handleDiscussionClosed);
        currentDoc.addEventListener(EVENT_CHAPTER_CHANGED, handleChapterChanged);

        isInitialized = true;
    }

    function onDiscussionLoaded(evt) {
        handleDiscussionLoaded(evt ? evt.detail : null);
    }

    /**
     * Resets module state (intended for isolated test suites).
     */
    function resetComposerState() {
        isInitialized = false;
        isSubmitting = false;
        authoritativeContext = null;
        currentMutationToken = 0;
        injectedFetch = null;
        injectedDrawer = null;
        injectedIndicators = null;

        if (boundForm) {
            try {
                boundForm.removeEventListener('submit', handleSubmit);
            } catch (_) {}
            boundForm = null;
        }

        if (boundDoc) {
            try {
                boundDoc.removeEventListener(EVENT_DISCUSSION_REQUESTED, handleDiscussionRequested);
                boundDoc.removeEventListener(EVENT_DISCUSSION_LOADED, onDiscussionLoaded);
                boundDoc.removeEventListener(EVENT_DISCUSSION_CLOSED, handleDiscussionClosed);
                boundDoc.removeEventListener(EVENT_CHAPTER_CHANGED, handleChapterChanged);
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
        initReaderBlockDiscussionComposer,
        resetComposerState,
        handleSubmit,
        handleDiscussionRequested,
        handleDiscussionLoaded,
        handleDiscussionClosed,
        handleChapterChanged,
        isMutationContextCurrent,
        resolveDrawerModule,
        getAuthoritativeContext: function () { return authoritativeContext; },
        isSubmitting: function () { return isSubmitting; },
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setDrawerImplementation: function (d) { injectedDrawer = d; },
        setIndicatorsImplementation: function (ind) { injectedIndicators = ind; }
    };
});
