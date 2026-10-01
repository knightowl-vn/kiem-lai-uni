/**
 * KiemLai Universe — Shared Community Post Card Renderer (MS-07B8.2.1)
 *
 * Responsibilities:
 * - Pure DOM creation for Community post cards (zero HTML injection for user data).
 * - Exact parity with canonical SSR post-card fragment.
 * - Renders author avatar with fallback, display name, handle, canonical profile link.
 * - Formats relative time via RelativeTime engine (never raw ISO string).
 * - Renders escaped caption and optional responsive image attachment.
 * - Renders reaction widget (authenticated) or read-only login link (guest).
 * - Renders comment count metric.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.CommunityPostCard = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommunityPostCard = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const defaultAvatar = '/images/default_avatar.jpg';

    function createPostCardElement(item, options) {
        if (!item || typeof item !== 'object') {
            throw new Error('item must be an object');
        }

        const opts = options || {};
        let isAuthenticated = false;
        if (typeof opts.isAuthenticated === 'boolean') {
            isAuthenticated = opts.isAuthenticated;
        } else if (typeof document !== 'undefined') {
            const feedList = document.getElementById('communityFeedList');
            const profileContainer = document.querySelector('.community-profile-container');
            if (feedList && feedList.getAttribute('data-authenticated') === 'true') {
                isAuthenticated = true;
            } else if (profileContainer && profileContainer.getAttribute('data-authenticated') === 'true') {
                isAuthenticated = true;
            } else if (document.body && document.body.dataset && document.body.dataset.authenticated === 'true') {
                isAuthenticated = true;
            }
        }

        const article = document.createElement('article');
        article.className = 'community-post-card';
        article.setAttribute('data-post-id', item.id);
        if (item.authorUserId) {
            article.setAttribute('data-author-id', item.authorUserId);
        }

        // Header
        const header = document.createElement('header');
        header.className = 'post-header';

        const authorInfo = document.createElement('div');
        authorInfo.className = 'post-author-info';

        const hasHandle = item.authorPublicHandle && item.authorPublicHandle.trim().length > 0;
        const avatarUrl = (item.authorAvatarUrl && item.authorAvatarUrl.trim().length > 0)
            ? item.authorAvatarUrl
            : defaultAvatar;
        const displayName = (item.authorDisplayName && item.authorDisplayName.trim().length > 0)
            ? item.authorDisplayName
            : 'Người dùng';

        const avatarImg = document.createElement('img');
        avatarImg.className = 'post-author-avatar';
        avatarImg.src = avatarUrl;
        avatarImg.alt = 'Ảnh đại diện';
        avatarImg.onerror = function () {
            this.onerror = null;
            this.src = defaultAvatar;
        };

        if (hasHandle) {
            const avatarLink = document.createElement('a');
            avatarLink.className = 'post-author-avatar-link';
            avatarLink.href = '/community/@' + encodeURIComponent(item.authorPublicHandle);
            avatarLink.appendChild(avatarImg);
            authorInfo.appendChild(avatarLink);
        } else {
            authorInfo.appendChild(avatarImg);
        }

        const postMeta = document.createElement('div');
        postMeta.className = 'post-meta';

        const authorRow = document.createElement('div');
        authorRow.className = 'post-author-row';

        if (hasHandle) {
            const authorNameLink = document.createElement('a');
            authorNameLink.className = 'post-author-name';
            authorNameLink.href = '/community/@' + encodeURIComponent(item.authorPublicHandle);
            authorNameLink.textContent = displayName;
            authorRow.appendChild(authorNameLink);

            const handleSpan = document.createElement('span');
            handleSpan.className = 'post-author-handle';
            handleSpan.textContent = '@' + item.authorPublicHandle;
            authorRow.appendChild(handleSpan);
        } else {
            const authorNameSpan = document.createElement('span');
            authorNameSpan.className = 'post-author-name';
            authorNameSpan.textContent = displayName;
            authorRow.appendChild(authorNameSpan);
        }

        // Timestamp (Never exposes raw ISO text)
        const timeEl = document.createElement('time');
        timeEl.className = 'post-time';
        timeEl.setAttribute('datetime', item.createdAt);
        timeEl.setAttribute('data-relative-time', '');
        if (typeof window !== 'undefined' && window.RelativeTime && typeof window.RelativeTime.format === 'function') {
            timeEl.textContent = window.RelativeTime.format(item.createdAt);
        } else {
            timeEl.textContent = 'Vừa xong';
        }

        postMeta.appendChild(authorRow);
        postMeta.appendChild(timeEl);
        authorInfo.appendChild(postMeta);
        header.appendChild(authorInfo);
        article.appendChild(header);

        // Caption (XSS-safe via textContent)
        const captionP = document.createElement('p');
        captionP.className = 'post-caption';
        captionP.textContent = item.caption;
        article.appendChild(captionP);

        // Image attachment
        if (item.imageUrl && item.imageUrl.trim().length > 0) {
            const imgContainer = document.createElement('div');
            imgContainer.className = 'post-image-container';

            const postImg = document.createElement('img');
            postImg.className = 'post-image';
            postImg.src = item.imageUrl;
            postImg.alt = 'Ảnh đính kèm';
            postImg.loading = 'lazy';

            imgContainer.appendChild(postImg);
            article.appendChild(imgContainer);
        }

        // Footer
        const footer = document.createElement('footer');
        footer.className = 'post-footer';

        if (isAuthenticated) {
            // Authenticated: Reaction Picker widget for COMMUNITY_POST
            const reactionWidget = document.createElement('div');
            reactionWidget.className = 'kl-reaction-widget';
            reactionWidget.setAttribute('data-reaction-widget', '');
            reactionWidget.setAttribute('data-reaction-target-type', 'COMMUNITY_POST');
            reactionWidget.setAttribute('data-reaction-target-id', String(item.id));
            reactionWidget.setAttribute('data-reaction-total', String(item.reactionCount || 0));
            footer.appendChild(reactionWidget);
        } else {
            // Guest: Read-only thumbs-up metric linking to login
            const guestLike = document.createElement('a');
            guestLike.className = 'post-metric post-metric--login-link';
            let returnTo = '/community';
            if (typeof window !== 'undefined' && window.location) {
                returnTo = encodeURIComponent(window.location.pathname + window.location.search);
            }
            guestLike.href = '/login?returnTo=' + returnTo;
            guestLike.title = 'Đăng nhập để thích';

            const likeIcon = document.createElement('i');
            likeIcon.className = 'fa-regular fa-thumbs-up me-1';
            guestLike.appendChild(likeIcon);

            const likeCountSpan = document.createElement('span');
            likeCountSpan.textContent = item.reactionCount || 0;
            guestLike.appendChild(likeCountSpan);

            guestLike.appendChild(document.createTextNode(' lượt thích'));
            footer.appendChild(guestLike);
        }

        // Comment count metric (Read-only for B8.2.1)
        const commentMetric = document.createElement('span');
        commentMetric.className = 'post-metric';
        const commentIcon = document.createElement('i');
        commentIcon.className = 'fa-regular fa-comment me-1';
        commentMetric.appendChild(commentIcon);
        const commentCountSpan = document.createElement('span');
        commentCountSpan.className = 'post-comment-count';
        commentCountSpan.textContent = item.commentCount || 0;
        commentMetric.appendChild(commentCountSpan);
        commentMetric.appendChild(document.createTextNode(' bình luận'));
        footer.appendChild(commentMetric);

        article.appendChild(footer);
        return article;
    }

    return {
        create: createPostCardElement
    };
});
