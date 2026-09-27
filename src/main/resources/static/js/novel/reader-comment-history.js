/**
 * KiemLai Universe — Novel Reader Comment Revision History UI (MS-05E5G5E)
 *
 * Responsibilities:
 * - Shared, presentation-agnostic history viewer for active edited comments.
 * - Supports both Drawer (block discussion) and Bottom chapter comments feed.
 * - Single shared Bootstrap-compatible modal viewer (#novelCommentHistoryModal).
 * - Read-only transparency: displays newest-first revision entries without mutation controls.
 * - Fetches initial page (page=0, size=20) on explicit click of "đã chỉnh sửa" marker.
 * - Progressive slice pagination (load-more) when hasNext is true without COUNT(*) queries.
 * - Monotonic token-race safety: stale responses from earlier fetches or comment switches are ignored.
 * - 404 / privacy protection: clears previously rendered history and displays neutral unavailable state.
 * - Handles 'kiemlai:chapter-changed' to close modal and invalidate pending requests.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderCommentHistory = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderCommentHistory = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const MODAL_ID = 'novelCommentHistoryModal';
    const TITLE_ID = 'novelCommentHistoryModalTitle';
    const STATUS_ID = 'novelCommentHistoryStatus';
    const LIST_ID = 'novelCommentHistoryList';
    const MORE_CONTAINER_ID = 'novelCommentHistoryMore';
    const MORE_BTN_ID = 'novelCommentHistoryMoreBtn';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';

    // Module state
    let currentDoc = null;
    let boundDoc = null;
    let currentCommentId = null;
    let currentChapterId = null;
    let currentRequestToken = 0;
    let currentPage = 0;
    let hasNext = false;
    let isLoading = false;
    let isLoadingMore = false;
    let previousFocusedElement = null;

    let injectedFetch = null;
    let injectedMutations = null;
    let injectedRelativeTime = undefined;

    let delegatedClickHandler = null;
    let keydownHandler = null;
    let chapterChangedHandler = null;

    /**
     * Resolves the shared RelativeTime engine instance.
     *
     * @returns {Object|null}
     */
    function resolveRelativeTime() {
        if (injectedRelativeTime !== undefined) {
            return injectedRelativeTime;
        }
        if (typeof window !== 'undefined' && window.RelativeTime) {
            return window.RelativeTime;
        }
        if (typeof globalThis !== 'undefined' && globalThis.RelativeTime) {
            return globalThis.RelativeTime;
        }
        if (typeof require === 'function') {
            try {
                return require('../shared/relative-time.js');
            } catch (_) {}
        }
        return null;
    }

    /**
     * Sets the injected RelativeTime module (for testing or explicit dependency injection).
     *
     * @param {Object|null} rt
     */
    function setRelativeTime(rt) {
        injectedRelativeTime = rt;
    }

    /**
     * Helper to safely clear element contents.
     *
     * @param {Element} el
     */
    function clearElement(el) {
        if (!el) return;
        if (typeof el.replaceChildren === 'function') {
            el.replaceChildren();
        } else {
            while (el.firstChild) {
                el.removeChild(el.firstChild);
            }
            el.textContent = '';
        }
    }

    /**
     * Formats ISO timestamp string to standard dd/MM/yyyy HH:mm format.
     *
     * @param {string} isoString
     * @returns {string}
     */
    function formatTimestamp(isoString) {
        const rt = resolveRelativeTime();
        if (rt && typeof rt.formatAbsolute === 'function') {
            return rt.formatAbsolute(isoString);
        }
        return '';
    }

    /**
     * Resolves the Comment Mutations transport client.
     *
     * @returns {Object|null}
     */
    function resolveMutations() {
        if (injectedMutations) {
            return injectedMutations;
        }
        if (typeof window !== 'undefined') {
            if (window.NovelReaderCommentMutations) {
                return window.NovelReaderCommentMutations;
            }
            if (window.KiemLai && window.KiemLai.NovelReaderCommentMutations) {
                return window.KiemLai.NovelReaderCommentMutations;
            }
        }
        if (typeof globalThis !== 'undefined' && globalThis.NovelReaderCommentMutations) {
            return globalThis.NovelReaderCommentMutations;
        }
        return null;
    }

    /**
     * Resolves active fetch implementation.
     *
     * @returns {Function|null}
     */
    function resolveFetch() {
        if (typeof injectedFetch === 'function') {
            return injectedFetch;
        }
        if (typeof window !== 'undefined' && typeof window.fetch === 'function') {
            return window.fetch.bind(window);
        }
        if (typeof fetch === 'function') {
            return fetch;
        }
        return null;
    }

    /**
     * Ensures the shared modal exists in the document, creating it dynamically if absent.
     *
     * @param {Document} doc
     * @returns {Element}
     */
    function ensureModal(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return null;

        let modal = d.getElementById ? d.getElementById(MODAL_ID) : null;
        if (!modal && typeof d.querySelector === 'function') {
            modal = d.querySelector('#' + MODAL_ID);
        }
        if (modal) {
            return modal;
        }

        modal = d.createElement('div');
        modal.id = MODAL_ID;
        modal.setAttribute('id', MODAL_ID);
        modal.className = 'novel-comment-history-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-labelledby', TITLE_ID);
        modal.hidden = true;

        const backdrop = d.createElement('div');
        backdrop.className = 'novel-comment-history-modal-backdrop';
        backdrop.setAttribute('data-action', 'close-history-modal');

        const card = d.createElement('div');
        card.className = 'novel-comment-history-modal-card';
        card.setAttribute('role', 'document');

        const header = d.createElement('div');
        header.className = 'novel-comment-history-modal-header';

        const title = d.createElement('h3');
        title.id = TITLE_ID;
        title.setAttribute('id', TITLE_ID);
        title.className = 'novel-comment-history-modal-title';
        title.textContent = 'Lịch sử chỉnh sửa';

        const closeBtn = d.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'novel-comment-history-modal-close';
        closeBtn.setAttribute('data-action', 'close-history-modal');
        closeBtn.setAttribute('aria-label', 'Đóng');
        closeBtn.textContent = '✕';

        header.appendChild(title);
        header.appendChild(closeBtn);

        const body = d.createElement('div');
        body.className = 'novel-comment-history-modal-body';
        body.id = 'novelCommentHistoryModalBody';
        body.setAttribute('id', 'novelCommentHistoryModalBody');

        const statusEl = d.createElement('div');
        statusEl.id = STATUS_ID;
        statusEl.setAttribute('id', STATUS_ID);
        statusEl.className = 'novel-comment-history-status';
        statusEl.setAttribute('role', 'status');
        statusEl.setAttribute('aria-live', 'polite');

        const listEl = d.createElement('div');
        listEl.id = LIST_ID;
        listEl.setAttribute('id', LIST_ID);
        listEl.className = 'novel-comment-history-list';
        listEl.setAttribute('role', 'feed');
        listEl.setAttribute('aria-label', 'Danh sách các phiên bản cũ');

        const moreContainer = d.createElement('div');
        moreContainer.id = MORE_CONTAINER_ID;
        moreContainer.setAttribute('id', MORE_CONTAINER_ID);
        moreContainer.className = 'novel-comment-history-more';
        moreContainer.hidden = true;

        const moreBtn = d.createElement('button');
        moreBtn.type = 'button';
        moreBtn.id = MORE_BTN_ID;
        moreBtn.setAttribute('id', MORE_BTN_ID);
        moreBtn.className = 'novel-comment-history-more-btn';
        moreBtn.textContent = 'Xem thêm';

        moreContainer.appendChild(moreBtn);

        body.appendChild(statusEl);
        body.appendChild(listEl);
        body.appendChild(moreContainer);

        card.appendChild(header);
        card.appendChild(body);

        modal.appendChild(backdrop);
        modal.appendChild(card);

        if (d.body && typeof d.body.appendChild === 'function') {
            d.body.appendChild(modal);
        }

        return modal;
    }

    /**
     * Resolves key elements of the history modal.
     *
     * @param {Document} doc
     * @returns {{modal: Element|null, statusEl: Element|null, listEl: Element|null, moreContainer: Element|null, moreBtn: Element|null, closeBtn: Element|null}}
     */
    function getElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return { modal: null, statusEl: null, listEl: null, moreContainer: null, moreBtn: null, closeBtn: null };
        }
        const modal = ensureModal(d);
        if (!modal) {
            return { modal: null, statusEl: null, listEl: null, moreContainer: null, moreBtn: null, closeBtn: null };
        }
        const statusEl = modal.querySelector('#' + STATUS_ID) || (d.getElementById ? d.getElementById(STATUS_ID) : null);
        const listEl = modal.querySelector('#' + LIST_ID) || (d.getElementById ? d.getElementById(LIST_ID) : null);
        const moreContainer = modal.querySelector('#' + MORE_CONTAINER_ID) || (d.getElementById ? d.getElementById(MORE_CONTAINER_ID) : null);
        const moreBtn = modal.querySelector('#' + MORE_BTN_ID) || (d.getElementById ? d.getElementById(MORE_BTN_ID) : null);
        const closeBtn = modal.querySelector('.novel-comment-history-modal-close') ||
            modal.querySelector('button[data-action="close-history-modal"]') ||
            modal.querySelector('[data-action="close-history-modal"]');
        return { modal, statusEl, listEl, moreContainer, moreBtn, closeBtn };
    }

    /**
     * Resolves the current chapterId from an element context or document.
     *
     * @param {Document} doc
     * @param {Element} [contextEl]
     * @returns {string|null}
     */
    function resolveChapterId(doc, contextEl) {
        if (contextEl) {
            if (typeof contextEl.getAttribute === 'function') {
                const id = contextEl.getAttribute('data-chapter-id');
                if (id && id.trim()) return id.trim();
            }
            if (typeof contextEl.closest === 'function') {
                const parentWithChapter = contextEl.closest('[data-chapter-id]');
                if (parentWithChapter) {
                    const id = parentWithChapter.getAttribute('data-chapter-id');
                    if (id && id.trim()) return id.trim();
                }
            }
        }
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (d && typeof d.querySelector === 'function') {
            const bodyEl = d.querySelector('.novel-reader-chapter-body[data-chapter-id]');
            if (bodyEl) {
                const id = bodyEl.getAttribute('data-chapter-id');
                if (id && id.trim()) return id.trim();
            }
            const fallback = d.querySelector('[data-chapter-id]');
            if (fallback) {
                const id = fallback.getAttribute('data-chapter-id');
                if (id && id.trim()) return id.trim();
            }
        }
        return null;
    }

    /**
     * Renders a single revision entry DOM element.
     *
     * @param {Object} rev
     * @param {number} rev.revisionNumber
     * @param {string} rev.body
     * @param {string} rev.createdAt
     * @param {Document} doc
     * @returns {Element}
     */
    function renderRevisionItem(rev, doc) {
        const itemEl = doc.createElement('article');
        itemEl.className = 'novel-comment-history-entry';

        const headerEl = doc.createElement('header');
        headerEl.className = 'novel-comment-history-entry-header';

        const badgeEl = doc.createElement('span');
        badgeEl.className = 'novel-comment-history-badge';
        const revNum = rev && rev.revisionNumber != null ? rev.revisionNumber : '';
        badgeEl.textContent = 'Phiên bản #' + revNum;
        headerEl.appendChild(badgeEl);

        if (rev && rev.createdAt) {
            const timeEl = doc.createElement('time');
            timeEl.className = 'novel-comment-history-time';
            timeEl.setAttribute('datetime', String(rev.createdAt));
            timeEl.setAttribute('data-relative-time', '');
            const rt = resolveRelativeTime();
            const formatted = rt && typeof rt.formatElement === 'function'
                ? rt.formatElement(timeEl)
                : false;
            if (!formatted) {
                const fallback = formatTimestamp(rev.createdAt);
                if (fallback) {
                    timeEl.textContent = fallback;
                }
            }
            if (timeEl.textContent) {
                headerEl.appendChild(timeEl);
            }
        }

        const bodyEl = doc.createElement('div');
        bodyEl.className = 'novel-comment-history-entry-body';
        bodyEl.textContent = (rev && rev.body) ? String(rev.body) : '';

        itemEl.appendChild(headerEl);
        itemEl.appendChild(bodyEl);
        return itemEl;
    }

    /**
     * Opens the revision history viewer for an active comment and triggers initial fetch.
     *
     * @param {Object} params
     * @param {string} params.chapterId
     * @param {string} params.commentId
     * @param {Function} [params.fetch]
     * @returns {Promise<void>}
     */
    async function open(params) {
        const p = params || {};
        const d = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        const chapterId = p.chapterId ? String(p.chapterId).trim() : resolveChapterId(d);
        const commentId = p.commentId ? String(p.commentId).trim() : '';

        if (!chapterId || !commentId) {
            return;
        }

        const { modal, statusEl, listEl, moreContainer, moreBtn, closeBtn } = getElements(d);
        if (!modal) return;

        // Invalidate any previous in-flight requests
        const token = ++currentRequestToken;
        currentCommentId = commentId;
        currentChapterId = chapterId;
        currentPage = 0;
        hasNext = false;
        isLoading = true;
        isLoadingMore = false;

        // Remember active element for focus restoration on close
        if (d.activeElement && d.activeElement !== modal) {
            previousFocusedElement = d.activeElement;
        }

        // Show modal and initial loading state
        modal.hidden = false;
        modal.removeAttribute('hidden');

        if (closeBtn && typeof closeBtn.focus === 'function') {
            try {
                closeBtn.focus();
            } catch (_) {}
        }

        if (listEl) {
            clearElement(listEl);
        }
        if (moreContainer) {
            moreContainer.hidden = true;
            moreContainer.setAttribute('hidden', '');
        }
        if (statusEl) {
            clearElement(statusEl);
            statusEl.textContent = 'Đang tải lịch sử chỉnh sửa...';
        }

        const activeFetch = p.fetch || resolveFetch();
        const mutations = resolveMutations();

        try {
            let result;
            if (mutations && typeof mutations.getCommentRevisions === 'function') {
                result = await mutations.getCommentRevisions({
                    chapterId: chapterId,
                    commentId: commentId,
                    page: 0,
                    size: 20
                }, { fetch: activeFetch });
            } else {
                // Fallback direct transport if mutations client not bound
                const url = '/api/novel/chapters/' + encodeURIComponent(chapterId) +
                    '/comments/' + encodeURIComponent(commentId) +
                    '/revisions?page=0&size=20';
                const res = await activeFetch(url, {
                    method: 'GET',
                    headers: { 'Accept': 'application/json' }
                });
                if (!res) {
                    const err = new Error('Empty response');
                    err.status = 0;
                    throw err;
                }
                if (res.status === 200 || res.ok) {
                    result = {
                        ok: true,
                        status: res.status,
                        data: await res.json()
                    };
                } else {
                    const err = new Error('HTTP ' + res.status);
                    err.status = res.status;
                    throw err;
                }
            }

            // Stale / race response check
            if (token !== currentRequestToken || currentCommentId !== commentId) {
                return;
            }

            isLoading = false;
            const data = result && result.data ? result.data : {};
            const items = Array.isArray(data.items) ? data.items : [];
            hasNext = Boolean(data.hasNext);
            currentPage = data.page != null ? Number(data.page) : 0;

            if (items.length === 0) {
                if (statusEl) {
                    statusEl.textContent = 'Chưa có lịch sử chỉnh sửa nào.';
                }
                if (listEl) {
                    clearElement(listEl);
                }
                if (moreContainer) {
                    moreContainer.hidden = true;
                    moreContainer.setAttribute('hidden', '');
                }
                return;
            }

            // Populated state: render server items in received newest-first order
            if (statusEl) {
                clearElement(statusEl);
            }
            if (listEl) {
                clearElement(listEl);
                for (let i = 0; i < items.length; i++) {
                    const rev = items[i];
                    if (!rev) continue;
                    listEl.appendChild(renderRevisionItem(rev, d));
                }
            }

            if (moreContainer && moreBtn) {
                if (hasNext) {
                    moreContainer.hidden = false;
                    moreContainer.removeAttribute('hidden');
                    moreBtn.disabled = false;
                    moreBtn.textContent = 'Xem thêm';
                } else {
                    moreContainer.hidden = true;
                    moreContainer.setAttribute('hidden', '');
                }
            }
        } catch (err) {
            // Stale / race check
            if (token !== currentRequestToken || currentCommentId !== commentId) {
                return;
            }

            isLoading = false;
            if (listEl) {
                clearElement(listEl);
            }
            if (moreContainer) {
                moreContainer.hidden = true;
                moreContainer.setAttribute('hidden', '');
            }

            const status = (err && err.status) || 0;
            if (statusEl) {
                clearElement(statusEl);
                if (status === 404) {
                    statusEl.textContent = 'Lịch sử chỉnh sửa không còn khả dụng.';
                } else {
                    statusEl.textContent = 'Không thể tải lịch sử chỉnh sửa. Vui lòng thử lại.';
                }
            }
        }
    }

    /**
     * Loads the next page of revisions and appends them to the viewer.
     *
     * @param {Function} [fetchFn]
     * @returns {Promise<void>}
     */
    async function loadMore(fetchFn) {
        const d = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d || isLoading || isLoadingMore || !hasNext || !currentCommentId || !currentChapterId) {
            return;
        }

        const { statusEl, listEl, moreContainer, moreBtn } = getElements(d);
        if (!moreBtn) return;

        isLoadingMore = true;
        const token = currentRequestToken;
        const targetCommentId = currentCommentId;
        const targetChapterId = currentChapterId;
        const nextPage = currentPage + 1;

        moreBtn.disabled = true;
        moreBtn.textContent = 'Đang tải...';

        const activeFetch = fetchFn || resolveFetch();
        const mutations = resolveMutations();

        try {
            let result;
            if (mutations && typeof mutations.getCommentRevisions === 'function') {
                result = await mutations.getCommentRevisions({
                    chapterId: targetChapterId,
                    commentId: targetCommentId,
                    page: nextPage,
                    size: 20
                }, { fetch: activeFetch });
            } else {
                const url = '/api/novel/chapters/' + encodeURIComponent(targetChapterId) +
                    '/comments/' + encodeURIComponent(targetCommentId) +
                    '/revisions?page=' + nextPage + '&size=20';
                const res = await activeFetch(url, {
                    method: 'GET',
                    headers: { 'Accept': 'application/json' }
                });
                if (!res) {
                    const err = new Error('Empty response');
                    err.status = 0;
                    throw err;
                }
                if (res.status === 200 || res.ok) {
                    result = {
                        ok: true,
                        status: res.status,
                        data: await res.json()
                    };
                } else {
                    const err = new Error('HTTP ' + res.status);
                    err.status = res.status;
                    throw err;
                }
            }

            // Stale / race protection
            if (token !== currentRequestToken || currentCommentId !== targetCommentId) {
                return;
            }

            isLoadingMore = false;
            currentPage = nextPage;
            const data = result && result.data ? result.data : {};
            const newItems = Array.isArray(data.items) ? data.items : [];
            hasNext = Boolean(data.hasNext);

            if (listEl && newItems.length > 0) {
                for (let i = 0; i < newItems.length; i++) {
                    const rev = newItems[i];
                    if (!rev) continue;
                    listEl.appendChild(renderRevisionItem(rev, d));
                }
            }

            if (moreContainer && moreBtn) {
                if (hasNext) {
                    moreContainer.hidden = false;
                    moreContainer.removeAttribute('hidden');
                    moreBtn.disabled = false;
                    moreBtn.textContent = 'Xem thêm';
                } else {
                    moreContainer.hidden = true;
                    moreContainer.setAttribute('hidden', '');
                }
            }
        } catch (err) {
            if (token !== currentRequestToken || currentCommentId !== targetCommentId) {
                return;
            }
            isLoadingMore = false;
            const status = err && err.status ? Number(err.status) : 0;
            if (status === 404) {
                hasNext = false;
                if (listEl) {
                    clearElement(listEl);
                }
                if (moreContainer) {
                    moreContainer.hidden = true;
                    moreContainer.setAttribute('hidden', '');
                }
                if (statusEl) {
                    clearElement(statusEl);
                    statusEl.textContent = 'Lịch sử chỉnh sửa không còn khả dụng.';
                }
            } else {
                if (moreBtn) {
                    moreBtn.disabled = false;
                    moreBtn.textContent = 'Thử lại';
                }
            }
        }
    }

    /**
     * Closes the revision history modal and restores focus.
     */
    function close() {
        currentRequestToken++; // Invalidate pending requests
        currentCommentId = null;
        isLoading = false;
        isLoadingMore = false;

        const d = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (d) {
            const { modal } = getElements(d);
            if (modal) {
                modal.hidden = true;
                modal.setAttribute('hidden', '');
            }
        }

        if (previousFocusedElement && typeof previousFocusedElement.focus === 'function') {
            try {
                previousFocusedElement.focus();
            } catch (_) {}
        }
        previousFocusedElement = null;
    }

    /**
     * Unbinds document listeners.
     */
    function unbindEvents() {
        if (boundDoc && typeof boundDoc.removeEventListener === 'function') {
            if (delegatedClickHandler) {
                boundDoc.removeEventListener('click', delegatedClickHandler);
            }
            if (keydownHandler) {
                boundDoc.removeEventListener('keydown', keydownHandler);
            }
            if (chapterChangedHandler) {
                boundDoc.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            }
        }
        delegatedClickHandler = null;
        keydownHandler = null;
        chapterChangedHandler = null;
        boundDoc = null;
    }

    /**
     * Binds document listeners for delegated clicks, keyboard navigation, and chapter changes.
     *
     * @param {Document} doc
     */
    function bindEvents(doc) {
        if (!doc || typeof doc.addEventListener !== 'function') return;
        unbindEvents();
        boundDoc = doc;
        currentDoc = doc;

        delegatedClickHandler = function (e) {
            const target = e ? e.target : null;
            if (!target) return;

            // 1. Close modal triggers
            let closeTrigger = null;
            if (typeof target.closest === 'function') {
                closeTrigger = target.closest('[data-action="close-history-modal"]');
            } else if (target.getAttribute && target.getAttribute('data-action') === 'close-history-modal') {
                closeTrigger = target;
            }
            if (closeTrigger) {
                if (e.preventDefault) e.preventDefault();
                close();
                return;
            }

            // 2. Load-more trigger
            let moreTrigger = null;
            if (typeof target.closest === 'function') {
                moreTrigger = target.closest('#' + MORE_BTN_ID) || target.closest('.novel-comment-history-more-btn');
            } else if (target.id === MORE_BTN_ID || (target.classList && target.classList.contains('novel-comment-history-more-btn'))) {
                moreTrigger = target;
            }
            if (moreTrigger) {
                if (e.preventDefault) e.preventDefault();
                loadMore();
                return;
            }

            // 3. View revisions trigger (.novel-comment-edited or [data-action="view-revisions"])
            let viewTrigger = null;
            if (typeof target.closest === 'function') {
                viewTrigger = target.closest('.novel-comment-edited') || target.closest('[data-action="view-revisions"]');
            } else if (target.classList && target.classList.contains('novel-comment-edited')) {
                viewTrigger = target;
            } else if (target.getAttribute && target.getAttribute('data-action') === 'view-revisions') {
                viewTrigger = target;
            }

            if (viewTrigger) {
                if (e.preventDefault) e.preventDefault();

                // Extract comment ID
                let commentId = viewTrigger.getAttribute('data-comment-id');
                if (!commentId && typeof viewTrigger.closest === 'function') {
                    const parentWithId = viewTrigger.closest('[data-comment-id]');
                    if (parentWithId) {
                        commentId = parentWithId.getAttribute('data-comment-id');
                    }
                }

                // Extract chapter ID
                const chapterId = resolveChapterId(doc, viewTrigger);

                if (commentId && chapterId) {
                    open({ chapterId: chapterId, commentId: commentId });
                }
            }
        };

        keydownHandler = function (e) {
            if (e && (e.key === 'Escape' || e.keyCode === 27)) {
                const { modal } = getElements(doc);
                if (modal && !modal.hidden) {
                    if (e.preventDefault) e.preventDefault();
                    close();
                }
            }
        };

        chapterChangedHandler = function () {
            close();
            currentChapterId = null;
        };

        doc.addEventListener('click', delegatedClickHandler);
        doc.addEventListener('keydown', keydownHandler);
        doc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
    }

    /**
     * Initializes the shared revision history module.
     *
     * @param {Document} doc
     * @param {Object} [options]
     */
    function init(doc, options) {
        const d = doc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;

        currentDoc = d;
        const opts = options || {};

        if (typeof opts.fetch === 'function') {
            injectedFetch = opts.fetch;
        }
        if (opts.mutations && typeof opts.mutations === 'object') {
            injectedMutations = opts.mutations;
        }

        ensureModal(d);
        bindEvents(d);
    }

    /**
     * Cleans up listeners, modal state, and pending tokens.
     */
    function destroy() {
        close();
        unbindEvents();
        currentDoc = null;
        injectedFetch = null;
        injectedMutations = null;
        injectedRelativeTime = undefined;
    }

    // Auto-init on document ready in browser environment if available
    if (typeof document !== 'undefined') {
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
        open: open,
        close: close,
        loadMore: loadMore,
        ensureModal: ensureModal,
        formatTimestamp: formatTimestamp,
        renderRevisionItem: renderRevisionItem,
        resolveRelativeTime: resolveRelativeTime,
        setRelativeTime: setRelativeTime,
        resolveChapterId: resolveChapterId
    };
});
