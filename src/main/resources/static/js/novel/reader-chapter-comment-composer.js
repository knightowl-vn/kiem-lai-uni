/**
 * KiemLai Universe — Bottom Chapter Root Comment Composer (MS-05E5H2F1)
 *
 * Responsibilities:
 * - Owns the whole-chapter root comment composer UX at the bottom of the chapter reader.
 * - Enforces client-side validation (non-blank body) before submitting.
 * - Extracts CSRF token from document head meta tags.
 * - Submits POST /api/novel/chapters/{chapterId}/comments with { "body": "..." } (unanchored).
 * - Enforces single-flight submission while POST request is in-flight.
 * - On 201 Created: clears draft textarea and triggers authoritative feed refresh via
 *   reader-chapter-comments.js refreshFromPageZero().
 * - If feed refresh fails after 201 Created, preserves cleared draft and presents a clear status
 *   ("Bình luận đã được gửi, nhưng chưa thể tải lại danh sách.") without retrying POST.
 * - On POST failure: preserves textarea draft and displays appropriate error message.
 * - On 'kiemlai:chapter-changed': cancels pending mutation state and prevents stale response
 *   from mutating the composer or refreshing the new chapter.
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

    // Module State
    let currentDoc = null;
    let boundDoc = null;
    let boundForm = null;
    let isSubmitting = false;
    let currentMutationToken = 0;

    let injectedFetch = null;
    let injectedCommentsModule = null;
    let injectedChapterIdResolver = null;

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
        const chapterId = resolveChapterId(doc);
        if (!chapterId) {
            return;
        }

        const rawBody = (elements.input.value || '').trim();
        if (!rawBody) {
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
                body: JSON.stringify({ body: rawBody })
            });

            if (mutationToken !== currentMutationToken || resolveChapterId(doc) !== submittedChapterId) {
                return;
            }

            if (res && res.status === 201) {
                // Clear textarea draft
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
                        if (mutationToken === currentMutationToken && resolveChapterId(doc) === submittedChapterId) {
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
            if (mutationToken !== currentMutationToken || resolveChapterId(doc) !== submittedChapterId) {
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
     */
    function handleChapterChanged() {
        currentMutationToken++;
        isSubmitting = false;

        const elements = getElements();
        if (elements.input) {
            elements.input.disabled = false;
        }
        if (elements.submitBtn) {
            elements.submitBtn.disabled = false;
        }
        setStatus(elements.statusEl, '', '');
    }

    /**
     * Resets module state and detaches listeners.
     */
    function resetComposerState() {
        isSubmitting = false;
        currentMutationToken++;
        injectedFetch = null;
        injectedCommentsModule = null;
        injectedChapterIdResolver = null;

        if (boundForm) {
            try {
                boundForm.removeEventListener('submit', handleSubmit);
            } catch (_) {}
            boundForm = null;
        }

        if (boundDoc) {
            try {
                boundDoc.removeEventListener(EVENT_CHAPTER_CHANGED, handleChapterChanged);
            } catch (_) {}
            boundDoc = null;
        }

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
        }

        const elements = getElements(doc);
        if (elements.form && typeof elements.form.addEventListener === 'function') {
            boundForm = elements.form;
            boundForm.addEventListener('submit', handleSubmit);
        }

        if (typeof doc.addEventListener === 'function') {
            doc.addEventListener(EVENT_CHAPTER_CHANGED, handleChapterChanged);
        }

        return {
            handleSubmit: handleSubmit,
            handleChapterChanged: handleChapterChanged,
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
        init: initReaderChapterCommentComposer,
        destroy: resetComposerState,
        handleSubmit: handleSubmit,
        handleChapterChanged: handleChapterChanged,
        isSubmitting: function () { return isSubmitting; },
        resolveChapterId: resolveChapterId,
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        setCommentsModuleImplementation: function (mod) { injectedCommentsModule = mod; },
        setChapterIdResolver: function (fn) { injectedChapterIdResolver = fn; }
    };
});
