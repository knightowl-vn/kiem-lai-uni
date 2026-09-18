/**
 * KiemLai Universe — Bottom-of-Chapter Comment Section Read UI (MS-05E5H2C)
 *
 * Responsibilities:
 * - Owns the read-only rendering of the bottom-of-chapter comment feed.
 * - Fetches initial page (page=0, size=20) from GET /api/novel/chapters/{chapterId}/comments/feed.
 * - Renders thread cards in API-delivered order (newest root first).
 * - Renders nested replies underneath each root in API-delivered order (chronological ASC).
 * - Applies Wattpad-style immediate-parent attribution (@ParentName) for nested replies.
 * - Handles deleted replies gracefully using deterministic tombstone fallbacks.
 * - Manages status transitions: loading spinner, empty state, error with retry.
 * - Enforces race safety via load tokens against out-of-order responses.
 * - Responds to 'kiemlai:chapter-changed' to refresh comments for the new chapter.
 * - Strictly read-only comments feed with read-only origin navigation (⋯ → Xem đoạn gốc).
 * - Mutation affordances (reply creation, edit, delete) and passage excerpts are strictly absent.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.NovelReaderChapterComments = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.NovelReaderChapterComments = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const SECTION_ID = 'novelChapterComments';
    const TITLE_ID = 'novelChapterCommentsTitle';
    const COUNT_ID = 'novelChapterCommentsCount';
    const STATUS_ID = 'novelChapterCommentsStatus';
    const LIST_ID = 'novelChapterCommentsList';

    const EVENT_CHAPTER_CHANGED = 'kiemlai:chapter-changed';

    // Internal module state
    let currentDoc = null;
    let currentChapterId = null;
    let injectedFetch = null;
    let injectedOpenDiscussionTarget = null;
    let loadToken = 0;
    let currentStatus = 'idle'; // 'idle' | 'loading' | 'empty' | 'populated' | 'error'
    let currentItems = [];
    let chapterChangedHandler = null;
    let documentClickHandler = null;
    let documentKeydownHandler = null;
    let activeOpenMenu = null; // { triggerEl, popoverEl, containerEl }

    /**
     * Formats comment count label (e.g. '3 bình luận').
     *
     * @param {number} count
     * @returns {string}
     */
    function formatCommentCount(count) {
        const num = Number(count);
        if (!Number.isSafeInteger(num) || num < 0) {
            return '';
        }
        return num + ' bình luận';
    }

    /**
     * Formats ISO timestamp to localized readable string (DD/MM/YYYY HH:mm).
     * Returns empty string for invalid/missing timestamp without throwing.
     *
     * @param {string} isoString
     * @returns {string}
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
     * Checks if a comment has been edited by comparing createdAt and updatedAt timestamps.
     *
     * @param {Object} comment
     * @returns {boolean}
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
     * Clears all children of a container element safely.
     *
     * @param {Element} container
     */
    function clearElement(container) {
        if (!container) {
            return;
        }
        if (typeof container.replaceChildren === 'function') {
            container.replaceChildren();
        } else {
            while (container.firstChild) {
                container.removeChild(container.firstChild);
            }
        }
    }

    /**
     * Sanitizes an avatar URL ensuring safe protocols (http, https, or same-origin path).
     * Disallows dangerous protocols (javascript:, data:, vbscript:, blob:, etc.)
     * and disallows protocol-relative URLs (//evil.com).
     *
     * @param {string} url
     * @returns {string|null}
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
     *
     * @param {string} displayName
     * @param {Document} doc
     * @returns {Element}
     */
    function createAvatarFallback(displayName, doc) {
        const fallback = doc.createElement('span');
        fallback.className = 'novel-comment-avatar novel-comment-avatar--fallback';
        fallback.setAttribute('aria-hidden', 'true');
        const trimmed = (typeof displayName === 'string') ? displayName.trim() : '';
        const firstChar = trimmed ? trimmed.charAt(0).toUpperCase() : 'U';
        fallback.textContent = firstChar;
        return fallback;
    }

    /**
     * Renders author presentation (avatar and displayName) into comment header.
     *
     * @param {Element} headerEl
     * @param {Object|null} author
     * @param {Document} doc
     */
    function renderAuthorPresentation(headerEl, author, doc) {
        const authorObj = (author && typeof author === 'object') ? author : null;
        const rawName = (authorObj && typeof authorObj.displayName === 'string') ? authorObj.displayName.trim() : '';
        const displayName = rawName || 'Người dùng';
        const rawAvatar = (authorObj && typeof authorObj.avatarUrl === 'string') ? authorObj.avatarUrl.trim() : '';
        const sanitizedAvatar = sanitizeAvatarUrl(rawAvatar);

        if (sanitizedAvatar) {
            const avatarImg = doc.createElement('img');
            avatarImg.className = 'novel-comment-avatar';
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
        authorSpan.className = 'novel-comment-author';
        authorSpan.textContent = displayName;
        headerEl.appendChild(authorSpan);
    }

    /**
     * Resolves the single active direct child display name for contextual tombstone rendering.
     * Returns the non-blank displayName of the unique direct active child, or null if 0, >1,
     * or missing/blank child author display name.
     *
     * @param {Array} replies
     * @param {string|number} tombstoneId
     * @returns {string|null}
     */
    function resolveTombstoneContextChildDisplayName(replies, tombstoneId) {
        if (!Array.isArray(replies) || !tombstoneId) {
            return null;
        }
        const targetParentId = String(tombstoneId).trim();
        let activeDirectChild = null;
        let activeDirectChildCount = 0;

        for (let i = 0; i < replies.length; i++) {
            const r = replies[i];
            if (!r) continue;

            const isDeleted = r.tombstone === true || r.status === 'DELETED';
            if (isDeleted) continue;

            if (r.parentCommentId != null && String(r.parentCommentId).trim() === targetParentId) {
                activeDirectChildCount++;
                if (activeDirectChildCount === 1) {
                    activeDirectChild = r;
                } else {
                    return null;
                }
            }
        }

        if (activeDirectChildCount !== 1 || !activeDirectChild) {
            return null;
        }

        const author = activeDirectChild.author;
        if (!author || typeof author !== 'object') {
            return null;
        }

        if (typeof author.displayName !== 'string') {
            return null;
        }

        const trimmedName = author.displayName.trim();
        return trimmedName.length > 0 ? trimmedName : null;
    }

    /**
     * Resolves DOM elements for the chapter comments section.
     *
     * @returns {Object}
     */
    function getElements() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!doc) {
            return { sectionEl: null, listEl: null, statusEl: null, countEl: null };
        }
        return {
            sectionEl: doc.getElementById(SECTION_ID),
            listEl: doc.getElementById(LIST_ID),
            statusEl: doc.getElementById(STATUS_ID),
            countEl: doc.getElementById(COUNT_ID)
        };
    }

    /**
     * Resolves chapter ID from section or DOM body.
     *
     * @param {Document} doc
     * @param {Element|null} sectionEl
     * @returns {string|null}
     */
    function resolveChapterId(doc, sectionEl) {
        if (sectionEl) {
            const id = sectionEl.getAttribute('data-chapter-id');
            if (id && id.trim()) {
                return id.trim();
            }
        }
        if (doc && typeof doc.querySelector === 'function') {
            const bodyEl = doc.querySelector('.novel-reader-chapter-body[data-chapter-id]');
            if (bodyEl) {
                const id = bodyEl.getAttribute('data-chapter-id');
                if (id && id.trim()) {
                    return id.trim();
                }
            }
            const fallbackEl = doc.querySelector('[data-chapter-id]');
            if (fallbackEl) {
                const id = fallbackEl.getAttribute('data-chapter-id');
                if (id && id.trim()) {
                    return id.trim();
                }
            }
        }
        return null;
    }

    /**
     * Determines whether origin navigation is available for given anchor status and block key.
     * Available for CURRENT and RELOCATED when blockKey is non-null and non-empty.
     *
     * @param {string|null} anchorStatus
     * @param {string|null} blockKey
     * @returns {boolean}
     */
    function isOriginNavigable(anchorStatus, blockKey) {
        if (!blockKey || typeof blockKey !== 'string' || !blockKey.trim()) {
            return false;
        }
        const status = (typeof anchorStatus === 'string') ? anchorStatus.trim().toUpperCase() : '';
        return status === 'CURRENT' || status === 'RELOCATED';
    }

    /**
     * Closes the active overflow menu and optionally restores focus to its trigger button.
     *
     * @param {boolean} [restoreFocus=false]
     */
    function closeActiveMenu(restoreFocus) {
        if (!activeOpenMenu) {
            return;
        }
        const { triggerEl, popoverEl, containerEl } = activeOpenMenu;
        activeOpenMenu = null;

        if (triggerEl) {
            triggerEl.setAttribute('aria-expanded', 'false');
            if (restoreFocus && typeof triggerEl.focus === 'function') {
                try {
                    triggerEl.focus();
                } catch (_) {}
            }
        }
        if (popoverEl) {
            popoverEl.hidden = true;
            popoverEl.setAttribute('hidden', '');
        }
        if (containerEl && containerEl.classList && typeof containerEl.classList.remove === 'function') {
            containerEl.classList.remove('is-open');
        }
    }

    /**
     * Opens a specific overflow menu, closing any previously open menu first.
     *
     * @param {Element} triggerEl
     * @param {Element} popoverEl
     * @param {Element} containerEl
     */
    function openMenu(triggerEl, popoverEl, containerEl) {
        if (activeOpenMenu && activeOpenMenu.triggerEl === triggerEl) {
            closeActiveMenu(false);
            return;
        }
        closeActiveMenu(false);

        activeOpenMenu = { triggerEl: triggerEl, popoverEl: popoverEl, containerEl: containerEl };
        triggerEl.setAttribute('aria-expanded', 'true');
        popoverEl.hidden = false;
        popoverEl.removeAttribute('hidden');
        if (containerEl && containerEl.classList && typeof containerEl.classList.add === 'function') {
            containerEl.classList.add('is-open');
        }
    }

    /**
     * Handles document click events to close the active menu if clicked outside.
     *
     * @param {Event} e
     */
    function onDocumentClick(e) {
        if (!activeOpenMenu) {
            return;
        }
        const container = activeOpenMenu.containerEl;
        const target = (e && e.target) ? e.target : null;
        if (container && target) {
            if (typeof container.contains === 'function' && container.contains(target)) {
                return;
            }
        }
        closeActiveMenu(false);
    }

    /**
     * Handles Escape key to close the active menu and restore focus.
     *
     * @param {KeyboardEvent} e
     */
    function onDocumentKeydown(e) {
        if (!activeOpenMenu) {
            return;
        }
        if (e && (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27)) {
            if (typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            closeActiveMenu(true);
        }
    }

    /**
     * Opens the canonical block discussion drawer targeting the root discussion thread.
     * Reuses reader-block-discussion-restore or injected options.openDiscussionTarget.
     * If the canonical restore bridge is unavailable, safely no-ops without weaker fallback dispatch.
     *
     * @param {string} blockKey
     * @param {string} rootCommentId
     * @param {Document} [doc]
     */
    function openOriginDiscussion(blockKey, rootCommentId, doc) {
        const documentRef = doc || currentDoc || (typeof document !== 'undefined' ? document : null);
        if (!blockKey || !documentRef) {
            return;
        }

        // 1. Injected handler takes precedence (for isolated testing or explicit orchestrator)
        if (typeof injectedOpenDiscussionTarget === 'function') {
            injectedOpenDiscussionTarget({
                chapterId: currentChapterId,
                blockKey: blockKey,
                threadId: rootCommentId
            }, documentRef);
            return;
        }

        const win = (documentRef.defaultView) || (typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null));
        const restoreModule = (win && (win.NovelReaderBlockDiscussionRestore || (win.KiemLai && win.KiemLai.NovelReaderBlockDiscussionRestore))) ||
            (typeof NovelReaderBlockDiscussionRestore !== 'undefined' ? NovelReaderBlockDiscussionRestore : null);

        // 2. Canonical restore bridge
        if (restoreModule && typeof restoreModule.openDiscussionTarget === 'function') {
            restoreModule.openDiscussionTarget({
                chapterId: currentChapterId,
                blockKey: blockKey,
                threadId: rootCommentId
            }, documentRef, win);
            return;
        }

        // If the canonical restore bridge is unavailable: safely no-op
    }

    /**
     * Constructs the three-dot overflow actions menu element containing "Xem đoạn gốc".
     *
     * @param {string} blockKey
     * @param {string} rootCommentId
     * @param {Document} doc
     * @returns {Element}
     */
    function createActionsMenu(blockKey, rootCommentId, doc) {
        const menuContainer = doc.createElement('div');
        menuContainer.className = 'novel-comment-actions-menu';

        const triggerBtn = doc.createElement('button');
        triggerBtn.type = 'button';
        triggerBtn.className = 'novel-comment-menu-trigger';
        triggerBtn.setAttribute('aria-label', 'Mở menu bình luận');
        triggerBtn.setAttribute('aria-haspopup', 'menu');
        triggerBtn.setAttribute('aria-expanded', 'false');

        const dotsSpan = doc.createElement('span');
        dotsSpan.className = 'novel-comment-menu-dots';
        dotsSpan.setAttribute('aria-hidden', 'true');
        dotsSpan.textContent = '⋯';
        triggerBtn.appendChild(dotsSpan);

        const popoverDiv = doc.createElement('div');
        popoverDiv.className = 'novel-comment-menu-popover';
        popoverDiv.setAttribute('role', 'menu');
        popoverDiv.hidden = true;
        popoverDiv.setAttribute('hidden', '');

        const originBtn = doc.createElement('button');
        originBtn.type = 'button';
        originBtn.className = 'novel-comment-menu-item';
        originBtn.setAttribute('role', 'menuitem');
        originBtn.setAttribute('data-action', 'view-origin');
        originBtn.setAttribute('data-block-key', String(blockKey));
        if (rootCommentId) {
            originBtn.setAttribute('data-root-id', String(rootCommentId));
        }
        originBtn.textContent = 'Xem đoạn gốc';

        originBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            closeActiveMenu(false);
            openOriginDiscussion(blockKey, rootCommentId, doc);
        });

        triggerBtn.addEventListener('click', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            if (e && typeof e.stopPropagation === 'function') {
                e.stopPropagation();
            }
            if (activeOpenMenu && activeOpenMenu.triggerEl === triggerBtn) {
                closeActiveMenu(false);
            } else {
                openMenu(triggerBtn, popoverDiv, menuContainer);
            }
        });

        popoverDiv.appendChild(originBtn);
        menuContainer.appendChild(triggerBtn);
        menuContainer.appendChild(popoverDiv);

        return menuContainer;
    }

    /**
     * Renders a single discussion thread card (root comment + visible replies).
     *
     * @param {Object} item ChapterDiscussionFeedItemDTO
     * @param {Document} doc
     * @returns {Element}
     */
    function renderThread(item, doc) {
        const rootId = item.rootCommentId || item.id;
        const threadCard = doc.createElement('article');
        threadCard.className = 'novel-block-discussion-thread';
        if (rootId) {
            threadCard.setAttribute('data-root-id', String(rootId));
        }

        // Root comment element
        const rootEl = doc.createElement('div');
        rootEl.className = 'novel-comment novel-comment--root';
        if (rootId) {
            rootEl.setAttribute('data-comment-id', String(rootId));
        }
        const authorUserId = (item.author && item.author.userId) || item.authorUserId;
        if (authorUserId) {
            rootEl.setAttribute('data-author-user-id', String(authorUserId));
        }

        // Root Header (Author + Timestamp + Edited indicator)
        const rootHeader = doc.createElement('header');
        rootHeader.className = 'novel-comment-header';

        renderAuthorPresentation(rootHeader, item.author, doc);

        const rootTimeStr = formatTimestamp(item.createdAt);
        if (rootTimeStr) {
            const rootTime = doc.createElement('time');
            rootTime.className = 'novel-comment-time';
            rootTime.setAttribute('datetime', String(item.createdAt));
            rootTime.textContent = rootTimeStr;
            rootHeader.appendChild(rootTime);
        }

        if (isCommentEdited(item)) {
            const rootEdited = doc.createElement('span');
            rootEdited.className = 'novel-comment-edited';
            rootEdited.textContent = 'đã chỉnh sửa';
            rootHeader.appendChild(rootEdited);
        }

        if (isOriginNavigable(item.anchorStatus, item.blockKey)) {
            const rootMenu = createActionsMenu(item.blockKey, rootId, doc);
            rootHeader.appendChild(rootMenu);
        }

        // Root Body (rendered safely as textContent)
        const rootBody = doc.createElement('div');
        rootBody.className = 'novel-comment-body';
        rootBody.textContent = item.body || '';

        rootEl.appendChild(rootHeader);
        rootEl.appendChild(rootBody);

        threadCard.appendChild(rootEl);

        // Replies container
        const replies = Array.isArray(item.replies) ? item.replies : [];
        if (replies.length > 0) {
            const repliesContainer = doc.createElement('div');
            repliesContainer.className = 'novel-comment-replies';
            repliesContainer.setAttribute('role', 'group');
            repliesContainer.setAttribute('aria-label', 'Phản hồi');

            // Construct in-memory comment lookup for immediate parent attribution
            const commentLookup = Object.create(null);
            if (rootId) {
                commentLookup[String(rootId)] = {
                    id: rootId,
                    author: item.author,
                    tombstone: false
                };
            }
            for (let k = 0; k < replies.length; k++) {
                const rep = replies[k];
                if (rep && rep.id) {
                    commentLookup[String(rep.id)] = rep;
                }
            }

            const strRootId = rootId ? String(rootId) : '';

            for (let j = 0; j < replies.length; j++) {
                const reply = replies[j];
                if (!reply) continue;

                const replyEl = doc.createElement('article');
                replyEl.className = 'novel-comment novel-comment--reply';
                if (reply.id) {
                    replyEl.setAttribute('data-reply-id', String(reply.id));
                    replyEl.setAttribute('data-comment-id', String(reply.id));
                }

                const isTombstone = reply.tombstone === true || reply.status === 'DELETED';
                if (isTombstone) {
                    if (replyEl.classList && typeof replyEl.classList.add === 'function') {
                        replyEl.classList.add('is-tombstone');
                    }
                    const tombstoneBody = doc.createElement('div');
                    tombstoneBody.className = 'novel-comment-body novel-comment-body--tombstone';

                    const contextChildDisplayName = (reply.id)
                        ? resolveTombstoneContextChildDisplayName(replies, reply.id)
                        : null;

                    if (contextChildDisplayName) {
                        const prefixSpan = doc.createElement('span');
                        prefixSpan.textContent = 'Bình luận mà ';

                        const mentionSpan = doc.createElement('span');
                        mentionSpan.className = 'novel-comment-reply-mention';
                        mentionSpan.textContent = '@' + contextChildDisplayName;

                        const suffixSpan = doc.createElement('span');
                        suffixSpan.textContent = ' phản hồi đã bị xóa.';

                        tombstoneBody.appendChild(prefixSpan);
                        tombstoneBody.appendChild(mentionSpan);
                        tombstoneBody.appendChild(suffixSpan);
                    } else {
                        tombstoneBody.textContent = 'Bình luận đã bị xóa.';
                    }

                    replyEl.appendChild(tombstoneBody);
                } else {
                    const repAuthorUserId = (reply.author && reply.author.userId) || reply.authorUserId;
                    if (repAuthorUserId) {
                        replyEl.setAttribute('data-author-user-id', String(repAuthorUserId));
                    }

                    const replyHeader = doc.createElement('header');
                    replyHeader.className = 'novel-comment-header';

                    renderAuthorPresentation(replyHeader, reply.author, doc);

                    const replyTimeStr = formatTimestamp(reply.createdAt);
                    if (replyTimeStr) {
                        const replyTime = doc.createElement('time');
                        replyTime.className = 'novel-comment-time';
                        replyTime.setAttribute('datetime', String(reply.createdAt));
                        replyTime.textContent = replyTimeStr;
                        replyHeader.appendChild(replyTime);
                    }

                    if (isCommentEdited(reply)) {
                        const replyEdited = doc.createElement('span');
                        replyEdited.className = 'novel-comment-edited';
                        replyEdited.textContent = 'đã chỉnh sửa';
                        replyHeader.appendChild(replyEdited);
                    }

                    // Active reply inherits root anchorStatus and root blockKey
                    if (isOriginNavigable(item.anchorStatus, item.blockKey)) {
                        const replyMenu = createActionsMenu(item.blockKey, rootId, doc);
                        replyHeader.appendChild(replyMenu);
                    }

                    const replyBody = doc.createElement('div');
                    replyBody.className = 'novel-comment-body';

                    // Resolve immediate parent for Wattpad-style nested reply mention
                    let parentDisplayName = null;
                    const parentId = (reply.parentCommentId != null) ? String(reply.parentCommentId).trim() : '';
                    if (parentId && parentId !== strRootId) {
                        const immediateParent = commentLookup[parentId];
                        if (immediateParent) {
                            const isParentTombstone = immediateParent.tombstone === true || immediateParent.status === 'DELETED';
                            if (!isParentTombstone) {
                                const parentAuthor = (immediateParent.author && typeof immediateParent.author === 'object')
                                    ? immediateParent.author
                                    : null;
                                const rawParentName = (parentAuthor && typeof parentAuthor.displayName === 'string')
                                    ? parentAuthor.displayName.trim()
                                    : '';
                                if (rawParentName) {
                                    parentDisplayName = rawParentName;
                                }
                            }
                        }
                    }

                    if (parentDisplayName) {
                        const mentionSpan = doc.createElement('span');
                        mentionSpan.className = 'novel-comment-reply-mention';
                        mentionSpan.textContent = '@' + parentDisplayName;

                        const bodyTextSpan = doc.createElement('span');
                        bodyTextSpan.className = 'novel-comment-reply-body-text';
                        bodyTextSpan.textContent = reply.body || '';

                        replyBody.appendChild(mentionSpan);
                        replyBody.appendChild(bodyTextSpan);
                    } else {
                        replyBody.textContent = reply.body || '';
                    }

                    replyEl.appendChild(replyHeader);
                    replyEl.appendChild(replyBody);
                }

                repliesContainer.appendChild(replyEl);
            }

            threadCard.appendChild(repliesContainer);
        }

        return threadCard;
    }

    /**
     * Renders loading status.
     *
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Document} doc
     */
    function renderLoading(statusEl, listEl, countEl, doc) {
        currentStatus = 'loading';
        currentItems = [];

        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'true');
        }
        if (statusEl) {
            clearElement(statusEl);
            const loadingDiv = doc.createElement('div');
            loadingDiv.className = 'novel-chapter-comments-loading';
            loadingDiv.setAttribute('role', 'status');
            loadingDiv.setAttribute('aria-live', 'polite');

            const spinner = doc.createElement('span');
            spinner.className = 'novel-chapter-comments-spinner';
            spinner.setAttribute('aria-hidden', 'true');

            const text = doc.createElement('span');
            text.className = 'novel-chapter-comments-loading-text';
            text.textContent = 'Đang tải bình luận...';

            loadingDiv.appendChild(spinner);
            loadingDiv.appendChild(text);
            statusEl.appendChild(loadingDiv);
        }
        if (countEl) {
            countEl.textContent = '';
        }
    }

    /**
     * Renders empty comments state.
     *
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Document} doc
     */
    function renderEmpty(statusEl, listEl, countEl, doc) {
        currentStatus = 'empty';
        currentItems = [];

        if (statusEl) {
            clearElement(statusEl);
        }
        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');

            const emptyDiv = doc.createElement('div');
            emptyDiv.className = 'novel-chapter-comments-empty';

            const text = doc.createElement('p');
            text.className = 'novel-chapter-comments-empty-text';
            text.textContent = 'Chưa có bình luận nào.';

            emptyDiv.appendChild(text);
            listEl.appendChild(emptyDiv);
        }
        if (countEl) {
            countEl.textContent = formatCommentCount(0);
        }
    }

    /**
     * Renders populated comments state.
     *
     * @param {Array} items
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Document} doc
     */
    function renderPopulated(items, statusEl, listEl, countEl, doc) {
        currentStatus = 'populated';
        currentItems = items;

        if (statusEl) {
            clearElement(statusEl);
        }
        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');

            for (let i = 0; i < items.length; i++) {
                const item = items[i];
                if (!item) continue;
                const threadCard = renderThread(item, doc);
                listEl.appendChild(threadCard);
            }
        }
        if (countEl) {
            const totalCount = items.reduce(function (sum, it) {
                const replyCount = (it && Number.isSafeInteger(Number(it.replyCount)))
                    ? Number(it.replyCount)
                    : (it && Array.isArray(it.replies) ? it.replies.length : 0);
                return sum + 1 + replyCount;
            }, 0);
            countEl.textContent = formatCommentCount(totalCount);
        }
    }

    /**
     * Renders error state with retry affordance.
     *
     * @param {string|null} message
     * @param {Element} statusEl
     * @param {Element} listEl
     * @param {Element|null} countEl
     * @param {Document} doc
     */
    function renderError(message, statusEl, listEl, countEl, doc) {
        currentStatus = 'error';
        currentItems = [];

        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');
        }
        if (statusEl) {
            clearElement(statusEl);

            const errorDiv = doc.createElement('div');
            errorDiv.className = 'novel-chapter-comments-error';
            errorDiv.setAttribute('role', 'alert');

            const text = doc.createElement('p');
            text.className = 'novel-chapter-comments-error-text';
            text.textContent = message || 'Không thể tải bình luận. Vui lòng thử lại.';

            const retryBtn = doc.createElement('button');
            retryBtn.type = 'button';
            retryBtn.className = 'novel-chapter-comments-retry-btn';
            retryBtn.textContent = 'Thử lại';
            retryBtn.addEventListener('click', function (e) {
                if (e && typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                fetchFeed();
            });

            errorDiv.appendChild(text);
            errorDiv.appendChild(retryBtn);
            statusEl.appendChild(errorDiv);
        }
        if (countEl) {
            countEl.textContent = '';
        }
    }

    /**
     * Executes the initial feed fetch with token-race protection.
     */
    function fetchFeed() {
        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { statusEl, listEl, countEl } = getElements();
        if (!doc || !listEl || !currentChapterId) {
            return;
        }

        const fetchFn = (typeof injectedFetch === 'function')
            ? injectedFetch
            : (typeof window !== 'undefined' && typeof window.fetch === 'function')
                ? window.fetch.bind(window)
                : (typeof fetch === 'function') ? fetch : null;

        if (!fetchFn) {
            renderError('Trình duyệt không hỗ trợ tải dữ liệu.', statusEl, listEl, countEl, doc);
            return;
        }

        const token = ++loadToken;
        renderLoading(statusEl, listEl, countEl, doc);

        const url = '/api/novel/chapters/' + encodeURIComponent(currentChapterId) + '/comments/feed?page=0&size=20';

        fetchFn(url)
            .then(function (res) {
                if (token !== loadToken) {
                    return null;
                }
                if (!res || !res.ok) {
                    const status = res ? res.status : 0;
                    throw new Error('HTTP ' + status);
                }
                return res.json();
            })
            .then(function (data) {
                if (token !== loadToken || !data) {
                    return;
                }
                const items = Array.isArray(data.items) ? data.items : [];
                if (items.length === 0) {
                    renderEmpty(statusEl, listEl, countEl, doc);
                } else {
                    renderPopulated(items, statusEl, listEl, countEl, doc);
                }
            })
            .catch(function (_) {
                if (token !== loadToken) {
                    return;
                }
                renderError('Không thể tải bình luận. Vui lòng thử lại.', statusEl, listEl, countEl, doc);
            });
    }

    /**
     * Handles chapter transition event.
     *
     * @param {Event|Object} evt
     */
    function handleChapterChanged(evt) {
        closeActiveMenu(false);

        const doc = currentDoc || (typeof document !== 'undefined' ? document : null);
        const { sectionEl } = getElements();

        const newChapterId = (evt && evt.detail && evt.detail.chapterId)
            ? String(evt.detail.chapterId).trim()
            : resolveChapterId(doc, sectionEl);

        if (newChapterId) {
            currentChapterId = newChapterId;
            if (sectionEl) {
                sectionEl.setAttribute('data-chapter-id', newChapterId);
            }
            fetchFeed();
        }
    }

    /**
     * Public retry method.
     */
    function retryFetch() {
        fetchFeed();
    }

    /**
     * Initializes the chapter comments module.
     *
     * @param {Document} doc
     * @param {Object} [options]
     */
    function initReaderChapterComments(doc, options) {
        const documentRef = doc || (typeof document !== 'undefined' ? document : null);
        if (!documentRef) {
            return;
        }

        // Clean up any existing listeners on prior document
        if (currentDoc) {
            if (chapterChangedHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            }
            if (documentClickHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('click', documentClickHandler);
            }
            if (documentKeydownHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('keydown', documentKeydownHandler);
            }
        }
        closeActiveMenu(false);

        currentDoc = documentRef;
        const opts = (options && typeof options === 'object') ? options : {};

        if (typeof opts.fetch === 'function') {
            injectedFetch = opts.fetch;
        }

        if (typeof opts.openDiscussionTarget === 'function') {
            injectedOpenDiscussionTarget = opts.openDiscussionTarget;
        } else {
            injectedOpenDiscussionTarget = null;
        }

        const { sectionEl, listEl } = getElements();
        if (!sectionEl || !listEl) {
            // Missing DOM requirements - exit gracefully without error
            return;
        }

        currentChapterId = (opts.chapterId && String(opts.chapterId).trim())
            ? String(opts.chapterId).trim()
            : resolveChapterId(currentDoc, sectionEl);

        if (!currentChapterId) {
            return;
        }

        chapterChangedHandler = function (evt) {
            handleChapterChanged(evt);
        };
        documentClickHandler = function (e) {
            onDocumentClick(e);
        };
        documentKeydownHandler = function (e) {
            onDocumentKeydown(e);
        };

        if (typeof currentDoc.addEventListener === 'function') {
            currentDoc.addEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            currentDoc.addEventListener('click', documentClickHandler);
            currentDoc.addEventListener('keydown', documentKeydownHandler);
        }

        fetchFeed();
    }

    /**
     * Destroys module state, clears DOM, and detaches listeners.
     */
    function destroyReaderChapterComments() {
        loadToken++; // Invalidate any in-flight request
        closeActiveMenu(false);

        if (currentDoc) {
            if (chapterChangedHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener(EVENT_CHAPTER_CHANGED, chapterChangedHandler);
            }
            if (documentClickHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('click', documentClickHandler);
            }
            if (documentKeydownHandler && typeof currentDoc.removeEventListener === 'function') {
                currentDoc.removeEventListener('keydown', documentKeydownHandler);
            }
        }

        const { statusEl, listEl, countEl } = getElements();
        if (listEl) {
            clearElement(listEl);
            listEl.setAttribute('aria-busy', 'false');
        }
        if (statusEl) {
            clearElement(statusEl);
        }
        if (countEl) {
            countEl.textContent = '';
        }

        currentDoc = null;
        currentChapterId = null;
        injectedFetch = null;
        injectedOpenDiscussionTarget = null;
        chapterChangedHandler = null;
        documentClickHandler = null;
        documentKeydownHandler = null;
        currentStatus = 'idle';
        currentItems = [];
    }

    /**
     * Returns current state snapshot (useful for testing/diagnostics).
     *
     * @returns {Object}
     */
    function getState() {
        return {
            chapterId: currentChapterId,
            status: currentStatus,
            items: currentItems,
            loadToken: loadToken
        };
    }

    // Auto-init in browser if DOM is ready
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initReaderChapterComments(document);
            });
        } else {
            initReaderChapterComments(document);
        }
    }

    return {
        SECTION_ID: SECTION_ID,
        TITLE_ID: TITLE_ID,
        COUNT_ID: COUNT_ID,
        STATUS_ID: STATUS_ID,
        LIST_ID: LIST_ID,
        EVENT_CHAPTER_CHANGED: EVENT_CHAPTER_CHANGED,
        init: initReaderChapterComments,
        destroy: destroyReaderChapterComments,
        retry: retryFetch,
        getState: getState,
        formatCommentCount: formatCommentCount,
        formatTimestamp: formatTimestamp,
        isCommentEdited: isCommentEdited,
        resolveTombstoneContextChildDisplayName: resolveTombstoneContextChildDisplayName,
        sanitizeAvatarUrl: sanitizeAvatarUrl,
        createAvatarFallback: createAvatarFallback,
        setFetchImplementation: function (fn) { injectedFetch = fn; },
        isOriginNavigable: isOriginNavigable,
        openOriginDiscussion: openOriginDiscussion,
        closeActiveMenu: closeActiveMenu,
        getActiveOpenMenu: function () { return activeOpenMenu; },
        getHighlightedElement: function () { return null; }
    };
});
