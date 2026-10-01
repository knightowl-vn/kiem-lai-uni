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

    const postStates = new Map(); // postId -> { page, hasNext, isLoading, isLoaded, roots }
    let injectedFetch = null;
    let isInitialized = false;

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

    function initContainerShell(container, postId) {
        const d = getDoc();
        if (!d || !container) return;

        container.innerHTML = '';

        // Composer or Guest Prompt
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

            container.appendChild(composerEl);
        } else {
            const guestEl = d.createElement('div');
            guestEl.className = 'community-comment-guest-prompt';

            const loginLink = d.createElement('a');
            loginLink.className = 'btn btn-sm btn-outline-secondary';
            loginLink.href = '/login?returnTo=' + getReturnToUrl();
            loginLink.textContent = 'Đăng nhập để tham gia bình luận';

            guestEl.appendChild(loginLink);
            container.appendChild(guestEl);
        }

        // Thread List Container
        const listEl = d.createElement('div');
        listEl.className = 'community-comments-list';
        listEl.setAttribute('data-thread-list', String(postId));
        container.appendChild(listEl);

        // Load More Container
        const moreContainer = d.createElement('div');
        moreContainer.className = 'community-comments-more';
        moreContainer.setAttribute('data-more-container', String(postId));
        moreContainer.hidden = true;

        const moreBtn = d.createElement('button');
        moreBtn.type = 'button';
        moreBtn.className = 'btn btn-sm btn-link text-decoration-none community-comments-more-btn';
        moreBtn.setAttribute('data-action', 'load-more-comments');
        moreBtn.setAttribute('data-post-id', String(postId));
        moreBtn.textContent = 'Xem thêm bình luận';
        moreContainer.appendChild(moreBtn);

        container.appendChild(moreContainer);
    }

    async function fetchComments(postId, page) {
        const doc = getDoc();
        if (!doc) return;

        let state = postStates.get(postId);
        if (!state) {
            state = { page: 0, hasNext: false, isLoading: false, isLoaded: false, roots: [] };
            postStates.set(postId, state);
        }

        if (state.isLoading) return;
        state.isLoading = true;

        const container = doc.querySelector('[data-post-comments="' + postId + '"]');
        if (!container) return;

        const listEl = container.querySelector('[data-thread-list="' + postId + '"]');
        const moreContainer = container.querySelector('[data-more-container="' + postId + '"]');

        const fetchFn = getFetch();
        if (!fetchFn) {
            state.isLoading = false;
            return;
        }

        try {
            const resp = await fetchFn('/api/community/posts/' + encodeURIComponent(postId) + '/comments?page=' + page + '&size=10', {
                headers: { 'Accept': 'application/json' }
            });

            if (!resp.ok) {
                throw new Error('Failed to load comments: ' + resp.status);
            }

            const data = await resp.json();
            state.page = data.page;
            state.hasNext = data.hasNext;
            state.isLoaded = true;

            updateCommentCount(postId, data.commentCount);

            if (page === 0 && listEl) {
                listEl.innerHTML = '';
            }

            const rawRoots = data.roots || data.threads || [];
            if (Array.isArray(rawRoots) && listEl) {
                rawRoots.forEach(item => {
                    const threadEl = renderRootCommentItem(item, postId, doc);
                    if (threadEl) {
                        listEl.appendChild(threadEl);
                    }
                });
            }

            if (moreContainer) {
                moreContainer.hidden = !state.hasNext;
            }

            hydrateComponents(container);
        } catch (err) {
            console.error('Failed to load comments for post ' + postId, err);
            if (page === 0 && listEl) {
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
        } finally {
            state.isLoading = false;
        }
    }

    async function toggleThreadReplies(postId, rootId) {
        const doc = getDoc();
        if (!doc || !postId || !rootId) return;

        const repliesContainer = doc.querySelector('.community-comment-replies[data-replies-for="' + rootId + '"]');
        const toggleBtn = doc.querySelector('.community-thread-toggle-btn[data-root-id="' + rootId + '"]');
        if (!repliesContainer || !toggleBtn) return;

        const replyCount = toggleBtn.getAttribute('data-reply-count') || '0';
        const isLoaded = repliesContainer.getAttribute('data-loaded') === 'true';

        if (isLoaded) {
            // Already loaded, just toggle visibility
            if (repliesContainer.hidden) {
                repliesContainer.hidden = false;
                toggleBtn.textContent = 'Ẩn phản hồi';
            } else {
                repliesContainer.hidden = true;
                toggleBtn.textContent = 'Xem ' + replyCount + ' phản hồi';
            }
            return;
        }

        // Lazy fetch replies for this thread
        toggleBtn.disabled = true;
        toggleBtn.textContent = 'Đang tải...';

        const fetchFn = getFetch();
        if (!fetchFn) {
            toggleBtn.disabled = false;
            toggleBtn.textContent = 'Xem ' + replyCount + ' phản hồi';
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
            repliesContainer.innerHTML = '';
            if (Array.isArray(threadData.replies)) {
                threadData.replies.forEach(reply => {
                    const replyContainer = renderReplyItem(reply, rootId, postId, doc);
                    if (replyContainer) {
                        repliesContainer.appendChild(replyContainer);
                    }
                });
            }

            repliesContainer.hidden = false;
            repliesContainer.setAttribute('data-loaded', 'true');
            toggleBtn.textContent = 'Ẩn phản hồi';
            hydrateComponents(repliesContainer);
        } catch (err) {
            console.error('Failed to load replies for root ' + rootId, err);
            toggleBtn.textContent = 'Lỗi tải phản hồi. Thử lại';
        } finally {
            toggleBtn.disabled = false;
        }
    }

    async function submitRootComment(postId) {
        const doc = getDoc();
        if (!doc || !postId) return;

        const container = doc.querySelector('[data-post-comments="' + postId + '"]');
        if (!container) return;

        const input = container.querySelector('[data-input-root="' + postId + '"]');
        const submitBtn = container.querySelector('[data-action="submit-root-comment"][data-post-id="' + postId + '"]');
        const errorEl = container.querySelector('[data-error-root="' + postId + '"]');

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
            input.value = '';
            submitBtn.disabled = true;

            updateCommentCount(postId, result.updatedCommentCount);

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

            const threadEl = doc.querySelector('.community-comment-thread[data-thread-id="' + rootId + '"]');
            if (threadEl) {
                const updatedThreadEl = renderRootCommentItem(threadData, postId, doc);
                if (updatedThreadEl && threadEl.parentNode) {
                    threadEl.parentNode.replaceChild(updatedThreadEl, threadEl);
                    hydrateComponents(updatedThreadEl);
                }
            }
        } catch (err) {
            console.error('Failed to refresh thread ' + rootId, err);
        }
    }

    function toggleComments(postId) {
        const doc = getDoc();
        if (!doc || !postId) return;

        const container = doc.querySelector('[data-post-comments="' + postId + '"]');
        const btn = doc.querySelector('[data-action="toggle-comments"][data-post-id="' + postId + '"]');
        if (!container) return;

        if (container.hidden) {
            container.hidden = false;
            if (btn) {
                btn.setAttribute('aria-expanded', 'true');
                btn.classList.add('is-active');
            }

            let state = postStates.get(postId);
            if (!state || !state.isLoaded) {
                initContainerShell(container, postId);
                fetchComments(postId, 0);
            }
        } else {
            container.hidden = true;
            if (btn) {
                btn.setAttribute('aria-expanded', 'false');
                btn.classList.remove('is-active');
            }
        }
    }

    function handleDelegatedClick(event) {
        const target = event.target;
        if (!target) return;

        // Toggle comments button
        const toggleBtn = target.closest('[data-action="toggle-comments"]');
        if (toggleBtn) {
            event.preventDefault();
            const postId = toggleBtn.getAttribute('data-post-id');
            if (postId) toggleComments(postId);
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
            const state = postStates.get(postId);
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

    function init(options) {
        if (isInitialized && (!options || !options.force)) return;
        const opts = options || {};
        if (opts.fetch) injectedFetch = opts.fetch;

        const doc = getDoc();
        if (doc) {
            doc.addEventListener('click', handleDelegatedClick);
        }
        isInitialized = true;
    }

    function reset() {
        postStates.clear();
        const doc = getDoc();
        if (doc) {
            doc.removeEventListener('click', handleDelegatedClick);
        }
        isInitialized = false;
        injectedFetch = null;
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
        fetchComments: fetchComments,
        toggleThreadReplies: toggleThreadReplies,
        submitRootComment: submitRootComment,
        openReplyComposer: openReplyComposer,
        submitReply: submitReply,
        refreshThread: refreshThread,
        updateCommentCount: updateCommentCount,
        _states: postStates
    };
});
