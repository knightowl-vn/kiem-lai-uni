/**
 * KiemLai Universe — Novel Comment Draft Adapter (UX-DRAFT-01D1)
 *
 * Responsibilities:
 * - Resolves EphemeralDraftStore instance across injected, browser global, and CommonJS contexts.
 * - Owns canonical Novel draft storage key construction for Bottom Chapter and Block Drawer comments.
 * - Provides a single, unified, synchronous pagehide flush bridge across the Novel Reader.
 * - Presentation-agnostic: contains NO DOM composers, API calls, auth logic, or DTO handling.
 */
(function (root, factory) {
    'use strict';
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderCommentDrafts = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderCommentDrafts = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const EVENT_FLUSH_DRAFTS = 'kiemlai:novel-comment-drafts-flush';

    // Bridge state
    let boundDoc = null;
    let boundWin = null;
    let boundPagehideHandler = null;

    /**
     * Validates and URI-encodes a dynamic key component.
     * Dynamic components must be non-empty strings.
     * Non-string values are strictly rejected without coercion.
     *
     * @param {*} value
     * @returns {string|null} URI-encoded trimmed string, or null if invalid
     */
    function validateComponent(value) {
        if (typeof value !== 'string') {
            return null;
        }
        const trimmed = value.trim();
        if (!trimmed) {
            return null;
        }
        return encodeURIComponent(trimmed);
    }

    /**
     * Canonical Bottom Chapter Root Draft Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:root
     *
     * @param {string} chapterId
     * @returns {string|null}
     */
    function getChapterRootDraftKey(chapterId) {
        const c = validateComponent(chapterId);
        return c ? 'kiemlai:draft:novel-comment:' + c + ':root' : null;
    }

    /**
     * Canonical Bottom Chapter Reply Draft Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:reply:{targetCommentId}
     *
     * @param {string} chapterId
     * @param {string} targetCommentId
     * @returns {string|null}
     */
    function getChapterReplyDraftKey(chapterId, targetCommentId) {
        const c = validateComponent(chapterId);
        const t = validateComponent(targetCommentId);
        return (c && t) ? 'kiemlai:draft:novel-comment:' + c + ':reply:' + t : null;
    }

    /**
     * Canonical Bottom Chapter Edit Draft Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:edit:{commentId}
     *
     * @param {string} chapterId
     * @param {string} commentId
     * @returns {string|null}
     */
    function getChapterEditDraftKey(chapterId, commentId) {
        const c = validateComponent(chapterId);
        const m = validateComponent(commentId);
        return (c && m) ? 'kiemlai:draft:novel-comment:' + c + ':edit:' + m : null;
    }

    /**
     * Canonical Bottom Chapter Active Inline Marker Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:active-inline
     *
     * @param {string} chapterId
     * @returns {string|null}
     */
    function getChapterActiveInlineMarkerKey(chapterId) {
        const c = validateComponent(chapterId);
        return c ? 'kiemlai:draft:novel-comment:' + c + ':active-inline' : null;
    }

    /**
     * Canonical Block Drawer Root Draft Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:block:{blockKey}:root
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @returns {string|null}
     */
    function getBlockRootDraftKey(chapterId, blockKey) {
        const c = validateComponent(chapterId);
        const b = validateComponent(blockKey);
        return (c && b) ? 'kiemlai:draft:novel-comment:' + c + ':block:' + b + ':root' : null;
    }

    /**
     * Canonical Block Drawer Reply Draft Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:block:{blockKey}:reply:{targetCommentId}
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @param {string} targetCommentId
     * @returns {string|null}
     */
    function getBlockReplyDraftKey(chapterId, blockKey, targetCommentId) {
        const c = validateComponent(chapterId);
        const b = validateComponent(blockKey);
        const t = validateComponent(targetCommentId);
        return (c && b && t) ? 'kiemlai:draft:novel-comment:' + c + ':block:' + b + ':reply:' + t : null;
    }

    /**
     * Canonical Block Drawer Edit Draft Key.
     * Schema: kiemlai:draft:novel-comment:{chapterId}:block:{blockKey}:edit:{commentId}
     *
     * @param {string} chapterId
     * @param {string} blockKey
     * @param {string} commentId
     * @returns {string|null}
     */
    function getBlockEditDraftKey(chapterId, blockKey, commentId) {
        const c = validateComponent(chapterId);
        const b = validateComponent(blockKey);
        const m = validateComponent(commentId);
        return (c && b && m) ? 'kiemlai:draft:novel-comment:' + c + ':block:' + b + ':edit:' + m : null;
    }

    /**
     * Canonical Block Drawer Active Marker Key (chapter-scoped, NOT block-scoped).
     * Schema: kiemlai:draft:novel-comment:{chapterId}:active-block
     *
     * @param {string} chapterId
     * @returns {string|null}
     */
    function getBlockActiveMarkerKey(chapterId) {
        const c = validateComponent(chapterId);
        return c ? 'kiemlai:draft:novel-comment:' + c + ':active-block' : null;
    }

    /**
     * Resolves the EphemeralDraftStore implementation across multiple environments.
     *
     * @param {Object} [injectedStore]
     * @returns {Object|null}
     */
    function resolveDraftStore(injectedStore) {
        if (injectedStore && typeof injectedStore === 'object') {
            return injectedStore;
        }
        if (typeof window !== 'undefined') {
            if (window.EphemeralDraftStore) {
                return window.EphemeralDraftStore;
            }
            if (window.KiemLai && window.KiemLai.EphemeralDraftStore) {
                return window.KiemLai.EphemeralDraftStore;
            }
        }
        if (typeof globalThis !== 'undefined') {
            if (globalThis.EphemeralDraftStore) {
                return globalThis.EphemeralDraftStore;
            }
            if (globalThis.KiemLai && globalThis.KiemLai.EphemeralDraftStore) {
                return globalThis.KiemLai.EphemeralDraftStore;
            }
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/ephemeral-draft-store.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Synchronous pagehide event listener dispatching local flush on document.
     */
    function onPagehide() {
        if (!boundDoc) {
            return;
        }
        try {
            if (typeof CustomEvent === 'function') {
                boundDoc.dispatchEvent(new CustomEvent(EVENT_FLUSH_DRAFTS));
            } else if (typeof boundDoc.createEvent === 'function') {
                const evt = boundDoc.createEvent('CustomEvent');
                evt.initCustomEvent(EVENT_FLUSH_DRAFTS, false, false, null);
                boundDoc.dispatchEvent(evt);
            }
        } catch (_) {}
    }

    /**
     * Initializes the single Reader-wide pagehide flush bridge.
     * Idempotent when called repeatedly on the same document/window.
     * Re-initialization with different document/window detaches the previous listener first.
     *
     * @param {Document} [targetDoc]
     * @param {Window} [targetWin]
     * @returns {boolean} true if bridge listener was bound
     */
    function initPagehideBridge(targetDoc, targetWin) {
        const win = targetWin || (targetDoc && targetDoc.defaultView) || (typeof window !== 'undefined' ? window : null);
        const doc = targetDoc || (win && win.document) || (typeof document !== 'undefined' ? document : null);

        if (!win || !doc || typeof win.addEventListener !== 'function') {
            return false;
        }

        if (boundWin === win && boundDoc === doc && boundPagehideHandler) {
            return true; // Already bound idempotently
        }

        destroyPagehideBridge();

        boundDoc = doc;
        boundWin = win;
        boundPagehideHandler = onPagehide;
        boundWin.addEventListener('pagehide', boundPagehideHandler);
        return true;
    }

    /**
     * Destroys the pagehide bridge listener without clearing stored drafts.
     */
    function destroyPagehideBridge() {
        if (boundWin && boundPagehideHandler && typeof boundWin.removeEventListener === 'function') {
            try {
                boundWin.removeEventListener('pagehide', boundPagehideHandler);
            } catch (_) {}
        }
        boundDoc = null;
        boundWin = null;
        boundPagehideHandler = null;
    }

    // Auto-bootstrap in browser environment if DOM and window are available
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        initPagehideBridge(document, window);
    }

    return {
        EVENT_FLUSH_DRAFTS: EVENT_FLUSH_DRAFTS,
        resolveDraftStore: resolveDraftStore,
        getChapterRootDraftKey: getChapterRootDraftKey,
        getChapterReplyDraftKey: getChapterReplyDraftKey,
        getChapterEditDraftKey: getChapterEditDraftKey,
        getChapterActiveInlineMarkerKey: getChapterActiveInlineMarkerKey,
        getBlockRootDraftKey: getBlockRootDraftKey,
        getBlockReplyDraftKey: getBlockReplyDraftKey,
        getBlockEditDraftKey: getBlockEditDraftKey,
        getBlockActiveMarkerKey: getBlockActiveMarkerKey,
        initPagehideBridge: initPagehideBridge,
        destroyPagehideBridge: destroyPagehideBridge,
        init: initPagehideBridge,
        destroy: destroyPagehideBridge,
        isBridgeActive: function () {
            return boundPagehideHandler !== null;
        }
    };
});
