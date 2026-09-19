/**
 * Kiem Lai Wiki — Article Discussion / Comments Module
 *
 * MS-05E6C: Article-level discussion frontend module for public Wiki articles.
 * Reuses Interaction backend endpoints:
 *   GET    /api/wiki/articles/{articleId}/comments?page={page}&size={size}
 *   GET    /api/wiki/articles/{articleId}/comments/{rootCommentId}/thread
 *   POST   /api/wiki/articles/{articleId}/comments
 *   POST   /api/wiki/articles/{articleId}/comments/{parentCommentId}/replies
 *   PATCH  /api/wiki/articles/{articleId}/comments/{commentId}
 *   DELETE /api/wiki/articles/{articleId}/comments/{commentId}
 */
(function (root, factory) {
    if (typeof define === 'function' && define.amd) {
        define([], factory);
    } else if (typeof module === 'object' && module.exports) {
        module.exports = factory();
    } else {
        root.WikiArticleComments = factory();
    }
}(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    const SECTION_ID = 'wikiDiscussion';
    const COUNT_BADGE_ID = 'wikiDiscussionCountBadge';
    const ROOT_COMPOSER_FORM_ID = 'wikiRootComposerForm';
    const ROOT_COMPOSER_INPUT_ID = 'wikiRootComposerInput';
    const ROOT_COMPOSER_SUBMIT_ID = 'wikiRootComposerSubmit';
    const ROOT_COMPOSER_ERROR_ID = 'wikiRootComposerError';
    const STATUS_ID = 'wikiDiscussionStatus';
    const THREAD_LIST_ID = 'wikiDiscussionThreadList';
    const FOOTER_ID = 'wikiDiscussionFooter';
    const LOAD_MORE_BTN_ID = 'wikiDiscussionLoadMoreBtn';

    const DEFAULT_PAGE_SIZE = 20;

    // Module State
    let currentDoc = null;
    let articleId = null;
    let isAuthenticated = false;
    let loginUrl = '/login';
    let csrfToken = '';
    let csrfHeader = 'X-CSRF-TOKEN';

    let currentPage = 0;
    let hasNext = false;
    let isLoading = false;
    let isMutating = false;
    let loadToken = 0;

    let currentThreads = [];
    let renderedRootIds = new Set();
    let threadCount = 0;
    let commentCount = 0;

    // Injected implementations for testing
    let injectedFetch = null;
    let injectedConfirm = null;

    /**
     * Resets all module internal state.
     */
    function resetState() {
        currentDoc = null;
        articleId = null;
        isAuthenticated = false;
        loginUrl = '/login';
        csrfToken = '';
        csrfHeader = 'X-CSRF-TOKEN';
        currentPage = 0;
        hasNext = false;
        isLoading = false;
        isMutating = false;
        loadToken = 0;
        currentThreads = [];
        renderedRootIds.clear();
        threadCount = 0;
        commentCount = 0;
        injectedFetch = null;
        injectedConfirm = null;
    }

    /**
     * Executes fetch using injected implementation or global fetch.
     */
    function doFetch(url, options) {
        if (typeof injectedFetch === 'function') {
            return injectedFetch(url, options);
        }
        return fetch(url, options);
    }

    /**
     * Prompts confirmation using injected confirm or global window.confirm.
     */
    function doConfirm(message) {
        if (typeof injectedConfirm === 'function') {
            return injectedConfirm(message);
        }
        if (typeof window !== 'undefined' && typeof window.confirm === 'function') {
            return window.confirm(message);
        }
        return true;
    }

    /**
     * Validates whether a redirect URL is an allowed same-origin security destination.
     * Matches the proven Wiki Save pattern.
     */
    function getValidSecurityRedirectUrl(targetUrl, currentHref) {
        if (!targetUrl || typeof targetUrl !== 'string') {
            return null;
        }
        try {
            const baseHref = currentHref || (typeof window !== 'undefined' && window.location ? window.location.href : 'http://localhost');
            const parsed = new URL(targetUrl, baseHref);
            const currentOrigin = new URL(baseHref).origin;

            if (parsed.origin !== currentOrigin) {
                return null;
            }
            if (parsed.pathname === '/login' || parsed.pathname === '/access-denied') {
                return parsed.href;
            }
            return null;
        } catch (_) {
            return null;
        }
    }

    /**
     * Sanitizes an avatar URL ensuring safe protocols (http, https, or same-origin path).
     */
    function sanitizeAvatarUrl(url) {
        if (typeof url !== 'string') {
            return null;
        }
        const trimmed = url.trim();
        if (!trimmed) {
            return null;
        }
        const lower = trimmed.toLowerCase();
        if (lower.startsWith('https://') || lower.startsWith('http://')) {
            return trimmed;
        }
        if (lower.startsWith('/') && !lower.startsWith('//')) {
            return trimmed;
        }
        return null;
    }

    /**
     * Creates an avatar fallback element with the first initial of the display name.
     */
    function createAvatarFallback(displayName, doc) {
        const fallback = doc.createElement('span');
        fallback.className = 'wiki-comment-avatar wiki-comment-avatar--fallback';
        fallback.setAttribute('aria-hidden', 'true');
        const trimmed = (typeof displayName === 'string') ? displayName.trim() : '';
        const firstChar = trimmed ? trimmed.charAt(0).toUpperCase() : 'U';
        fallback.textContent = firstChar;
        return fallback;
    }

    /**
     * Formats timestamp to localized human-readable string (DD/MM/YYYY HH:mm).
     */
    function formatTimestamp(isoString) {
        if (!isoString || typeof isoString !== 'string') {
            return '';
        }
        try {
            const date = new Date(isoString);
            if (isNaN(date.getTime())) {
                return '';
            }
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            return day + '/' + month + '/' + year + ' ' + hours + ':' + minutes;
        } catch (_) {
            return '';
        }
    }

    /**
     * Formats comment count label (e.g. '3 bình luận').
     */
    function formatCommentCount(count) {
        const num = Number(count);
        if (!Number.isSafeInteger(num) || num < 0) {
            return '0 bình luận';
        }
        return num + ' bình luận';
    }

    /**
     * Checks if a comment has been edited.
     */
    function isCommentEdited(comment) {
        if (!comment || typeof comment !== 'object') {
            return false;
        }
        if (comment.edited === true) {
            return true;
        }
        if (!comment.createdAt || !comment.updatedAt) {
            return false;
        }
        try {
            const createdMs = new Date(comment.createdAt).getTime();
            const updatedMs = new Date(comment.updatedAt).getTime();
            return Number.isFinite(createdMs) && Number.isFinite(updatedMs) && updatedMs > createdMs;
        } catch (_) {
            return false;
        }
    }

    /**
     * Safely clears all children of a container.
     */
    function clearElement(container) {
        if (!container) return;
        if (typeof container.replaceChildren === 'function') {
            container.replaceChildren();
        } else {
            while (container.firstChild) {
                container.removeChild(container.firstChild);
            }
        }
    }

    /**
     * Builds request headers including CSRF token if available.
     */
    function buildHeaders(includeJsonContent = true) {
        const headers = {};
        if (includeJsonContent) {
            headers['Content-Type'] = 'application/json';
        }
        if (csrfHeader && csrfToken) {
            headers[csrfHeader] = csrfToken;
        }
        return headers;
    }

    /**
     * Resolves DOM elements for the Wiki discussion section.
     */
    function getElements(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) {
            return {};
        }
        return {
            sectionEl: d.getElementById(SECTION_ID),
            countBadgeEl: d.getElementById(COUNT_BADGE_ID),
            composerFormEl: d.getElementById(ROOT_COMPOSER_FORM_ID),
            composerInputEl: d.getElementById(ROOT_COMPOSER_INPUT_ID),
            composerSubmitEl: d.getElementById(ROOT_COMPOSER_SUBMIT_ID),
            composerErrorEl: d.getElementById(ROOT_COMPOSER_ERROR_ID),
            statusEl: d.getElementById(STATUS_ID),
            threadListEl: d.getElementById(THREAD_LIST_ID),
            footerEl: d.getElementById(FOOTER_ID),
            loadMoreBtnEl: d.getElementById(LOAD_MORE_BTN_ID)
        };
    }

    /**
     * Updates the discussion header count badge authoritatively.
     */
    function updateCountBadge(count, doc) {
        const els = getElements(doc);
        if (els.countBadgeEl) {
            els.countBadgeEl.textContent = formatCommentCount(count);
        }
    }

    /**
     * Displays a neutral user-facing error message in the discussion status region.
     */
    function showDiscussionError(message, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--error';
            els.statusEl.textContent = message;
        }
    }

    /**
     * Clears the discussion status region.
     */
    function clearDiscussionStatus(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status';
        }
    }

    /**
     * Redirects unauthenticated guest to login URL safely.
     */
    function redirectToLogin(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const currentHref = (d && d.defaultView && d.defaultView.location) ? d.defaultView.location.href : (typeof window !== 'undefined' && window.location ? window.location.href : '');
        const validUrl = getValidSecurityRedirectUrl(loginUrl, currentHref);
        const destination = validUrl || loginUrl || '/login';
        if (typeof window !== 'undefined' && window.location) {
            window.location.href = destination;
        } else if (d && d.defaultView && d.defaultView.location) {
            d.defaultView.location.href = destination;
        }
    }

    /**
     * Authoritatively refreshes total article discussion metrics.
     */
    async function refreshMetrics(doc) {
        if (!articleId) return;
        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments?page=0&size=1';
            const res = await doFetch(url, { method: 'GET' });
            if (res && res.status === 200) {
                const data = await res.json();
                if (data && typeof data.commentCount === 'number') {
                    commentCount = data.commentCount;
                    threadCount = data.threadCount;
                    updateCountBadge(commentCount, doc);
                }
            }
        } catch (_) {
            // Ignore metrics background failure
        }
    }

    /**
     * Loads the Wiki discussion feed from the server.
     */
    async function loadDiscussionFeed(page = 0, append = false, doc = null, force = false) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);
        if (!els.sectionEl || !articleId) {
            return;
        }

        if (isLoading && append && !force) {
            return;
        }

        const currentToken = ++loadToken;
        isLoading = true;

        if (els.threadListEl) {
            els.threadListEl.setAttribute('aria-busy', 'true');
        }

        if (!append && els.statusEl) {
            clearElement(els.statusEl);
            els.statusEl.className = 'wiki-discussion-status';
            els.statusEl.textContent = 'Đang tải thảo luận...';
        }

        if (els.loadMoreBtnEl) {
            els.loadMoreBtnEl.disabled = true;
        }

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments?page=' + page + '&size=' + DEFAULT_PAGE_SIZE;
            const response = await doFetch(url, { method: 'GET' });

            if (currentToken !== loadToken) {
                return; // Discard stale response
            }

            if (!response || response.status !== 200) {
                if (els.statusEl) {
                    clearElement(els.statusEl);
                    els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--error';
                    els.statusEl.textContent = 'Không thể tải bình luận. Vui lòng thử lại sau.';
                }
                return;
            }

            const data = await response.json();
            if (currentToken !== loadToken) {
                return;
            }

            currentPage = data.page || 0;
            hasNext = Boolean(data.hasNext);
            threadCount = data.threadCount || 0;
            commentCount = data.commentCount || 0;

            updateCountBadge(commentCount, d);

            if (!append) {
                currentThreads = [];
                renderedRootIds.clear();
                if (els.threadListEl) {
                    clearElement(els.threadListEl);
                }
            }

            const newThreads = Array.isArray(data.threads) ? data.threads : [];

            if (newThreads.length === 0 && !append) {
                if (els.statusEl) {
                    clearElement(els.statusEl);
                    els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--empty';
                    els.statusEl.textContent = 'Chưa có bình luận nào. Hãy là người đầu tiên thảo luận!';
                }
                if (els.footerEl) {
                    els.footerEl.hidden = true;
                }
                return;
            }

            // Populated threads
            if (els.statusEl) {
                clearElement(els.statusEl);
                els.statusEl.className = 'wiki-discussion-status';
            }

            if (els.threadListEl) {
                for (let i = 0; i < newThreads.length; i++) {
                    const t = newThreads[i];
                    if (!t || !t.root || !t.root.id) continue;
                    const rootIdStr = String(t.root.id);
                    if (renderedRootIds.has(rootIdStr)) {
                        continue; // Prevent duplicate roots
                    }
                    renderedRootIds.add(rootIdStr);
                    currentThreads.push(t);

                    const threadEl = renderThread(t, d);
                    els.threadListEl.appendChild(threadEl);
                }
            }

            if (els.footerEl) {
                els.footerEl.hidden = !hasNext;
            }
        } catch (err) {
            if (currentToken !== loadToken) return;
            if (els.statusEl) {
                clearElement(els.statusEl);
                els.statusEl.className = 'wiki-discussion-status wiki-discussion-status--error';
                els.statusEl.textContent = 'Lỗi kết nối khi tải thảo luận. Vui lòng thử lại sau.';
            }
        } finally {
            if (currentToken === loadToken) {
                isLoading = false;
                if (els.threadListEl) {
                    els.threadListEl.setAttribute('aria-busy', 'false');
                }
                if (els.loadMoreBtnEl) {
                    els.loadMoreBtnEl.disabled = false;
                }
            }
        }
    }

    /**
     * Authoritatively refreshes a single thread by rootCommentId in-place.
     */
    async function refreshThread(rootCommentId, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!articleId || !rootCommentId || !d) return;

        const strRootId = String(rootCommentId);
        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(strRootId) + '/thread';
            const res = await doFetch(url, { method: 'GET' });

            const els = getElements(d);
            const threadEl = d.querySelector('[data-thread-id="' + strRootId + '"]');

            if (res && res.status === 200) {
                const freshThread = await res.json();
                if (!freshThread || !freshThread.root) return;

                // Update internal thread list
                const idx = currentThreads.findIndex(t => t && t.root && String(t.root.id) === strRootId);
                if (idx >= 0) {
                    currentThreads[idx] = freshThread;
                }

                if (threadEl) {
                    const newThreadEl = renderThread(freshThread, d);
                    if (threadEl.parentNode) {
                        threadEl.parentNode.replaceChild(newThreadEl, threadEl);
                    }
                }
            } else if (res && res.status === 404) {
                // Thread is deleted / pruned
                if (threadEl && threadEl.parentNode) {
                    threadEl.parentNode.removeChild(threadEl);
                }
                renderedRootIds.delete(strRootId);
                currentThreads = currentThreads.filter(t => t && t.root && String(t.root.id) !== strRootId);
            } else {
                showDiscussionError('Không thể cập nhật thảo luận. Vui lòng tải lại trang.', d);
            }
        } catch (err) {
            showDiscussionError('Lỗi kết nối khi cập nhật thảo luận. Vui lòng thử lại.', d);
        }
    }

    /**
     * Renders author presentation safely (avatar image or fallback + display name).
     */
    function renderAuthorPresentation(headerEl, author, doc) {
        const authorObj = (author && typeof author === 'object') ? author : null;
        const rawName = (authorObj && typeof authorObj.displayName === 'string') ? authorObj.displayName.trim() : '';
        const displayName = rawName || 'Người dùng';
        const rawAvatar = (authorObj && typeof authorObj.avatarUrl === 'string') ? authorObj.avatarUrl.trim() : '';
        const sanitizedAvatar = sanitizeAvatarUrl(rawAvatar);

        if (sanitizedAvatar) {
            const avatarImg = doc.createElement('img');
            avatarImg.className = 'wiki-comment-avatar';
            avatarImg.src = sanitizedAvatar;
            avatarImg.setAttribute('src', sanitizedAvatar);
            avatarImg.alt = displayName;
            avatarImg.setAttribute('alt', displayName);
            avatarImg.setAttribute('referrerpolicy', 'no-referrer');
            avatarImg.onerror = function () {
                const parent = avatarImg.parentNode;
                if (parent) {
                    const fallback = createAvatarFallback(displayName, doc);
                    if (typeof parent.replaceChild === 'function') {
                        parent.replaceChild(fallback, avatarImg);
                    } else if (typeof parent.removeChild === 'function') {
                        parent.removeChild(avatarImg);
                        parent.appendChild(fallback);
                    }
                }
            };
            headerEl.appendChild(avatarImg);
        } else {
            headerEl.appendChild(createAvatarFallback(displayName, doc));
        }

        const authorSpan = doc.createElement('span');
        authorSpan.className = 'wiki-comment-author';
        authorSpan.textContent = displayName;
        headerEl.appendChild(authorSpan);
    }

    /**
     * Resolves the immediate parent author display name for a flat reply.
     */
    function resolveImmediateParentDisplayName(reply, rootItem, replies) {
        if (!reply || !reply.parentCommentId || !rootItem) {
            return null;
        }
        const strParentId = String(reply.parentCommentId).trim();
        const strRootId = String(rootItem.id).trim();

        if (strParentId === strRootId) {
            // Replying directly to root
            return null; // Root reply doesn't need @RootAuthor mention
        }

        if (Array.isArray(replies)) {
            for (let i = 0; i < replies.length; i++) {
                const cand = replies[i];
                if (cand && String(cand.id).trim() === strParentId) {
                    if (cand.tombstone !== true && cand.author && cand.author.displayName) {
                        return cand.author.displayName.trim() || null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Renders a comment item (root or reply) safely.
     */
    function renderComment(comment, rootCommentId, thread, isReply, doc) {
        const commentEl = doc.createElement('div');
        commentEl.className = isReply ? 'wiki-comment wiki-comment--reply' : 'wiki-comment wiki-comment--root';
        const strCommentId = String(comment.id);
        commentEl.setAttribute('data-comment-id', strCommentId);
        if (isReply) {
            commentEl.setAttribute('data-reply-id', strCommentId);
        }

        // Tombstone check
        if (comment.tombstone === true) {
            commentEl.classList.add('is-tombstone');

            const tombstoneBody = doc.createElement('div');
            tombstoneBody.className = 'wiki-comment-body wiki-comment-body--tombstone';
            tombstoneBody.textContent = 'Bình luận đã bị xóa.';
            commentEl.appendChild(tombstoneBody);
            return commentEl;
        }

        // Active comment
        const headerEl = doc.createElement('header');
        headerEl.className = 'wiki-comment-header';

        renderAuthorPresentation(headerEl, comment.author, doc);

        const timeStr = formatTimestamp(comment.createdAt);
        if (timeStr) {
            const timeEl = doc.createElement('time');
            timeEl.className = 'wiki-comment-time';
            timeEl.setAttribute('datetime', String(comment.createdAt));
            timeEl.textContent = timeStr;
            headerEl.appendChild(timeEl);
        }

        if (isCommentEdited(comment)) {
            const editedSpan = doc.createElement('span');
            editedSpan.className = 'wiki-comment-edited';
            editedSpan.textContent = 'đã chỉnh sửa';
            headerEl.appendChild(editedSpan);
        }

        // Action buttons
        const actionsContainer = doc.createElement('div');
        actionsContainer.className = 'wiki-comment-actions';

        const replyBtn = doc.createElement('button');
        replyBtn.type = 'button';
        replyBtn.className = 'wiki-comment-action-btn';
        replyBtn.setAttribute('data-action', 'reply');
        replyBtn.setAttribute('data-comment-id', strCommentId);
        replyBtn.setAttribute('data-root-id', String(rootCommentId));
        const authorName = (comment.author && comment.author.displayName) ? comment.author.displayName : '';
        replyBtn.setAttribute('data-author-name', authorName);
        replyBtn.textContent = 'Trả lời';
        actionsContainer.appendChild(replyBtn);

        if (comment.canEdit === true) {
            const editBtn = doc.createElement('button');
            editBtn.type = 'button';
            editBtn.className = 'wiki-comment-action-btn';
            editBtn.setAttribute('data-action', 'edit');
            editBtn.setAttribute('data-comment-id', strCommentId);
            editBtn.setAttribute('data-root-id', String(rootCommentId));
            editBtn.textContent = 'Sửa';
            actionsContainer.appendChild(editBtn);
        }

        if (comment.canDelete === true) {
            const deleteBtn = doc.createElement('button');
            deleteBtn.type = 'button';
            deleteBtn.className = 'wiki-comment-action-btn wiki-comment-action-btn--danger';
            deleteBtn.setAttribute('data-action', 'delete');
            deleteBtn.setAttribute('data-comment-id', strCommentId);
            deleteBtn.setAttribute('data-root-id', String(rootCommentId));
            deleteBtn.textContent = 'Xóa';
            actionsContainer.appendChild(deleteBtn);
        }

        headerEl.appendChild(actionsContainer);
        commentEl.appendChild(headerEl);

        // Body
        const bodyEl = doc.createElement('div');
        bodyEl.className = 'wiki-comment-body';
        bodyEl.setAttribute('data-body-container', strCommentId);

        if (isReply) {
            const immediateParentName = resolveImmediateParentDisplayName(comment, thread.root, thread.replies);
            if (immediateParentName) {
                const mentionSpan = doc.createElement('span');
                mentionSpan.className = 'wiki-comment-reply-mention';
                mentionSpan.textContent = '@' + immediateParentName;
                bodyEl.appendChild(mentionSpan);

                const textSpan = doc.createElement('span');
                textSpan.className = 'wiki-comment-reply-text';
                textSpan.textContent = comment.body || '';
                bodyEl.appendChild(textSpan);
            } else {
                bodyEl.textContent = comment.body || '';
            }
        } else {
            bodyEl.textContent = comment.body || '';
        }

        commentEl.appendChild(bodyEl);

        // Container for inline reply composer
        const replyComposerContainer = doc.createElement('div');
        replyComposerContainer.className = 'wiki-reply-composer-slot';
        replyComposerContainer.setAttribute('data-reply-slot', strCommentId);
        commentEl.appendChild(replyComposerContainer);

        return commentEl;
    }

    /**
     * Renders a full thread card (root + flat replies container).
     */
    function renderThread(thread, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const threadArticle = d.createElement('article');
        threadArticle.className = 'wiki-thread';
        const strRootId = String(thread.root.id);
        threadArticle.setAttribute('data-thread-id', strRootId);
        threadArticle.setAttribute('data-root-id', strRootId);

        // Render root comment
        const rootEl = renderComment(thread.root, strRootId, thread, false, d);
        threadArticle.appendChild(rootEl);

        // Render replies container (flat level 1)
        const repliesContainer = d.createElement('div');
        repliesContainer.className = 'wiki-thread-replies';
        repliesContainer.setAttribute('data-thread-replies', strRootId);

        const replies = Array.isArray(thread.replies) ? thread.replies : [];
        for (let i = 0; i < replies.length; i++) {
            const rep = replies[i];
            if (!rep || !rep.id) continue;
            const replyEl = renderComment(rep, strRootId, thread, true, d);
            repliesContainer.appendChild(replyEl);
        }

        threadArticle.appendChild(repliesContainer);
        return threadArticle;
    }

    /**
     * Opens an inline reply composer under a specific comment.
     */
    function openReplyComposer(targetCommentId, rootCommentId, authorName, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        // Close any existing open inline composers first
        closeAllInlineComposers(d);

        const slot = d.querySelector('[data-reply-slot="' + targetCommentId + '"]');
        if (!slot) return;

        const composerBox = d.createElement('div');
        composerBox.className = 'wiki-inline-composer';

        const form = d.createElement('form');
        form.className = 'wiki-comment-composer-form';

        const label = d.createElement('label');
        label.className = 'visually-hidden';
        label.textContent = 'Nội dung phản hồi';
        const textareaId = 'wikiReplyInput_' + targetCommentId;
        label.setAttribute('for', textareaId);
        form.appendChild(label);

        const textarea = d.createElement('textarea');
        textarea.id = textareaId;
        textarea.className = 'wiki-comment-textarea';
        textarea.rows = 2;
        textarea.placeholder = authorName ? ('Trả lời @' + authorName + '...') : 'Viết phản hồi...';
        textarea.maxLength = 2000;
        form.appendChild(textarea);

        const footer = d.createElement('div');
        footer.className = 'wiki-comment-composer-footer';

        const errorSpan = d.createElement('span');
        errorSpan.className = 'wiki-comment-composer-error';
        errorSpan.setAttribute('role', 'alert');
        errorSpan.hidden = true;
        footer.appendChild(errorSpan);

        const actionsDiv = d.createElement('div');
        actionsDiv.className = 'wiki-comment-composer-actions';

        const cancelBtn = d.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'wiki-comment-btn wiki-comment-btn--secondary';
        cancelBtn.textContent = 'Hủy';
        cancelBtn.addEventListener('click', function () {
            clearElement(slot);
        });
        actionsDiv.appendChild(cancelBtn);

        const submitBtn = d.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = 'wiki-comment-btn wiki-comment-btn--primary';
        submitBtn.textContent = 'Gửi phản hồi';
        actionsDiv.appendChild(submitBtn);

        footer.appendChild(actionsDiv);
        form.appendChild(footer);

        form.addEventListener('submit', async function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            const body = textarea.value ? textarea.value.trim() : '';
            if (!body) {
                errorSpan.textContent = 'Vui lòng nhập nội dung phản hồi.';
                errorSpan.hidden = false;
                textarea.focus();
                return;
            }

            if (isMutating) return;
            isMutating = true;
            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            textarea.disabled = true;
            errorSpan.hidden = true;

            try {
                const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(targetCommentId) + '/replies';
                const res = await doFetch(url, {
                    method: 'POST',
                    headers: buildHeaders(true),
                    body: JSON.stringify({ body: body })
                });

                if (res && res.status === 201) {
                    clearElement(slot);
                    await refreshThread(rootCommentId, d);
                    await refreshMetrics(d);
                } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                    redirectToLogin(d);
                } else if (res && res.status === 400) {
                    errorSpan.textContent = 'Nội dung phản hồi không hợp lệ.';
                    errorSpan.hidden = false;
                } else if (res && res.status === 404) {
                    errorSpan.textContent = 'Bình luận hoặc bài viết không còn tồn tại.';
                    errorSpan.hidden = false;
                } else {
                    errorSpan.textContent = 'Không thể gửi phản hồi. Vui lòng thử lại sau.';
                    errorSpan.hidden = false;
                }
            } catch (err) {
                errorSpan.textContent = 'Lỗi kết nối khi gửi phản hồi.';
                errorSpan.hidden = false;
            } finally {
                isMutating = false;
                submitBtn.disabled = false;
                cancelBtn.disabled = false;
                textarea.disabled = false;
            }
        });

        composerBox.appendChild(form);
        slot.appendChild(composerBox);
        textarea.focus();
    }

    /**
     * Opens an inline edit form replacing comment body text.
     */
    function openEditComposer(commentId, rootCommentId, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        const bodyContainer = d.querySelector('[data-body-container="' + commentId + '"]');
        if (!bodyContainer) return;

        // Find current comment data
        let commentData = null;
        for (let i = 0; i < currentThreads.length; i++) {
            const t = currentThreads[i];
            if (!t) continue;
            if (t.root && String(t.root.id) === String(commentId)) {
                commentData = t.root;
                break;
            }
            if (Array.isArray(t.replies)) {
                for (let j = 0; j < t.replies.length; j++) {
                    if (t.replies[j] && String(t.replies[j].id) === String(commentId)) {
                        commentData = t.replies[j];
                        break;
                    }
                }
            }
            if (commentData) break;
        }

        const initialBody = (commentData && commentData.body) ? commentData.body : '';

        // Save previous DOM state for cancel
        const savedChildNodes = Array.from(bodyContainer.childNodes);

        clearElement(bodyContainer);

        const form = d.createElement('form');
        form.className = 'wiki-inline-edit-form';

        const label = d.createElement('label');
        label.className = 'visually-hidden';
        label.textContent = 'Chỉnh sửa bình luận';
        const textareaId = 'wikiEditInput_' + commentId;
        label.setAttribute('for', textareaId);
        form.appendChild(label);

        const textarea = d.createElement('textarea');
        textarea.id = textareaId;
        textarea.className = 'wiki-comment-textarea';
        textarea.rows = 3;
        textarea.value = initialBody;
        textarea.maxLength = 2000;
        form.appendChild(textarea);

        const footer = d.createElement('div');
        footer.className = 'wiki-comment-composer-footer';

        const errorSpan = d.createElement('span');
        errorSpan.className = 'wiki-comment-composer-error';
        errorSpan.setAttribute('role', 'alert');
        errorSpan.hidden = true;
        footer.appendChild(errorSpan);

        const actionsDiv = d.createElement('div');
        actionsDiv.className = 'wiki-comment-composer-actions';

        const cancelBtn = d.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'wiki-comment-btn wiki-comment-btn--secondary';
        cancelBtn.textContent = 'Hủy';
        cancelBtn.addEventListener('click', function () {
            clearElement(bodyContainer);
            for (let i = 0; i < savedChildNodes.length; i++) {
                bodyContainer.appendChild(savedChildNodes[i]);
            }
        });
        actionsDiv.appendChild(cancelBtn);

        const submitBtn = d.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.className = 'wiki-comment-btn wiki-comment-btn--primary';
        submitBtn.textContent = 'Lưu';
        actionsDiv.appendChild(submitBtn);

        footer.appendChild(actionsDiv);
        form.appendChild(footer);

        form.addEventListener('submit', async function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            const newBody = textarea.value ? textarea.value.trim() : '';
            if (!newBody) {
                errorSpan.textContent = 'Vui lòng nhập nội dung bình luận.';
                errorSpan.hidden = false;
                textarea.focus();
                return;
            }

            if (isMutating) return;
            isMutating = true;
            submitBtn.disabled = true;
            cancelBtn.disabled = true;
            textarea.disabled = true;
            errorSpan.hidden = true;

            try {
                const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(commentId);
                const res = await doFetch(url, {
                    method: 'PATCH',
                    headers: buildHeaders(true),
                    body: JSON.stringify({ body: newBody })
                });

                if (res && res.status === 204) {
                    await refreshThread(rootCommentId, d);
                } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                    redirectToLogin(d);
                } else if (res && res.status === 403) {
                    errorSpan.textContent = 'Bạn không có quyền chỉnh sửa bình luận này.';
                    errorSpan.hidden = false;
                    await refreshThread(rootCommentId, d);
                } else if (res && res.status === 400) {
                    errorSpan.textContent = 'Nội dung bình luận không hợp lệ.';
                    errorSpan.hidden = false;
                } else if (res && res.status === 404) {
                    errorSpan.textContent = 'Bình luận không còn tồn tại.';
                    errorSpan.hidden = false;
                } else {
                    errorSpan.textContent = 'Không thể chỉnh sửa bình luận. Vui lòng thử lại sau.';
                    errorSpan.hidden = false;
                }
            } catch (err) {
                errorSpan.textContent = 'Lỗi kết nối khi lưu bình luận.';
                errorSpan.hidden = false;
            } finally {
                isMutating = false;
                submitBtn.disabled = false;
                cancelBtn.disabled = false;
                textarea.disabled = false;
            }
        });

        bodyContainer.appendChild(form);
        textarea.focus();
    }

    /**
     * Handles deletion of a comment (root or reply).
     */
    async function handleDeleteComment(commentId, rootCommentId, doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        const confirmed = doConfirm('Bạn có chắc chắn muốn xóa bình luận này không?');
        if (!confirmed) return;

        if (isMutating) return;
        isMutating = true;

        const isRootDelete = String(commentId) === String(rootCommentId);

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments/' + encodeURIComponent(commentId);
            const res = await doFetch(url, {
                method: 'DELETE',
                headers: buildHeaders(false)
            });

            if (res && res.status === 204) {
                clearDiscussionStatus(d);
                if (isRootDelete) {
                    // Authoritatively reload feed from page 0
                    await loadDiscussionFeed(0, false, d);
                } else {
                    // Refresh affected thread authoritatively
                    await refreshThread(rootCommentId, d);
                    await refreshMetrics(d);
                }
            } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                redirectToLogin(d);
            } else if (res && res.status === 403) {
                try {
                    if (isRootDelete) {
                        await loadDiscussionFeed(0, false, d);
                    } else {
                        await refreshThread(rootCommentId, d);
                    }
                } catch (ignored) {
                    // Ignore refresh error, authoritative 403 error message takes priority
                }
                showDiscussionError('Bạn không còn quyền xóa bình luận này.', d);
            } else if (res && res.status === 404) {
                await loadDiscussionFeed(0, false, d);
            } else {
                showDiscussionError('Không thể xóa bình luận. Vui lòng thử lại sau.', d);
            }
        } catch (err) {
            showDiscussionError('Lỗi kết nối khi xóa bình luận. Vui lòng thử lại.', d);
        } finally {
            isMutating = false;
        }
    }

    /**
     * Closes any open inline reply composers in the document.
     */
    function closeAllInlineComposers(doc) {
        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!d) return;
        const slots = d.querySelectorAll('.wiki-reply-composer-slot');
        for (let i = 0; i < slots.length; i++) {
            clearElement(slots[i]);
        }
    }

    /**
     * Root comment composer form submit handler.
     */
    async function handleRootCommentSubmit(e, doc) {
        if (e && typeof e.preventDefault === 'function') {
            e.preventDefault();
        }

        const d = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        const els = getElements(d);

        if (!isAuthenticated) {
            redirectToLogin(d);
            return;
        }

        const text = els.composerInputEl ? els.composerInputEl.value.trim() : '';
        if (!text) {
            if (els.composerErrorEl) {
                els.composerErrorEl.textContent = 'Vui lòng nhập nội dung bình luận.';
                els.composerErrorEl.hidden = false;
            }
            if (els.composerInputEl) {
                els.composerInputEl.focus();
            }
            return;
        }

        if (isMutating) return;
        isMutating = true;

        if (els.composerSubmitEl) els.composerSubmitEl.disabled = true;
        if (els.composerInputEl) els.composerInputEl.disabled = true;
        if (els.composerErrorEl) els.composerErrorEl.hidden = true;

        try {
            const url = '/api/wiki/articles/' + encodeURIComponent(articleId) + '/comments';
            const res = await doFetch(url, {
                method: 'POST',
                headers: buildHeaders(true),
                body: JSON.stringify({ body: text })
            });

            if (res && res.status === 201) {
                if (els.composerInputEl) {
                    els.composerInputEl.value = '';
                }
                // Refresh discussion authoritatively from page 0
                await loadDiscussionFeed(0, false, d);
            } else if (res && (res.status === 401 || res.status === 302 || res.redirected)) {
                redirectToLogin(d);
            } else if (res && res.status === 400) {
                if (els.composerErrorEl) {
                    els.composerErrorEl.textContent = 'Nội dung bình luận không hợp lệ.';
                    els.composerErrorEl.hidden = false;
                }
            } else if (res && res.status === 404) {
                if (els.composerErrorEl) {
                    els.composerErrorEl.textContent = 'Bài viết không còn tồn tại hoặc chưa xuất bản.';
                    els.composerErrorEl.hidden = false;
                }
            } else {
                if (els.composerErrorEl) {
                    els.composerErrorEl.textContent = 'Không thể gửi bình luận. Vui lòng thử lại sau.';
                    els.composerErrorEl.hidden = false;
                }
            }
        } catch (err) {
            if (els.composerErrorEl) {
                els.composerErrorEl.textContent = 'Lỗi kết nối khi gửi bình luận.';
                els.composerErrorEl.hidden = false;
            }
        } finally {
            isMutating = false;
            if (els.composerSubmitEl) els.composerSubmitEl.disabled = false;
            if (els.composerInputEl) els.composerInputEl.disabled = false;
        }
    }

    /**
     * Load more button click handler.
     */
    async function handleLoadMore(doc) {
        if (!hasNext || isLoading) return;
        await loadDiscussionFeed(currentPage + 1, true, doc);
    }

    /**
     * Initializes the Wiki Article Comments frontend module.
     */
    function initWikiArticleComments(doc) {
        currentDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (!currentDoc) return false;

        const els = getElements(currentDoc);
        if (!els.sectionEl) {
            return false;
        }

        articleId = els.sectionEl.getAttribute('data-article-id');
        if (!articleId) {
            return false; // No article ID -> do not initialize or call APIs
        }

        isAuthenticated = els.sectionEl.getAttribute('data-authenticated') === 'true';
        loginUrl = els.sectionEl.getAttribute('data-login-url') || '/login';

        // Read CSRF from data attributes or fallback to meta tags
        const attrCsrfToken = els.sectionEl.getAttribute('data-csrf-token');
        const attrCsrfHeader = els.sectionEl.getAttribute('data-csrf-header');

        const metaCsrf = currentDoc.querySelector('meta[name="_csrf"]');
        const metaCsrfHeader = currentDoc.querySelector('meta[name="_csrf_header"]');

        csrfToken = attrCsrfToken || (metaCsrf ? metaCsrf.getAttribute('content') : '');
        csrfHeader = attrCsrfHeader || (metaCsrfHeader ? metaCsrfHeader.getAttribute('content') : 'X-CSRF-TOKEN');

        // Wire root composer
        if (els.composerFormEl) {
            els.composerFormEl.addEventListener('submit', function (e) {
                handleRootCommentSubmit(e, currentDoc);
            });
        }

        // Wire load more button
        if (els.loadMoreBtnEl) {
            els.loadMoreBtnEl.addEventListener('click', function () {
                handleLoadMore(currentDoc);
            });
        }

        // Delegate comment action buttons (reply, edit, delete) on thread list
        if (els.threadListEl) {
            els.threadListEl.addEventListener('click', function (e) {
                const target = e.target;
                if (!target || typeof target.getAttribute !== 'function') return;

                const actionBtn = target.closest ? target.closest('.wiki-comment-action-btn') : (target.classList && target.classList.contains('wiki-comment-action-btn') ? target : null);
                if (!actionBtn) return;

                const action = actionBtn.getAttribute('data-action');
                const commentId = actionBtn.getAttribute('data-comment-id');
                const rootCommentId = actionBtn.getAttribute('data-root-id');
                const authorName = actionBtn.getAttribute('data-author-name') || '';

                if (action === 'reply' && commentId && rootCommentId) {
                    openReplyComposer(commentId, rootCommentId, authorName, currentDoc);
                } else if (action === 'edit' && commentId && rootCommentId) {
                    openEditComposer(commentId, rootCommentId, currentDoc);
                } else if (action === 'delete' && commentId && rootCommentId) {
                    handleDeleteComment(commentId, rootCommentId, currentDoc);
                }
            });
        }

        // Initial feed load
        loadDiscussionFeed(0, false, currentDoc);

        return true;
    }

    // Auto-initialize in browser when DOM is ready
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initWikiArticleComments(document);
            });
        } else {
            initWikiArticleComments(document);
        }
    }

    // Public API
    return {
        SECTION_ID: SECTION_ID,
        COUNT_BADGE_ID: COUNT_BADGE_ID,
        THREAD_LIST_ID: THREAD_LIST_ID,
        STATUS_ID: STATUS_ID,
        FOOTER_ID: FOOTER_ID,
        LOAD_MORE_BTN_ID: LOAD_MORE_BTN_ID,
        init: initWikiArticleComments,
        destroy: resetState,
        resetState: resetState,
        loadDiscussionFeed: loadDiscussionFeed,
        refreshFeed: function (doc) { return loadDiscussionFeed(0, false, doc, true); },
        refreshThread: refreshThread,
        refreshMetrics: refreshMetrics,
        showDiscussionError: showDiscussionError,
        clearDiscussionStatus: clearDiscussionStatus,
        renderThread: renderThread,
        renderComment: renderComment,
        formatTimestamp: formatTimestamp,
        formatCommentCount: formatCommentCount,
        isCommentEdited: isCommentEdited,
        sanitizeAvatarUrl: sanitizeAvatarUrl,
        createAvatarFallback: createAvatarFallback,
        getValidSecurityRedirectUrl: getValidSecurityRedirectUrl,
        openReplyComposer: openReplyComposer,
        openEditComposer: openEditComposer,
        handleDeleteComment: handleDeleteComment,
        handleRootCommentSubmit: handleRootCommentSubmit,
        getState: function () {
            return {
                articleId: articleId,
                isAuthenticated: isAuthenticated,
                loginUrl: loginUrl,
                currentPage: currentPage,
                hasNext: hasNext,
                isLoading: isLoading,
                isMutating: isMutating,
                threadCount: threadCount,
                commentCount: commentCount,
                currentThreads: currentThreads,
                renderedRootIds: Array.from(renderedRootIds)
            };
        },
        setFetchImplementation: function (fn) {
            injectedFetch = fn;
        },
        setConfirmImplementation: function (fn) {
            injectedConfirm = fn;
        }
    };
}));
