/**
 * KiemLai Universe — Novel Reader Comment Mutations Client (MS-05E5H2F2A)
 *
 * Responsibilities:
 * - Shared, presentation-agnostic client for Novel comment mutations.
 * - Owns transport and application request semantics for creating replies.
 * - Performs canonical endpoint construction, payload serialization, and CSRF header binding.
 * - Pure transport layer: does NOT render DOM, fabricate DTOs, or trigger surface-specific refreshes.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderCommentMutations = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderCommentMutations = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Extracts CSRF token and header name from document <meta> tags.
     *
     * @param {Document} [doc]
     * @returns {{token: string, headerName: string}|null}
     */
    function getCsrf(doc) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
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
     * Canonical Reply creation transport mutation.
     * Issues: POST /api/novel/chapters/{chapterId}/comments/{parentCommentId}/replies
     *
     * @param {Object} input
     * @param {string} input.chapterId - ID of the Novel chapter.
     * @param {string} input.parentCommentId - ID of the target comment being replied to.
     * @param {string} input.body - Raw text content of the reply.
     * @param {Object} [options]
     * @param {Function} [options.fetch] - Custom or test fetch implementation.
     * @param {Document} [options.document] - Document to extract CSRF metadata from.
     * @param {{token: string, headerName: string}} [options.csrf] - Explicit CSRF credentials.
     * @returns {Promise<{ok: boolean, status: number, commentId: string|null, data: Object|null}>}
     */
    async function createReply(input, options) {
        const params = input || {};
        const opts = options || {};

        const chapterId = params.chapterId ? String(params.chapterId).trim() : '';
        const parentCommentId = params.parentCommentId ? String(params.parentCommentId).trim() : '';
        const rawBody = params.body !== undefined && params.body !== null ? String(params.body) : '';
        const trimmedBody = rawBody.trim();

        if (!chapterId || !parentCommentId) {
            const err = new Error('chapterId and parentCommentId are required.');
            err.code = 'INVALID_TARGET';
            throw err;
        }

        if (!trimmedBody) {
            const err = new Error('Comment body is required.');
            err.code = 'INVALID_BODY';
            throw err;
        }

        const doc = opts.document || params.document || (typeof document !== 'undefined' ? document : null);
        const csrf = opts.csrf || params.csrf || getCsrf(doc);
        if (!csrf || !csrf.token || !csrf.headerName) {
            const err = new Error('CSRF token missing');
            err.code = 'CSRF_MISSING';
            throw err;
        }

        const fetchFn = opts.fetch || opts.fetchFn || params.fetch || params.fetchFn ||
            (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchFn || typeof fetchFn !== 'function') {
            const err = new Error('Fetch implementation not available');
            err.code = 'FETCH_UNAVAILABLE';
            throw err;
        }

        const url = '/api/novel/chapters/' + encodeURIComponent(chapterId) +
            '/comments/' + encodeURIComponent(parentCommentId) + '/replies';

        const headers = {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
        };
        headers[csrf.headerName] = csrf.token;

        const res = await fetchFn(url, {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({ body: trimmedBody })
        });

        if (!res) {
            const err = new Error('Empty response received from server');
            err.status = 0;
            throw err;
        }

        if (res.status === 201) {
            let data = null;
            if (typeof res.json === 'function') {
                try {
                    data = await res.json();
                } catch (_) {
                    data = null;
                }
            }
            const commentId = data && typeof data.commentId === 'string' && data.commentId.trim().length > 0
                ? data.commentId.trim()
                : null;

            return {
                ok: true,
                status: 201,
                commentId: commentId,
                data: data
            };
        }

        const err = new Error('HTTP ' + res.status);
        err.status = res.status;
        if (typeof res.json === 'function') {
            try {
                err.data = await res.json();
            } catch (_) {
                err.data = null;
            }
        }
        throw err;
    }

    /**
     * Canonical Comment edit transport mutation.
     * Issues: PATCH /api/novel/chapters/{chapterId}/comments/{commentId}
     *
     * @param {Object} input
     * @param {string} input.chapterId - ID of the Novel chapter.
     * @param {string} input.commentId - ID of the comment being edited.
     * @param {string} input.body - Raw updated text content of the comment.
     * @param {Object} [options]
     * @param {Function} [options.fetch] - Custom or test fetch implementation.
     * @param {Document} [options.document] - Document to extract CSRF metadata from.
     * @param {{token: string, headerName: string}} [options.csrf] - Explicit CSRF credentials.
     * @returns {Promise<{ok: boolean, status: number, data: Object|null}>}
     */
    async function editComment(input, options) {
        const params = input || {};
        const opts = options || {};

        const chapterId = params.chapterId ? String(params.chapterId).trim() : '';
        const commentId = params.commentId ? String(params.commentId).trim() : '';
        const rawBody = params.body !== undefined && params.body !== null ? String(params.body) : '';
        const trimmedBody = rawBody.trim();

        if (!chapterId || !commentId) {
            const err = new Error('chapterId and commentId are required.');
            err.code = 'INVALID_TARGET';
            throw err;
        }

        if (!trimmedBody) {
            const err = new Error('Comment body is required.');
            err.code = 'INVALID_BODY';
            throw err;
        }

        const doc = opts.document || params.document || (typeof document !== 'undefined' ? document : null);
        const csrf = opts.csrf || params.csrf || getCsrf(doc);
        if (!csrf || !csrf.token || !csrf.headerName) {
            const err = new Error('CSRF token missing');
            err.code = 'CSRF_MISSING';
            throw err;
        }

        const fetchFn = opts.fetch || opts.fetchFn || params.fetch || params.fetchFn ||
            (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchFn || typeof fetchFn !== 'function') {
            const err = new Error('Fetch implementation not available');
            err.code = 'FETCH_UNAVAILABLE';
            throw err;
        }

        const url = '/api/novel/chapters/' + encodeURIComponent(chapterId) +
            '/comments/' + encodeURIComponent(commentId);

        const headers = {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
        };
        headers[csrf.headerName] = csrf.token;

        const res = await fetchFn(url, {
            method: 'PATCH',
            headers: headers,
            body: JSON.stringify({ body: trimmedBody })
        });

        if (!res) {
            const err = new Error('Empty response received from server');
            err.status = 0;
            throw err;
        }

        if (res.status === 204 || res.status === 200) {
            let data = null;
            if (typeof res.json === 'function') {
                try {
                    data = await res.json();
                } catch (_) {
                    data = null;
                }
            }
            return {
                ok: true,
                status: res.status,
                data: data
            };
        }

        const err = new Error('HTTP ' + res.status);
        err.status = res.status;
        if (typeof res.json === 'function') {
            try {
                err.data = await res.json();
            } catch (_) {
                err.data = null;
            }
        }
        throw err;
    }

    /**
     * Canonical Comment deletion transport mutation.
     * Issues: DELETE /api/novel/chapters/{chapterId}/comments/{commentId}
     *
     * @param {Object} input
     * @param {string} input.chapterId - ID of the Novel chapter.
     * @param {string} input.commentId - ID of the comment being deleted.
     * @param {Object} [options]
     * @param {Function} [options.fetch] - Custom or test fetch implementation.
     * @param {Document} [options.document] - Document to extract CSRF metadata from.
     * @param {{token: string, headerName: string}} [options.csrf] - Explicit CSRF credentials.
     * @returns {Promise<{ok: boolean, status: number}>}
     */
    async function deleteComment(input, options) {
        const params = input || {};
        const opts = options || {};

        const chapterId = params.chapterId ? String(params.chapterId).trim() : '';
        const commentId = params.commentId ? String(params.commentId).trim() : '';

        if (!chapterId || !commentId) {
            const err = new Error('chapterId and commentId are required.');
            err.code = 'INVALID_TARGET';
            throw err;
        }

        const doc = opts.document || params.document || (typeof document !== 'undefined' ? document : null);
        const csrf = opts.csrf || params.csrf || getCsrf(doc);
        if (!csrf || !csrf.token || !csrf.headerName) {
            const err = new Error('CSRF token missing');
            err.code = 'CSRF_MISSING';
            throw err;
        }

        const fetchFn = opts.fetch || opts.fetchFn || params.fetch || params.fetchFn ||
            (typeof globalThis !== 'undefined' && globalThis.fetch ? globalThis.fetch : null);
        if (!fetchFn || typeof fetchFn !== 'function') {
            const err = new Error('Fetch implementation not available');
            err.code = 'FETCH_UNAVAILABLE';
            throw err;
        }

        const url = '/api/novel/chapters/' + encodeURIComponent(chapterId) +
            '/comments/' + encodeURIComponent(commentId);

        const headers = {
            'Accept': 'application/json'
        };
        headers[csrf.headerName] = csrf.token;

        const res = await fetchFn(url, {
            method: 'DELETE',
            headers: headers
        });

        if (!res) {
            const err = new Error('Empty response received from server');
            err.status = 0;
            throw err;
        }

        if (res.status === 204 || res.status === 200) {
            return {
                ok: true,
                status: res.status
            };
        }

        const err = new Error('HTTP ' + res.status);
        err.status = res.status;
        if (typeof res.json === 'function') {
            try {
                err.data = await res.json();
            } catch (_) {
                err.data = null;
            }
        }
        throw err;
    }

    return {
        createReply: createReply,
        editComment: editComment,
        deleteComment: deleteComment,
        getCsrf: getCsrf
    };
});
