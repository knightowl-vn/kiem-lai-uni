/**
 * KiemLai Community — Inline Comments Module (MS-07B8.2.2)
 *
 * Responsibilities:
 * - Lazy load & render discussion feed for Community post cards
 * - Roots-only initial feed loading (zero eager reply materialization)
 * - Explicit on-demand thread expansion for replies
 * - Authenticated root comment and reply creation
 * - Delegated click handling for toggle, reply, cancel, load-more, toggle-thread
 * - Authoritative post comment-count synchronization
 * - Integration with shared CommentPresentation and InteractionReactions primitives
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        let commentPresentation, relativeTime, interactionReactions;
        try { commentPresentation = require('../shared/comment-presentation.js'); } catch (_) {}
        try { relativeTime = require('../shared/relative-time.js'); } catch (_) {}
        try { interactionReactions = require('../shared/interaction-reactions.js'); } catch (_) {}
        module.exports = factory(commentPresentation, relativeTime, interactionReactions);
    } else {
        const exports = factory(root.CommentPresentation, root.RelativeTime, root.InteractionReactions);
        root.CommunityComments = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommunityComments = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function (injectedPresentation, injectedRelativeTime, injectedReactions) {
    'use strict';

    const postStates = new Map(); // postId -> { page, hasNext, isLoading, isLoaded, roots } and `${postId}:${sort}`
    const postCurrentSort = new Map(); // postId -> 'FEATURED' | 'NEWEST'

    const SORT_MODES = Object.freeze({
        FEATURED: 'FEATURED',
        NEWEST: 'NEWEST'
    });

    function getCurrentSort(postId) {
        return postCurrentSort.get(String(postId)) || SORT_MODES.NEWEST;
    }

    function setCurrentSort(postId, sort) {
        postCurrentSort.set(String(postId), sort === SORT_MODES.FEATURED ? SORT_MODES.FEATURED : SORT_MODES.NEWEST);
    }

    function getPostState(postId, optSort) {
        if (!postId) return null;
        const sort = optSort || getCurrentSort(postId);
        return postStates.get(String(postId) + ':' + sort);
    }

    function updateSortControlsActiveState(postId, activeSort) {
        const doc = getDoc();
        if (!doc || !postId) return;
        const strPostId = String(postId);
        const presentation = resolvePresentation();
        const controls = doc.querySelectorAll('.kl-sort-dropdown[data-target-id="' + strPostId + '"], .kl-sort-dropdown[data-post-id="' + strPostId + '"], .kl-comment-sort-controls[data-target-id="' + strPostId + '"], .kl-comment-sort-controls[data-post-id="' + strPostId + '"]');
        controls.forEach(ctrl => {
            if (presentation && typeof presentation.updateSortDropdown === 'function') {
                presentation.updateSortDropdown(ctrl, activeSort);
            } else {
                const labelEl = ctrl.querySelector('.kl-sort-dropdown__label');
                if (labelEl) {
                    labelEl.textContent = (activeSort === SORT_MODES.FEATURED) ? 'Nổi bật' : 'Mới nhất';
                }
                const btns = ctrl.querySelectorAll('[data-action="change-comment-sort"], .kl-sort-dropdown__item, .kl-comment-sort-btn');
                btns.forEach(btn => {
                    const mode = btn.getAttribute('data-sort-mode');
                    const isActive = (mode === activeSort);
                    btn.setAttribute('aria-pressed', isActive ? 'true' : 'false');
                    btn.setAttribute('aria-checked', isActive ? 'true' : 'false');
                    if (isActive) {
                        btn.classList.add('is-active');
                        btn.classList.add('is-selected');
                    } else {
                        btn.classList.remove('is-active');
                        btn.classList.remove('is-selected');
                    }
                });
            }
        });
    }

    function switchSort(postId, newSort) {
        if (!postId || !newSort) return;
        const sortMode = (newSort === SORT_MODES.NEWEST) ? SORT_MODES.NEWEST : SORT_MODES.FEATURED;
        const prevSort = getCurrentSort(postId);
        if (sortMode === prevSort) {
            updateSortControlsActiveState(postId, sortMode);
            return;
        }

        setCurrentSort(postId, sortMode);
        updateSortControlsActiveState(postId, sortMode);

        const cacheKey = String(postId) + ':' + sortMode;
        const cachedState = postStates.get(cacheKey);

        if (cachedState && cachedState.isLoaded) {
            const targets = getActiveTargets(postId);
            targets.forEach(t => {
                renderRootsIntoTarget(postId, t.el, t.mode, true);
            });
        } else {
            const freshState = { page: 0, hasNext: false, isLoading: false, isLoaded: false, roots: [] };
            postStates.set(cacheKey, freshState);

            const doc = getDoc();
            if (doc) {
                const targets = getActiveTargets(postId);
                targets.forEach(t => {
                    const listEl = t.el.querySelector('[data-thread-list="' + postId + '"]');
                    if (listEl) {
                        listEl.innerHTML = '';
                    }
                });
            }

            fetchComments(postId, 0, null, sortMode);
        }
    }

    function handleReactionUpdated(event) {
        const detail = event && event.detail;
        if (!detail || detail.targetType !== 'COMMENT') return;
        const doc = getDoc();
        if (!doc) return;

        const commentEl = doc.querySelector('[data-comment-id="' + detail.targetId + '"], [data-thread-id="' + detail.targetId + '"]');
        let postId = null;
        if (commentEl) {
            const card = commentEl.closest('.community-post-card, [data-post-comments], [data-drawer-body]');
            if (card) {
                postId = card.getAttribute('data-post-id') || card.getAttribute('data-post-comments');
            }
            if (!postId && isDrawerOpen()) {
                postId = currentDrawerPostId;
            }
        }

        if (postId) {
            postStates.delete(String(postId) + ':FEATURED');
        } else {
            for (const key of Array.from(postStates.keys())) {
                if (key.endsWith(':FEATURED')) {
                    postStates.delete(key);
                }
            }
        }
    }

    let injectedFetch = null;
    let isInitialized = false;
    let injectedIsMobile = null;
    let savedScrollY = 0;
    let currentDrawerPostId = null;

    const DRAWER_ID = 'communityCommentsDrawer';
    const BACKDROP_ID = 'communityCommentsBackdrop';

    function isMobileViewport() {
        if (injectedIsMobile !== null) {
            return injectedIsMobile;
        }
        if (typeof window !== 'undefined') {
            if (typeof window.matchMedia === 'function') {
                const mq = window.matchMedia('(max-width: 767.98px)');
                if (mq && typeof mq.matches === 'boolean') {
                    return mq.matches;
                }
            }
            if (typeof window.innerWidth === 'number') {
                return window.innerWidth <= 767.98;
            }
        }
        return false;
    }

    function setMobileViewport(value) {
        injectedIsMobile = typeof value === 'boolean' ? value : null;
    }

    function getDrawerElements(doc) {
        const d = doc || getDoc();
        if (!d) return { drawer: null, backdrop: null };
        return {
            drawer: d.getElementById(DRAWER_ID),
            backdrop: d.getElementById(BACKDROP_ID)
        };
    }

    function ensureDrawerElements(doc) {
        const d = doc || getDoc();
        if (!d) return { drawer: null, backdrop: null };

        let backdrop = d.getElementById(BACKDROP_ID);
        if (!backdrop) {
            backdrop = d.createElement('div');
            backdrop.id = BACKDROP_ID;
            backdrop.setAttribute('id', BACKDROP_ID);
            backdrop.className = 'community-comments-backdrop';
            backdrop.hidden = true;
            backdrop.setAttribute('hidden', '');
            if (d.body) {
                d.body.appendChild(backdrop);
            }
        }

        let drawer = d.getElementById(DRAWER_ID);
        if (!drawer) {
            drawer = d.createElement('div');
            drawer.id = DRAWER_ID;
            drawer.setAttribute('id', DRAWER_ID);
            drawer.className = 'community-comments-drawer';
            drawer.setAttribute('role', 'dialog');
            drawer.setAttribute('aria-modal', 'true');
            drawer.setAttribute('aria-label', 'Bình luận');
            drawer.hidden = true;
            drawer.setAttribute('hidden', '');

            const header = d.createElement('div');
            header.className = 'community-comments-drawer__header';

            const titleDiv = d.createElement('div');
            titleDiv.className = 'community-comments-drawer__header-title';
            const title = d.createElement('h5');
            title.className = 'community-comments-drawer__title m-0';
            title.textContent = 'Bình luận';
            titleDiv.appendChild(title);

            const closeBtn = d.createElement('button');
            closeBtn.type = 'button';
            closeBtn.className = 'btn-close community-comments-drawer__close';
            closeBtn.setAttribute('data-action', 'close-comments-drawer');
            closeBtn.setAttribute('aria-label', 'Đóng');
            closeBtn.textContent = '×';

            header.appendChild(titleDiv);
            header.appendChild(closeBtn);
            drawer.appendChild(header);

            const body = d.createElement('div');
            body.className = 'community-comments-drawer__body';
            body.setAttribute('data-drawer-body', '');
            drawer.appendChild(body);

            const footer = d.createElement('div');
            footer.className = 'community-comments-drawer__footer';
            footer.setAttribute('data-drawer-footer', '');
            drawer.appendChild(footer);

            if (d.body) {
                d.body.appendChild(drawer);
            }
        }

        return { drawer, backdrop };
    }

    function isDrawerOpen() {
        const doc = getDoc();
        if (!doc) return false;
        const { drawer } = getDrawerElements(doc);
        return Boolean(drawer && !drawer.hidden && drawer.classList.contains('is-open'));
    }

    function getDoc() {
        return typeof document !== 'undefined' ? document : null;
    }

    function resolvePresentation() {
        if (injectedPresentation) return injectedPresentation;
        if (typeof window !== 'undefined') {
            return window.CommentPresentation || (window.KiemLai && window.KiemLai.CommentPresentation);
        }
        return null;
    }

    function resolveRelativeTime() {
        if (injectedRelativeTime) return injectedRelativeTime;
        if (typeof window !== 'undefined') {
            return window.RelativeTime || (window.KiemLai && window.KiemLai.RelativeTime);
        }
        return null;
    }

    function resolveReactions() {
        if (injectedReactions) return injectedReactions;
        if (typeof window !== 'undefined') {
            return window.InteractionReactions || (window.KiemLai && window.KiemLai.InteractionReactions);
        }
        return null;
    }

    function getFetch() {
        if (injectedFetch) return injectedFetch;
        if (typeof window !== 'undefined' && window.fetch) return window.fetch.bind(window);
        if (typeof globalThis !== 'undefined' && globalThis.fetch) return globalThis.fetch.bind(globalThis);
        return null;
    }

    function isUserAuthenticated() {
        const doc = getDoc();
        if (!doc) return false;
        const metaAuth = doc.querySelector('meta[name="_authenticated"]');
        if (metaAuth) return metaAuth.getAttribute('content') === 'true';
        if (doc.body && doc.body.dataset && doc.body.dataset.authenticated === 'true') {
            return true;
        }
        const feedList = doc.getElementById('communityFeedList');
        if (feedList && feedList.getAttribute('data-authenticated') === 'true') {
            return true;
        }
        const profileContainer = doc.querySelector('.community-profile-container');
        if (profileContainer && profileContainer.getAttribute('data-authenticated') === 'true') {
            return true;
        }
        return false;
    }

    function getCsrfToken() {
        const doc = getDoc();
        if (!doc) return '';
        const meta = doc.querySelector('meta[name="_csrf"]');
        return meta ? (meta.getAttribute('content') || '') : '';
    }

    function getCsrfHeader() {
        const doc = getDoc();
        if (!doc) return 'X-CSRF-TOKEN';
        const meta = doc.querySelector('meta[name="_csrf_header"]');
        return meta ? (meta.getAttribute('content') || 'X-CSRF-TOKEN') : 'X-CSRF-TOKEN';
    }

    function getReturnToUrl() {
        if (typeof window !== 'undefined' && window.location) {
            return encodeURIComponent(window.location.pathname + window.location.search);
        }
        return encodeURIComponent('/community');
    }

    function updateCommentCount(postId, count) {
        const doc = getDoc();
        if (!doc || !postId) return;
        const postCards = doc.querySelectorAll('.community-post-card[data-post-id="' + postId + '"]');
        postCards.forEach(card => {
            const countSpan = card.querySelector('.post-comment-count');
            if (countSpan) {
                countSpan.textContent = String(count);
            }
        });
    }

    function hydrateComponents(el) {
        if (!el) return;
        const rt = resolveRelativeTime();
        if (rt && typeof rt.formatTree === 'function') {
            rt.formatTree(el);
        }
        const reactions = resolveReactions();
        if (reactions && typeof reactions.hydrate === 'function') {
            reactions.hydrate(el);
        }
    }

    function renderRootCommentItem(item, postId, doc) {
        const d = doc || getDoc();
        const presentation = resolvePresentation();
        if (!presentation || !item || !item.root) return null;

        const root = item.root;
        const replyCount = typeof item.replyCount === 'number' ? item.replyCount : (Array.isArray(item.replies) ? item.replies.length : 0);
        const hasLoadedReplies = Array.isArray(item.replies) && item.replies.length > 0;

        const threadEl = d.createElement('div');
        threadEl.className = 'community-comment-thread';
        threadEl.setAttribute('data-thread-id', String(root.id));

        const rootContainer = d.createElement('div');
        rootContainer.className = 'community-comment-root-container';

        const authorName = (root.author && root.author.displayName) ? root.author.displayName : 'Người dùng';
        const primaryActions = [
            {
                key: 'reply',
                label: 'Phản hồi',
                className: 'btn btn-sm btn-link text-decoration-none p-0 text-secondary community-reply-btn',
                attributes: {
                    'data-action': 'reply',
                    'data-post-id': String(postId),
                    'data-comment-id': String(root.id),
                    'data-root-id': String(root.id),
                    'data-author-name': authorName
                }
            }
        ];

        const rootDescriptor = {
            id: String(root.id),
            tag: 'div',
            legacyPrefix: 'community-comment',
            className: 'community-comment--root',
            tombstone: root.tombstone === true,
            author: root.author,
            createdAt: root.createdAt,
            edited: root.updatedAt && root.createdAt && (new Date(root.updatedAt) - new Date(root.createdAt) > 1000),
            reactionSummary: root.reactionSummary,
            body: root.body || '',
            primaryActions: primaryActions
        };

        const cardEl = presentation.renderComment(rootDescriptor, d);
        if (cardEl) {
            rootContainer.appendChild(cardEl);
        }

        const rootReplySlot = d.createElement('div');
        rootReplySlot.className = 'community-reply-composer-slot';
        rootReplySlot.setAttribute('data-reply-slot', String(root.id));
        rootContainer.appendChild(rootReplySlot);

        threadEl.appendChild(rootContainer);

        // Thread toggle button if replies exist
        if (replyCount > 0 || hasLoadedReplies) {
            const toggleContainer = d.createElement('div');
            toggleContainer.className = 'community-thread-toggle-wrapper ms-4 mt-1';

            const toggleBtn = d.createElement('button');
            toggleBtn.type = 'button';
            toggleBtn.className = 'btn btn-sm btn-link text-decoration-none p-0 text-primary community-thread-toggle-btn';
            toggleBtn.setAttribute('data-action', 'toggle-thread');
            toggleBtn.setAttribute('data-post-id', String(postId));
            toggleBtn.setAttribute('data-root-id', String(root.id));
            toggleBtn.setAttribute('data-reply-count', String(replyCount));
            toggleBtn.textContent = hasLoadedReplies ? 'Ẩn phản hồi' : ('Xem ' + replyCount + ' phản hồi');

            toggleContainer.appendChild(toggleBtn);
            threadEl.appendChild(toggleContainer);
        }

        // Replies container
        const repliesContainer = d.createElement('div');
        repliesContainer.className = 'community-comment-replies ms-4';
        repliesContainer.setAttribute('data-replies-for', String(root.id));

        if (hasLoadedReplies) {
            repliesContainer.setAttribute('data-loaded', 'true');
            item.replies.forEach(reply => {
                const replyContainer = renderReplyItem(reply, root.id, postId, d);
                if (replyContainer) {
                    repliesContainer.appendChild(replyContainer);
                }
            });
        } else {
            repliesContainer.hidden = true;
        }

        threadEl.appendChild(repliesContainer);
        return threadEl;
    }

    function renderReplyItem(reply, rootId, postId, doc) {
        const d = doc || getDoc();
        const presentation = resolvePresentation();
        if (!presentation || !reply) return null;

        const replyContainer = d.createElement('div');
        replyContainer.className = 'community-comment-reply-container mt-2';
        replyContainer.setAttribute('data-comment-id', String(reply.id));

        const authorName = (reply.author && reply.author.displayName) ? reply.author.displayName : 'Người dùng';
        const primaryActions = [
            {
                key: 'reply',
                label: 'Phản hồi',
                className: 'btn btn-sm btn-link text-decoration-none p-0 text-secondary community-reply-btn',
                attributes: {
                    'data-action': 'reply',
                    'data-post-id': String(postId),
                    'data-comment-id': String(reply.id),
                    'data-root-id': String(rootId),
                    'data-author-name': authorName
                }
            }
        ];

        const replyDescriptor = {
            id: String(reply.id),
            tag: 'div',
            legacyPrefix: 'community-comment',
            className: 'community-comment--reply',
            tombstone: reply.tombstone === true,
            author: reply.author,
            createdAt: reply.createdAt,
            edited: reply.updatedAt && reply.createdAt && (new Date(reply.updatedAt) - new Date(reply.createdAt) > 1000),
            reactionSummary: reply.reactionSummary,
            body: reply.body || '',
            primaryActions: primaryActions
        };

        const cardEl = presentation.renderComment(replyDescriptor, d);
        if (cardEl) {
            replyContainer.appendChild(cardEl);
        }

        const replySlot = d.createElement('div');
        replySlot.className = 'community-reply-composer-slot';
        replySlot.setAttribute('data-reply-slot', String(reply.id));
        replyContainer.appendChild(replySlot);

        return replyContainer;
    }

    function buildComposerOrGuestPrompt(postId, doc) {
        const d = doc || getDoc();
        if (!d) return null;

        if (isUserAuthenticated()) {
            const composerEl = d.createElement('div');
            composerEl.className = 'community-comment-composer';
            composerEl.setAttribute('data-composer-root', String(postId));

            const innerEl = d.createElement('div');
            innerEl.className = 'community-comment-composer__inner';

            const textarea = d.createElement('textarea');
            textarea.className = 'community-comment-composer__input';
            textarea.placeholder = 'Viết bình luận...';
            textarea.setAttribute('placeholder', 'Viết bình luận...');
            textarea.setAttribute('rows', '1');
            textarea.setAttribute('maxlength', '2000');
            textarea.setAttribute('data-input-root', String(postId));

            const submitBtn = d.createElement('button');
            submitBtn.type = 'button';
            submitBtn.className = 'btn btn-sm btn-primary community-comment-composer__submit';
            submitBtn.setAttribute('data-action', 'submit-root-comment');
            submitBtn.setAttribute('data-post-id', String(postId));
            submitBtn.disabled = true;
            submitBtn.textContent = 'Gửi';

            textarea.addEventListener('input', function () {
                submitBtn.disabled = !textarea.value.trim();
            });

            innerEl.appendChild(textarea);
            innerEl.appendChild(submitBtn);
            composerEl.appendChild(innerEl);

            const errorEl = d.createElement('div');
            errorEl.className = 'community-comment-composer__error text-danger small mt-1';
            errorEl.setAttribute('data-error-root', String(postId));
            errorEl.hidden = true;
            composerEl.appendChild(errorEl);

            return composerEl;
        } else {
            const guestEl = d.createElement('div');
            guestEl.className = 'community-comment-guest-prompt';

            const loginLink = d.createElement('a');
            loginLink.className = 'btn btn-sm btn-outline-secondary';
            const loginUrl = '/login?returnTo=' + getReturnToUrl();
            loginLink.href = loginUrl;
            loginLink.setAttribute('href', loginUrl);
            loginLink.textContent = 'Đăng nhập để tham gia bình luận';

            guestEl.appendChild(loginLink);
            return guestEl;
        }
    }

    function buildThreadListAndMore(postId, doc) {
        const d = doc || getDoc();
        if (!d) return { listEl: null, moreContainer: null };

        const listEl = d.createElement('div');
        listEl.className = 'community-comments-list';
        listEl.setAttribute('data-thread-list', String(postId));

        const moreContainer = d.createElement('div');
        moreContainer.className = 'community-comments-more';
        moreContainer.setAttribute('data-more-container', String(postId));
        moreContainer.hidden = true;
        moreContainer.setAttribute('hidden', '');

        const moreBtn = d.createElement('button');
        moreBtn.type = 'button';
        moreBtn.className = 'btn btn-sm btn-link text-decoration-none community-comments-more-btn';
        moreBtn.setAttribute('data-action', 'load-more-comments');
        moreBtn.setAttribute('data-post-id', String(postId));
        moreBtn.textContent = 'Xem thêm bình luận';
        moreContainer.appendChild(moreBtn);

        return { listEl: listEl, moreContainer: moreContainer };
    }

    function initContainerShell(container, postId) {
        const d = getDoc();
        if (!d || !container) return;

        container.innerHTML = '';
        const composer = buildComposerOrGuestPrompt(postId, d);
        if (composer) container.appendChild(composer);

        const presentation = resolvePresentation();
        if (presentation && (typeof presentation.renderSortDropdown === 'function' || typeof presentation.renderSortControls === 'function')) {
            const renderer = presentation.renderSortDropdown || presentation.renderSortControls;
            const sortControls = renderer({
                currentSort: getCurrentSort(postId),
                targetId: String(postId),
                actionName: 'change-comment-sort'
            }, d);
            if (sortControls) container.appendChild(sortControls);
        }

        const { listEl, moreContainer } = buildThreadListAndMore(postId, d);
        if (listEl) container.appendChild(listEl);
        if (moreContainer) container.appendChild(moreContainer);
    }

    function initDrawerShell(drawer, postId) {
        const d = getDoc();
        if (!d || !drawer) return;

        const bodyEl = drawer.querySelector('[data-drawer-body]');
        const footerEl = drawer.querySelector('[data-drawer-footer]');

        if (bodyEl) {
            bodyEl.innerHTML = '';
            const presentation = resolvePresentation();
            if (presentation && (typeof presentation.renderSortDropdown === 'function' || typeof presentation.renderSortControls === 'function')) {
                const renderer = presentation.renderSortDropdown || presentation.renderSortControls;
                const sortControls = renderer({
                    currentSort: getCurrentSort(postId),
                    targetId: String(postId),
                    actionName: 'change-comment-sort'
                }, d);
                if (sortControls) bodyEl.appendChild(sortControls);
            }
            const { listEl, moreContainer } = buildThreadListAndMore(postId, d);
            if (listEl) bodyEl.appendChild(listEl);
            if (moreContainer) bodyEl.appendChild(moreContainer);
        }

        if (footerEl) {
            footerEl.innerHTML = '';
            const composer = buildComposerOrGuestPrompt(postId, d);
            if (composer) footerEl.appendChild(composer);
        }
    }

    function renderRootsIntoTarget(postId, targetEl, mode, isFullRefresh = false) {
        const d = getDoc();
        if (!d || !targetEl) return;
        const state = getPostState(postId);
        if (!state || !Array.isArray(state.roots)) return;

        const listEl = targetEl.querySelector('[data-thread-list="' + postId + '"]');
        const moreContainer = targetEl.querySelector('[data-more-container="' + postId + '"]');

        if (listEl) {
            const childCount = listEl.children ? listEl.children.length : (listEl.childNodes ? listEl.childNodes.length : 0);
            if (isFullRefresh || childCount === 0) {
                listEl.innerHTML = '';
                state.roots.forEach(item => {
                    const threadEl = renderRootCommentItem(item, postId, d);
                    if (threadEl) {
                        listEl.appendChild(threadEl);
                    }
                });
                hydrateComponents(listEl);
            }
        }

        if (moreContainer) {
            moreContainer.hidden = !state.hasNext;
            if (state.hasNext) {
                moreContainer.removeAttribute('hidden');
            } else {
                moreContainer.setAttribute('hidden', '');
            }
        }
    }

    function appendRootsIntoTarget(postId, targetEl, rawRoots) {
        const d = getDoc();
        if (!d || !targetEl || !Array.isArray(rawRoots)) return;
        const listEl = targetEl.querySelector('[data-thread-list="' + postId + '"]');
        const moreContainer = targetEl.querySelector('[data-more-container="' + postId + '"]');

        if (listEl) {
            rawRoots.forEach(item => {
                const threadEl = renderRootCommentItem(item, postId, d);
                if (threadEl) {
                    listEl.appendChild(threadEl);
                }
            });
            hydrateComponents(listEl);
        }

        const state = getPostState(postId);
        if (moreContainer && state) {
            moreContainer.hidden = !state.hasNext;
            if (state.hasNext) {
                moreContainer.removeAttribute('hidden');
            } else {
                moreContainer.setAttribute('hidden', '');
            }
        }
    }

    function getActiveTargets(postId) {
        const doc = getDoc();
        if (!doc) return [];
        const targets = [];

        const inlineContainers = doc.querySelectorAll('.post-comments-container[data-post-comments="' + postId + '"]');
        inlineContainers.forEach(container => {
            if (!container.hidden && !container.hasAttribute('hidden')) {
                targets.push({ el: container, mode: 'inline' });
            }
        });

        const { drawer } = getDrawerElements(doc);
        if (drawer && isDrawerOpen() && currentDrawerPostId === postId) {
            targets.push({ el: drawer, mode: 'drawer' });
        }

        return targets;
    }

    function renderCachedRoots(postId, doc) {
        const targets = getActiveTargets(postId);
        targets.forEach(t => {
            renderRootsIntoTarget(postId, t.el, t.mode, false);
        });
    }

    async function fetchComments(postId, page, targetMode, requestedSort) {
        const doc = getDoc();
        if (!doc) return;

        const sort = (requestedSort === SORT_MODES.NEWEST || requestedSort === SORT_MODES.FEATURED)
            ? requestedSort
            : getCurrentSort(postId);

        const cacheKey = String(postId) + ':' + sort;
        let state = postStates.get(cacheKey);
        if (!state) {
            state = { page: 0, hasNext: false, isLoading: false, isLoaded: false, roots: [] };
            postStates.set(cacheKey, state);
        }

        if (state.isLoading) return;
        state.isLoading = true;

        const fetchFn = getFetch();
        if (!fetchFn) {
            state.isLoading = false;
            return;
        }

        try {
            const resp = await fetchFn('/api/community/posts/' + encodeURIComponent(postId) + '/comments?page=' + page + '&size=10&sort=' + encodeURIComponent(sort), {
                headers: { 'Accept': 'application/json' }
            });

            if (!resp.ok) {
                throw new Error('Failed to load comments: ' + resp.status);
            }

            const data = await resp.json();
            state.page = data.page;
            state.hasNext = data.hasNext;
            state.isLoaded = true;

            const rawRoots = data.roots || data.threads || [];
            if (page === 0) {
                state.roots = Array.isArray(rawRoots) ? rawRoots : [];
            } else if (Array.isArray(rawRoots)) {
                state.roots = (state.roots || []).concat(rawRoots);
            }

            updateCommentCount(postId, data.commentCount);
            updateSortControlsActiveState(postId, sort);

            const targets = getActiveTargets(postId);
            if (targetMode === 'inline') {
                const inlineContainer = doc.querySelector('.post-comments-container[data-post-comments="' + postId + '"]');
                if (inlineContainer && !targets.some(t => t.el === inlineContainer)) {
                    targets.push({ el: inlineContainer, mode: 'inline' });
                }
            } else if (targetMode === 'drawer') {
                const { drawer } = getDrawerElements(doc);
                if (drawer && !targets.some(t => t.el === drawer)) {
                    targets.push({ el: drawer, mode: 'drawer' });
                }
            }

            if (page === 0) {
                targets.forEach(t => {
                    renderRootsIntoTarget(postId, t.el, t.mode, true);
                });
            } else {
                targets.forEach(t => {
                    appendRootsIntoTarget(postId, t.el, rawRoots);
                });
            }
        } catch (err) {
            console.error('Failed to load comments for post ' + postId, err);
            if (page === 0) {
                const targets = getActiveTargets(postId);
                if (targetMode === 'inline') {
                    const inlineContainer = doc.querySelector('.post-comments-container[data-post-comments="' + postId + '"]');
                    if (inlineContainer && !targets.some(t => t.el === inlineContainer)) {
                        targets.push({ el: inlineContainer, mode: 'inline' });
                    }
                } else if (targetMode === 'drawer') {
                    const { drawer } = getDrawerElements(doc);
                    if (drawer && !targets.some(t => t.el === drawer)) {
                        targets.push({ el: drawer, mode: 'drawer' });
                    }
                }

                targets.forEach(t => {
                    const listEl = t.el.querySelector('[data-thread-list="' + postId + '"]');
                    if (listEl) {
                        listEl.innerHTML = '';
                        const errDiv = doc.createElement('div');
                        errDiv.className = 'community-comments-error text-danger small p-2';
                        errDiv.textContent = 'Không thể tải bình luận. ';
                        const retryBtn = doc.createElement('button');
                        retryBtn.type = 'button';
                        retryBtn.className = 'btn btn-sm btn-link p-0';
                        retryBtn.setAttribute('data-action', 'retry-comments');
                        retryBtn.setAttribute('data-post-id', String(postId));
                        retryBtn.textContent = 'Thử lại';
                        errDiv.appendChild(retryBtn);
                        listEl.appendChild(errDiv);
                    }
                });
            }
        } finally {
            state.isLoading = false;
        }
    }

    async function toggleThreadReplies(postId, rootId) {
        const doc = getDoc();
        if (!doc || !postId || !rootId) return;

        const repliesContainers = doc.querySelectorAll('.community-comment-replies[data-replies-for="' + rootId + '"]');
        const toggleBtns = doc.querySelectorAll('.community-thread-toggle-btn[data-root-id="' + rootId + '"]');
        if (repliesContainers.length === 0 || toggleBtns.length === 0) return;

        const firstContainer = repliesContainers[0];
        const firstBtn = toggleBtns[0];
        const replyCount = firstBtn.getAttribute('data-reply-count') || '0';
        const isLoaded = firstContainer.getAttribute('data-loaded') === 'true';

        if (isLoaded) {
            const willHide = !firstContainer.hidden;
            repliesContainers.forEach(rc => { rc.hidden = willHide; });
            toggleBtns.forEach(btn => {
                btn.textContent = willHide ? ('Xem ' + replyCount + ' phản hồi') : 'Ẩn phản hồi';
            });
            return;
        }

        // Lazy fetch replies for this thread
        toggleBtns.forEach(btn => {
            btn.disabled = true;
            btn.textContent = 'Đang tải...';
        });

        const fetchFn = getFetch();
        if (!fetchFn) {
            toggleBtns.forEach(btn => {
                btn.disabled = false;
                btn.textContent = 'Xem ' + replyCount + ' phản hồi';
            });
            return;
        }

        try {
            const resp = await fetchFn('/api/community/posts/' + encodeURIComponent(postId) + '/comments/' + encodeURIComponent(rootId) + '/thread', {
                headers: { 'Accept': 'application/json' }
            });

            if (!resp.ok) {
                throw new Error('Failed to load thread: ' + resp.status);
            }

            const threadData = await resp.json();

            for (const sortMode of [SORT_MODES.FEATURED, SORT_MODES.NEWEST]) {
                const s = postStates.get(String(postId) + ':' + sortMode);
                if (s && Array.isArray(s.roots)) {
                    const idx = s.roots.findIndex(r => (r.root && String(r.root.id) === String(rootId)) || String(r.id) === String(rootId));
                    if (idx !== -1) {
                        s.roots[idx] = threadData;
                    }
                }
            }

            repliesContainers.forEach(rc => {
                rc.innerHTML = '';
                if (Array.isArray(threadData.replies)) {
                    threadData.replies.forEach(reply => {
                        const replyContainer = renderReplyItem(reply, rootId, postId, doc);
                        if (replyContainer) {
                            rc.appendChild(replyContainer);
                        }
                    });
                }
                rc.hidden = false;
                rc.setAttribute('data-loaded', 'true');
                hydrateComponents(rc);
            });

            toggleBtns.forEach(btn => {
                btn.textContent = 'Ẩn phản hồi';
                btn.disabled = false;
            });
        } catch (err) {
            console.error('Failed to load replies for root ' + rootId, err);
            toggleBtns.forEach(btn => {
                btn.textContent = 'Lỗi tải phản hồi. Thử lại';
                btn.disabled = false;
            });
        }
    }

    async function submitRootComment(postId) {
        const doc = getDoc();
        if (!doc || !postId) return;

        let input = null;
        let submitBtn = null;
        let errorEl = null;

        if (isDrawerOpen()) {
            const drawerFooter = doc.querySelector('[data-drawer-footer]');
            if (drawerFooter) {
                input = drawerFooter.querySelector('[data-input-root="' + postId + '"]');
                submitBtn = drawerFooter.querySelector('[data-action="submit-root-comment"][data-post-id="' + postId + '"]');
                errorEl = drawerFooter.querySelector('[data-error-root="' + postId + '"]');
            }
        }

        if (!input) {
            const container = doc.querySelector('[data-post-comments="' + postId + '"]');
            if (container) {
                input = container.querySelector('[data-input-root="' + postId + '"]');
                submitBtn = container.querySelector('[data-action="submit-root-comment"][data-post-id="' + postId + '"]');
                errorEl = container.querySelector('[data-error-root="' + postId + '"]');
            }
        }

        if (!input || !submitBtn) {
            input = doc.querySelector('[data-input-root="' + postId + '"]');
            submitBtn = doc.querySelector('[data-action="submit-root-comment"][data-post-id="' + postId + '"]');
            errorEl = doc.querySelector('[data-error-root="' + postId + '"]');
        }

        if (!input || !submitBtn) return;
        const bodyText = input.value.trim();
        if (!bodyText) return;

        submitBtn.disabled = true;
        if (errorEl) errorEl.hidden = true;

        const fetchFn = getFetch();
        if (!fetchFn) return;

        try {
            const resp = await fetchFn('/api/community/posts/' + encodeURIComponent(postId) + '/comments', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json',
                    [getCsrfHeader()]: getCsrfToken()
                },
                body: JSON.stringify({ body: bodyText })
            });

            if (!resp.ok) {
                const errJson = await resp.json().catch(() => null);
                throw new Error(errJson && errJson.message ? errJson.message : 'Không thể đăng bình luận.');
            }

            const result = await resp.json();
            const inputs = doc.querySelectorAll('[data-input-root="' + postId + '"]');
            inputs.forEach(inp => { inp.value = ''; });
            const submitBtns = doc.querySelectorAll('[data-action="submit-root-comment"][data-post-id="' + postId + '"]');
            submitBtns.forEach(b => { b.disabled = true; });
            updateCommentCount(postId, result.updatedCommentCount);

            // Invalidate loaded state across sort modes so fresh roots are re-rendered
            postStates.delete(String(postId) + ':FEATURED');
            postStates.delete(String(postId) + ':NEWEST');

            // Refresh canonical first root page to display new root comment at the top
            await fetchComments(postId, 0);
        } catch (err) {
            if (errorEl) {
                errorEl.textContent = err.message || 'Lỗi khi gửi bình luận.';
                errorEl.hidden = false;
            }
            submitBtn.disabled = false;
        }
    }

    function openReplyComposer(postId, commentId, rootId, authorName) {
        const doc = getDoc();
        if (!doc) return;

        if (!isUserAuthenticated()) {
            window.location.href = '/login?returnTo=' + getReturnToUrl();
            return;
        }

        const slot = doc.querySelector('[data-reply-slot="' + commentId + '"]');
        if (!slot) return;

        // If composer already open in this slot, focus it
        const existingInput = slot.querySelector('.community-reply-composer__input');
        if (existingInput) {
            existingInput.focus();
            return;
        }

        // Close any other open reply composers across the post
        doc.querySelectorAll('.community-reply-composer').forEach(el => el.remove());

        const composerEl = doc.createElement('div');
        composerEl.className = 'community-comment-composer community-reply-composer mt-2';

        const innerEl = doc.createElement('div');
        innerEl.className = 'community-comment-composer__inner';

        const textarea = doc.createElement('textarea');
        textarea.className = 'community-comment-composer__input community-reply-composer__input';
        textarea.placeholder = 'Phản hồi cho @' + authorName + '...';
        textarea.setAttribute('placeholder', 'Phản hồi cho @' + authorName + '...');
        textarea.setAttribute('rows', '1');
        textarea.setAttribute('maxlength', '2000');

        const btnGroup = doc.createElement('div');
        btnGroup.className = 'd-flex gap-1 align-self-end';

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'btn btn-sm btn-outline-secondary';
        cancelBtn.setAttribute('data-action', 'cancel-reply');
        cancelBtn.textContent = 'Hủy';

        const submitBtn = doc.createElement('button');
        submitBtn.type = 'button';
        submitBtn.className = 'btn btn-sm btn-primary';
        submitBtn.setAttribute('data-action', 'submit-reply');
        submitBtn.setAttribute('data-post-id', String(postId));
        submitBtn.setAttribute('data-comment-id', String(commentId));
        submitBtn.setAttribute('data-root-id', String(rootId));
        submitBtn.disabled = true;
        submitBtn.textContent = 'Gửi';

        textarea.addEventListener('input', function () {
            submitBtn.disabled = !textarea.value.trim();
        });

        btnGroup.appendChild(cancelBtn);
        btnGroup.appendChild(submitBtn);

        innerEl.appendChild(textarea);
        innerEl.appendChild(btnGroup);
        composerEl.appendChild(innerEl);

        const errorEl = doc.createElement('div');
        errorEl.className = 'community-comment-composer__error text-danger small mt-1';
        errorEl.hidden = true;
        composerEl.appendChild(errorEl);

        slot.appendChild(composerEl);
        textarea.focus();
    }

    async function submitReply(postId, commentId, rootId) {
        const doc = getDoc();
        if (!doc) return;

        const slot = doc.querySelector('[data-reply-slot="' + commentId + '"]');
        if (!slot) return;

        const input = slot.querySelector('.community-reply-composer__input');
        const submitBtn = slot.querySelector('[data-action="submit-reply"]');
        const errorEl = slot.querySelector('.community-comment-composer__error');

        if (!input || !submitBtn) return;
        const bodyText = input.value.trim();
        if (!bodyText) return;

        submitBtn.disabled = true;
        if (errorEl) errorEl.hidden = true;

        const fetchFn = getFetch();
        if (!fetchFn) return;

        try {
            const resp = await fetchFn('/api/community/posts/' + encodeURIComponent(postId) + '/comments/' + encodeURIComponent(commentId) + '/replies', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json',
                    [getCsrfHeader()]: getCsrfToken()
                },
                body: JSON.stringify({ body: bodyText })
            });

            if (!resp.ok) {
                const errJson = await resp.json().catch(() => null);
                throw new Error(errJson && errJson.message ? errJson.message : 'Không thể gửi phản hồi.');
            }

            const result = await resp.json();
            updateCommentCount(postId, result.updatedCommentCount);

            // Invalidate FEATURED cache since reply count / engagement score changed
            postStates.delete(String(postId) + ':FEATURED');

            // Remove composer only after success
            const composerEl = slot.querySelector('.community-reply-composer');
            if (composerEl) composerEl.remove();

            // Refresh ONLY affected thread
            await refreshThread(postId, rootId);
        } catch (err) {
            if (errorEl) {
                errorEl.textContent = err.message || 'Lỗi khi gửi phản hồi.';
                errorEl.hidden = false;
            }
            submitBtn.disabled = false;
        }
    }

    async function refreshThread(postId, rootId) {
        const doc = getDoc();
        if (!doc) return;

        const fetchFn = getFetch();
        if (!fetchFn) return;

        try {
            const resp = await fetchFn('/api/community/posts/' + encodeURIComponent(postId) + '/comments/' + encodeURIComponent(rootId) + '/thread', {
                headers: { 'Accept': 'application/json' }
            });

            if (!resp.ok) return;
            const threadData = await resp.json();

            for (const sortMode of [SORT_MODES.FEATURED, SORT_MODES.NEWEST]) {
                const s = postStates.get(String(postId) + ':' + sortMode);
                if (s && Array.isArray(s.roots)) {
                    const idx = s.roots.findIndex(r => (r.root && String(r.root.id) === String(rootId)) || String(r.id) === String(rootId));
                    if (idx !== -1) {
                        s.roots[idx] = threadData;
                    }
                }
            }

            const threadEls = doc.querySelectorAll('.community-comment-thread[data-thread-id="' + rootId + '"]');
            threadEls.forEach(threadEl => {
                const updatedThreadEl = renderRootCommentItem(threadData, postId, doc);
                if (updatedThreadEl && threadEl.parentNode) {
                    threadEl.parentNode.replaceChild(updatedThreadEl, threadEl);
                    hydrateComponents(updatedThreadEl);
                }
            });
        } catch (err) {
            console.error('Failed to refresh thread ' + rootId, err);
        }
    }

    function openCommentsDrawer(postId) {
        const doc = getDoc();
        if (!doc || !postId) return;

        const { drawer, backdrop } = ensureDrawerElements(doc);
        if (!drawer || !backdrop) return;

        if (isDrawerOpen() && currentDrawerPostId === postId) {
            closeCommentsDrawer();
            return;
        }

        if (typeof window !== 'undefined') {
            savedScrollY = window.scrollY || window.pageYOffset || (doc.documentElement && doc.documentElement.scrollTop) || 0;
        }

        currentDrawerPostId = postId;
        drawer.setAttribute('data-active-post-id', String(postId));

        if (doc.body) {
            doc.body.classList.add('has-community-comments-open');
        }

        drawer.hidden = false;
        drawer.removeAttribute('hidden');
        drawer.classList.add('is-open');
        backdrop.hidden = false;
        backdrop.removeAttribute('hidden');
        backdrop.classList.add('is-open');

        const btns = doc.querySelectorAll('[data-action="toggle-comments"][data-post-id="' + postId + '"]');
        btns.forEach(btn => {
            btn.setAttribute('aria-expanded', 'true');
            btn.classList.add('is-active');
        });

        initDrawerShell(drawer, postId);

        let state = getPostState(postId);
        if (!state || !state.isLoaded) {
            fetchComments(postId, 0, 'drawer');
        } else {
            renderRootsIntoTarget(postId, drawer, 'drawer', false);
        }
    }

    function closeCommentsDrawer() {
        const doc = getDoc();
        if (!doc) return;

        const { drawer, backdrop } = getDrawerElements(doc);
        if (drawer) {
            drawer.classList.remove('is-open');
            drawer.hidden = true;
            drawer.setAttribute('hidden', '');
            drawer.removeAttribute('data-active-post-id');
        }
        if (backdrop) {
            backdrop.classList.remove('is-open');
            backdrop.hidden = true;
            backdrop.setAttribute('hidden', '');
        }

        if (doc.body) {
            doc.body.classList.remove('has-community-comments-open');
        }

        if (currentDrawerPostId) {
            const btns = doc.querySelectorAll('[data-action="toggle-comments"][data-post-id="' + currentDrawerPostId + '"]');
            btns.forEach(btn => {
                btn.setAttribute('aria-expanded', 'false');
                btn.classList.remove('is-active');
            });
        }
        currentDrawerPostId = null;

        if (typeof window !== 'undefined') {
            if (typeof window.scrollTo === 'function') {
                window.scrollTo(0, savedScrollY);
            } else if (doc.documentElement) {
                doc.documentElement.scrollTop = savedScrollY;
            }
        }
    }

    function toggleInlineComments(postId, targetCard) {
        const doc = getDoc();
        if (!doc || !postId) return;

        let container = null;
        if (targetCard) {
            container = targetCard.querySelector('.post-comments-container[data-post-comments="' + postId + '"]') ||
                        targetCard.querySelector('[data-post-comments="' + postId + '"]');
        }
        if (!container) {
            container = doc.querySelector('[data-post-comments="' + postId + '"]');
        }

        let btn = null;
        if (targetCard) {
            btn = targetCard.querySelector('[data-action="toggle-comments"][data-post-id="' + postId + '"]');
        }
        if (!btn) {
            btn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + postId + '"]');
        }

        if (!container) return;

        const isCurrentlyHidden = container.hidden || container.hasAttribute('hidden');

        if (isCurrentlyHidden) {
            container.hidden = false;
            container.removeAttribute('hidden');
            if (btn) {
                btn.setAttribute('aria-expanded', 'true');
                btn.classList.add('is-active');
            }

            // Always guarantee container shell exists before rendering
            if (!container.querySelector('[data-thread-list="' + postId + '"]')) {
                initContainerShell(container, postId);
            }

            let state = getPostState(postId);
            if (!state || !state.isLoaded) {
                fetchComments(postId, 0, 'inline');
            } else {
                renderRootsIntoTarget(postId, container, 'inline', false);
            }
        } else {
            container.hidden = true;
            container.setAttribute('hidden', '');
            if (btn) {
                btn.setAttribute('aria-expanded', 'false');
                btn.classList.remove('is-active');
            }
        }
    }

    function toggleComments(postId, targetCard) {
        if (isMobileViewport()) {
            openCommentsDrawer(postId);
        } else {
            toggleInlineComments(postId, targetCard);
        }
    }

    function handleDelegatedClick(event) {
        const target = event.target;
        if (!target) return;

        // Change comment sort
        const sortBtn = target.closest('[data-action="change-comment-sort"]');
        if (sortBtn) {
            event.preventDefault();
            const postId = sortBtn.getAttribute('data-target-id') || sortBtn.getAttribute('data-post-id');
            const sortMode = sortBtn.getAttribute('data-sort-mode');
            if (postId && sortMode) {
                switchSort(postId, sortMode);
            }
            return;
        }

        // Close drawer button
        const closeDrawerBtn = target.closest('[data-action="close-comments-drawer"]');
        if (closeDrawerBtn) {
            event.preventDefault();
            closeCommentsDrawer();
            return;
        }

        // Backdrop click
        if (target.id === BACKDROP_ID || (target.classList && target.classList.contains('community-comments-backdrop'))) {
            event.preventDefault();
            closeCommentsDrawer();
            return;
        }

        // Toggle comments button
        const toggleBtn = target.closest('[data-action="toggle-comments"]');
        if (toggleBtn) {
            event.preventDefault();
            const postId = toggleBtn.getAttribute('data-post-id');
            const targetCard = toggleBtn.closest('.community-post-card');
            if (postId) toggleComments(postId, targetCard);
            return;
        }

        // Retry comments
        const retryBtn = target.closest('[data-action="retry-comments"]');
        if (retryBtn) {
            event.preventDefault();
            const postId = retryBtn.getAttribute('data-post-id');
            if (postId) fetchComments(postId, 0);
            return;
        }

        // Toggle thread replies
        const toggleThreadBtn = target.closest('[data-action="toggle-thread"]');
        if (toggleThreadBtn) {
            event.preventDefault();
            const postId = toggleThreadBtn.getAttribute('data-post-id');
            const rootId = toggleThreadBtn.getAttribute('data-root-id');
            if (postId && rootId) toggleThreadReplies(postId, rootId);
            return;
        }

        // Load more comments
        const moreBtn = target.closest('[data-action="load-more-comments"]');
        if (moreBtn) {
            event.preventDefault();
            const postId = moreBtn.getAttribute('data-post-id');
            const state = getPostState(postId);
            if (postId && state) {
                fetchComments(postId, state.page + 1);
            }
            return;
        }

        // Submit root comment
        const submitRootBtn = target.closest('[data-action="submit-root-comment"]');
        if (submitRootBtn) {
            event.preventDefault();
            const postId = submitRootBtn.getAttribute('data-post-id');
            if (postId) submitRootComment(postId);
            return;
        }

        // Reply button
        const replyBtn = target.closest('[data-action="reply"]');
        if (replyBtn) {
            event.preventDefault();
            const postId = replyBtn.getAttribute('data-post-id');
            const commentId = replyBtn.getAttribute('data-comment-id');
            const rootId = replyBtn.getAttribute('data-root-id');
            const authorName = replyBtn.getAttribute('data-author-name') || 'Người dùng';
            if (postId && commentId && rootId) {
                openReplyComposer(postId, commentId, rootId, authorName);
            }
            return;
        }

        // Cancel reply
        const cancelReplyBtn = target.closest('[data-action="cancel-reply"]');
        if (cancelReplyBtn) {
            event.preventDefault();
            const composerEl = cancelReplyBtn.closest('.community-reply-composer');
            if (composerEl) composerEl.remove();
            return;
        }

        // Submit reply
        const submitReplyBtn = target.closest('[data-action="submit-reply"]');
        if (submitReplyBtn) {
            event.preventDefault();
            const postId = submitReplyBtn.getAttribute('data-post-id');
            const commentId = submitReplyBtn.getAttribute('data-comment-id');
            const rootId = submitReplyBtn.getAttribute('data-root-id');
            if (postId && commentId && rootId) {
                submitReply(postId, commentId, rootId);
            }
            return;
        }
    }

    function handleKeydown(event) {
        if (!event) return;
        if (event.key === 'Escape' || event.key === 'Esc' || event.keyCode === 27) {
            if (isDrawerOpen()) {
                closeCommentsDrawer();
            }
        }
    }

    function init(options) {
        if (isInitialized && (!options || !options.force)) return;
        const opts = options || {};
        if (opts.fetch) injectedFetch = opts.fetch;
        if (opts.isMobile !== undefined) setMobileViewport(opts.isMobile);

        const doc = getDoc();
        if (doc) {
            doc.addEventListener('click', handleDelegatedClick);
            doc.addEventListener('keydown', handleKeydown);
            doc.addEventListener('kiemlai:reaction-updated', handleReactionUpdated);
        }
        isInitialized = true;
    }

    function reset() {
        closeCommentsDrawer();
        postStates.clear();
        postCurrentSort.clear();
        const doc = getDoc();
        if (doc) {
            doc.removeEventListener('click', handleDelegatedClick);
            doc.removeEventListener('keydown', handleKeydown);
            doc.removeEventListener('kiemlai:reaction-updated', handleReactionUpdated);
            const { drawer, backdrop } = getDrawerElements(doc);
            if (drawer && drawer.parentNode) drawer.parentNode.removeChild(drawer);
            if (backdrop && backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
            if (doc.body) {
                doc.body.classList.remove('has-community-comments-open');
            }
        }
        isInitialized = false;
        injectedFetch = null;
        injectedIsMobile = null;
        savedScrollY = 0;
        currentDrawerPostId = null;
    }

    // Auto-init in browser DOM environment
    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () { init(); });
        } else {
            init();
        }
    }

    return {
        init: init,
        reset: reset,
        toggleComments: toggleComments,
        openCommentsDrawer: openCommentsDrawer,
        closeCommentsDrawer: closeCommentsDrawer,
        isDrawerOpen: isDrawerOpen,
        setMobileViewport: setMobileViewport,
        isMobileViewport: isMobileViewport,
        fetchComments: fetchComments,
        switchSort: switchSort,
        getCurrentSort: getCurrentSort,
        SORT_MODES: SORT_MODES,
        toggleThreadReplies: toggleThreadReplies,
        submitRootComment: submitRootComment,
        openReplyComposer: openReplyComposer,
        submitReply: submitReply,
        refreshThread: refreshThread,
        updateCommentCount: updateCommentCount,
        getPostState: getPostState,
        _states: postStates
    };
});
