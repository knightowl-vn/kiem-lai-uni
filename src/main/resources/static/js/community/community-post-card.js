/**
 * KiemLai Universe — Shared Community Post Card Renderer (MS-07B8.2.1 / MS-07B8.3.1)
 *
 * Responsibilities:
 * - Pure DOM creation for Community post cards (zero HTML injection for user data).
 * - Exact parity with canonical SSR post-card fragment.
 * - Renders author avatar with fallback, display name, handle, canonical profile link.
 * - Formats relative time via RelativeTime engine (never raw ISO string).
 * - Renders escaped caption and optional responsive image attachment.
 * - Renders reaction widget (authenticated) or read-only login link (guest).
 * - Renders comment count metric.
 * - Renders owner-only action menu (⋯ -> Chỉnh sửa bài viết) for authenticated author.
 * - Manages singleton post caption edit modal with CSRF, live counter, and in-place DOM update.
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

    let currentEditSessionId = 0;
    let activePostId = null;
    let activeCardEl = null;
    let activeOriginalCaption = null;
    let isSubmitting = false;

    function resolveCurrentUserId(doc) {
        if (!doc) return null;
        const metaUser = doc.querySelector('meta[name="current-user-id"]');
        if (metaUser && metaUser.getAttribute('content')) {
            return metaUser.getAttribute('content');
        }
        const feedList = doc.getElementById('communityFeedList');
        if (feedList && feedList.getAttribute('data-current-user-id')) {
            return feedList.getAttribute('data-current-user-id');
        }
        const profileContainer = doc.querySelector('.community-profile-container');
        if (profileContainer && profileContainer.getAttribute('data-current-user-id')) {
            return profileContainer.getAttribute('data-current-user-id');
        }
        if (doc.body && doc.body.dataset && doc.body.dataset.currentUserId) {
            return doc.body.dataset.currentUserId;
        }
        return null;
    }

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

        let currentUserId = null;
        if (opts.currentUserId) {
            currentUserId = String(opts.currentUserId);
        } else if (typeof document !== 'undefined') {
            currentUserId = resolveCurrentUserId(document);
        }

        const isOwner = Boolean(
            isAuthenticated &&
            currentUserId &&
            item.authorUserId &&
            String(currentUserId) === String(item.authorUserId)
        );

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

        // Owner action menu affordance (⋯ -> Chỉnh sửa bài viết)
        if (isOwner) {
            const actionsDropdown = document.createElement('div');
            actionsDropdown.className = 'post-actions-dropdown';

            const triggerBtn = document.createElement('button');
            triggerBtn.type = 'button';
            triggerBtn.className = 'post-actions-trigger';
            triggerBtn.setAttribute('data-action', 'toggle-post-menu');
            triggerBtn.setAttribute('aria-haspopup', 'menu');
            triggerBtn.setAttribute('aria-expanded', 'false');
            triggerBtn.setAttribute('aria-label', 'Tùy chọn bài viết');
            triggerBtn.title = 'Tùy chọn';

            const triggerIcon = document.createElement('i');
            triggerIcon.className = 'fa-solid fa-ellipsis';
            triggerIcon.setAttribute('aria-hidden', 'true');
            triggerBtn.appendChild(triggerIcon);
            actionsDropdown.appendChild(triggerBtn);

            const actionsMenu = document.createElement('div');
            actionsMenu.className = 'post-actions-menu';
            actionsMenu.setAttribute('role', 'menu');
            actionsMenu.hidden = true;

            const editBtn = document.createElement('button');
            editBtn.type = 'button';
            editBtn.className = 'post-actions-item';
            editBtn.setAttribute('role', 'menuitem');
            editBtn.setAttribute('data-action', 'edit-post');
            editBtn.setAttribute('data-post-id', String(item.id));

            const editIcon = document.createElement('i');
            editIcon.className = 'fa-regular fa-pen-to-square me-2';
            editIcon.setAttribute('aria-hidden', 'true');
            editBtn.appendChild(editIcon);

            const editSpan = document.createElement('span');
            editSpan.textContent = 'Chỉnh sửa bài viết';
            editBtn.appendChild(editSpan);

            actionsMenu.appendChild(editBtn);
            actionsDropdown.appendChild(actionsMenu);
            header.appendChild(actionsDropdown);
        }

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
            if (item.currentUserReaction) {
                reactionWidget.setAttribute('data-reaction-current', String(item.currentUserReaction));
            }
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

        // Comment toggle button
        const commentMetric = document.createElement('button');
        commentMetric.type = 'button';
        commentMetric.className = 'post-metric post-comment-toggle-btn';
        commentMetric.setAttribute('data-action', 'toggle-comments');
        commentMetric.setAttribute('data-post-id', String(item.id));
        commentMetric.setAttribute('aria-expanded', 'false');
        commentMetric.title = 'Bình luận';

        const commentIcon = document.createElement('i');
        commentIcon.className = 'fa-regular fa-comment me-1';
        commentIcon.setAttribute('aria-hidden', 'true');
        commentMetric.appendChild(commentIcon);

        const commentCountSpan = document.createElement('span');
        commentCountSpan.className = 'post-comment-count';
        commentCountSpan.textContent = item.commentCount || 0;
        commentMetric.appendChild(commentCountSpan);
        commentMetric.appendChild(document.createTextNode(' bình luận'));
        footer.appendChild(commentMetric);

        article.appendChild(footer);

        // Inline Comments Container (Lazy-loaded on first toggle)
        const commentsContainer = document.createElement('section');
        commentsContainer.className = 'post-comments-container';
        commentsContainer.setAttribute('data-post-comments', String(item.id));
        commentsContainer.hidden = true;
        article.appendChild(commentsContainer);

        return article;
    }

    // =========================================================================
    // Action Menu & Singleton Modal Management
    // =========================================================================

    function closeAllPostMenus(doc) {
        if (!doc) return;
        const menus = doc.querySelectorAll('.post-actions-menu');
        menus.forEach(menu => {
            menu.hidden = true;
        });
        const triggers = doc.querySelectorAll('.post-actions-trigger[aria-expanded="true"]');
        triggers.forEach(trigger => {
            trigger.setAttribute('aria-expanded', 'false');
        });
    }

    function togglePostMenu(triggerBtn) {
        if (!triggerBtn) return;
        const dropdown = triggerBtn.closest('.post-actions-dropdown');
        if (!dropdown) return;
        const menu = dropdown.querySelector('.post-actions-menu');
        if (!menu) return;

        const isCurrentlyOpen = !menu.hidden && triggerBtn.getAttribute('aria-expanded') === 'true';
        const doc = triggerBtn.ownerDocument || document;

        closeAllPostMenus(doc);

        if (!isCurrentlyOpen) {
            menu.hidden = false;
            triggerBtn.setAttribute('aria-expanded', 'true');
        }
    }

    function getOrCreateEditModal(doc) {
        if (!doc) return null;
        let modal = doc.getElementById('communityEditPostModal');
        if (modal) return modal;

        modal = doc.createElement('div');
        modal.id = 'communityEditPostModal';
        modal.className = 'kl-modal community-edit-modal';
        modal.setAttribute('role', 'dialog');
        modal.setAttribute('aria-modal', 'true');
        modal.setAttribute('aria-labelledby', 'communityEditModalTitle');
        modal.hidden = true;

        const backdrop = doc.createElement('div');
        backdrop.className = 'kl-modal-backdrop';
        backdrop.setAttribute('data-action', 'close-edit-modal');
        modal.appendChild(backdrop);

        const dialog = doc.createElement('div');
        dialog.className = 'kl-modal-dialog';

        const content = doc.createElement('div');
        content.className = 'kl-modal-content';

        const header = doc.createElement('div');
        header.className = 'kl-modal-header';

        const title = doc.createElement('h2');
        title.id = 'communityEditModalTitle';
        title.className = 'kl-modal-title';
        title.textContent = 'Chỉnh sửa bài viết';
        header.appendChild(title);

        const closeBtn = doc.createElement('button');
        closeBtn.type = 'button';
        closeBtn.id = 'communityEditCloseBtn';
        closeBtn.className = 'kl-modal-close';
        closeBtn.setAttribute('data-action', 'close-edit-modal');
        closeBtn.setAttribute('aria-label', 'Đóng');
        closeBtn.textContent = '×';
        header.appendChild(closeBtn);
        content.appendChild(header);

        const form = doc.createElement('form');
        form.id = 'communityEditPostForm';
        form.className = 'community-edit-form';

        const body = doc.createElement('div');
        body.className = 'kl-modal-body';

        const alertDiv = doc.createElement('div');
        alertDiv.id = 'communityEditModalAlert';
        alertDiv.className = 'alert alert-danger';
        alertDiv.setAttribute('role', 'alert');
        alertDiv.hidden = true;
        body.appendChild(alertDiv);

        const label = doc.createElement('label');
        label.setAttribute('for', 'communityEditCaptionInput');
        label.className = 'visually-hidden';
        label.textContent = 'Nội dung bài viết';
        body.appendChild(label);

        const textarea = doc.createElement('textarea');
        textarea.id = 'communityEditCaptionInput';
        textarea.className = 'community-edit-textarea';
        textarea.rows = 4;
        textarea.maxLength = 2000;
        textarea.placeholder = 'Chia sẻ suy nghĩ của bạn...';
        body.appendChild(textarea);

        const charRow = doc.createElement('div');
        charRow.className = 'community-edit-char-row';

        const charCount = doc.createElement('span');
        charCount.id = 'communityEditCharCount';
        charCount.className = 'community-edit-char-count';
        charCount.textContent = '0 / 2000';
        charRow.appendChild(charCount);
        body.appendChild(charRow);

        form.appendChild(body);

        const footer = doc.createElement('div');
        footer.className = 'kl-modal-footer';

        const cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.id = 'communityEditCancelBtn';
        cancelBtn.className = 'btn btn-secondary';
        cancelBtn.setAttribute('data-action', 'close-edit-modal');
        cancelBtn.textContent = 'Hủy';
        footer.appendChild(cancelBtn);

        const submitBtn = doc.createElement('button');
        submitBtn.type = 'submit';
        submitBtn.id = 'communityEditSubmitBtn';
        submitBtn.className = 'btn btn-primary community-edit-save-btn';

        const submitText = doc.createElement('span');
        submitText.id = 'communityEditSubmitText';
        submitText.textContent = 'Lưu thay đổi';
        submitBtn.appendChild(submitText);

        const spinner = doc.createElement('span');
        spinner.id = 'communityEditSubmitSpinner';
        spinner.className = 'spinner-border spinner-border-sm ms-1';
        spinner.setAttribute('role', 'status');
        spinner.hidden = true;
        submitBtn.appendChild(spinner);

        footer.appendChild(submitBtn);
        form.appendChild(footer);
        content.appendChild(form);
        dialog.appendChild(content);
        modal.appendChild(dialog);

        textarea.addEventListener('input', function () {
            charCount.textContent = textarea.value.length + ' / 2000';
        });

        form.addEventListener('submit', handleEditSubmit);

        doc.body.appendChild(modal);
        return modal;
    }

    function setSubmittingState(modal, submitting) {
        isSubmitting = submitting;
        if (!modal) return;
        const submitBtn = modal.querySelector('#communityEditSubmitBtn');
        const spinner = modal.querySelector('#communityEditSubmitSpinner');
        const textarea = modal.querySelector('#communityEditCaptionInput');
        const cancelBtn = modal.querySelector('#communityEditCancelBtn') || modal.querySelector('.btn-secondary');
        const closeBtn = modal.querySelector('#communityEditCloseBtn') || modal.querySelector('.kl-modal-close');

        if (submitBtn) submitBtn.disabled = submitting;
        if (spinner) spinner.hidden = !submitting;
        if (textarea) textarea.disabled = submitting;
        if (cancelBtn) cancelBtn.disabled = submitting;
        if (closeBtn) closeBtn.disabled = submitting;
    }

    function openEditModal(postId, cardEl, doc) {
        if (isSubmitting) {
            return false;
        }

        const targetDoc = doc || (cardEl ? cardEl.ownerDocument : document);
        if (!targetDoc) return false;

        currentEditSessionId++;

        const modal = getOrCreateEditModal(targetDoc);
        activePostId = postId;
        activeCardEl = cardEl;

        const captionEl = cardEl ? cardEl.querySelector('.post-caption') : null;
        const currentCaption = captionEl ? (captionEl.textContent || '') : '';
        activeOriginalCaption = currentCaption;

        const textarea = modal.querySelector('#communityEditCaptionInput');
        const charCount = modal.querySelector('#communityEditCharCount');
        const alertDiv = modal.querySelector('#communityEditModalAlert');

        if (textarea) {
            textarea.value = currentCaption;
            if (charCount) {
                charCount.textContent = currentCaption.length + ' / 2000';
            }
        }
        if (alertDiv) {
            alertDiv.textContent = '';
            alertDiv.hidden = true;
        }

        setSubmittingState(modal, false);
        modal.hidden = false;
        closeAllPostMenus(targetDoc);

        if (textarea && typeof textarea.focus === 'function') {
            textarea.focus();
        }
        return true;
    }

    function closeEditModal(doc) {
        if (isSubmitting) {
            return false;
        }

        const targetDoc = doc || document;
        const modal = targetDoc ? targetDoc.getElementById('communityEditPostModal') : null;
        if (modal) {
            modal.hidden = true;
            const alertDiv = modal.querySelector('#communityEditModalAlert');
            if (alertDiv) {
                alertDiv.textContent = '';
                alertDiv.hidden = true;
            }
            setSubmittingState(modal, false);
        }
        currentEditSessionId++;
        activePostId = null;
        activeCardEl = null;
        activeOriginalCaption = null;
        isSubmitting = false;
        return true;
    }

    async function handleEditSubmit(event) {
        if (event && typeof event.preventDefault === 'function') {
            event.preventDefault();
        }
        const doc = (event && event.target && event.target.ownerDocument) ? event.target.ownerDocument : document;
        const modal = doc.getElementById('communityEditPostModal');
        if (!modal || !activePostId || isSubmitting) return;

        const submittedSessionId = currentEditSessionId;
        const submittedPostId = activePostId;
        const submittedCardEl = activeCardEl;

        const textarea = modal.querySelector('#communityEditCaptionInput');
        const alertDiv = modal.querySelector('#communityEditModalAlert');
        const newCaption = textarea ? textarea.value : '';

        // Client validation
        if (!newCaption || newCaption.trim().length === 0) {
            if (alertDiv) {
                alertDiv.textContent = 'Nội dung bài viết không được để trống.';
                alertDiv.hidden = false;
            }
            return;
        }

        if (newCaption.trim().length > 2000) {
            if (alertDiv) {
                alertDiv.textContent = 'Nội dung bài viết không được vượt quá 2000 ký tự.';
                alertDiv.hidden = false;
            }
            return;
        }

        setSubmittingState(modal, true);
        if (alertDiv) {
            alertDiv.textContent = '';
            alertDiv.hidden = true;
        }

        // Read CSRF meta
        const csrfTokenMeta = doc.querySelector('meta[name="_csrf"]');
        const csrfHeaderMeta = doc.querySelector('meta[name="_csrf_header"]');
        const csrfToken = csrfTokenMeta ? csrfTokenMeta.getAttribute('content') : null;
        const csrfHeader = csrfHeaderMeta ? csrfHeaderMeta.getAttribute('content') : null;

        const headers = {
            'Content-Type': 'application/json'
        };
        if (csrfToken && csrfHeader) {
            headers[csrfHeader] = csrfToken;
        }

        try {
            const fetchFn = (typeof globalThis !== 'undefined' && globalThis.fetch)
                ? globalThis.fetch.bind(globalThis)
                : (typeof window !== 'undefined' && window.fetch)
                    ? window.fetch.bind(window)
                    : null;

            if (!fetchFn) {
                throw new Error('fetch is not available');
            }

            const response = await fetchFn('/api/community/posts/' + encodeURIComponent(submittedPostId), {
                method: 'PATCH',
                headers: headers,
                body: JSON.stringify({ caption: newCaption })
            });

            // Discard stale response if edit session was closed or switched
            if (submittedSessionId !== currentEditSessionId) {
                return;
            }

            // 1. Explicit redirect handling (e.g. Spring Security 302 -> /login or /access-denied)
            const isRedirected = Boolean(
                response.redirected ||
                (response.url && (response.url.includes('/login') || response.url.includes('/access-denied')))
            );
            const urlStr = response.url || '';

            if (isRedirected || urlStr.includes('/login') || urlStr.includes('/access-denied')) {
                let redirectMsg = 'Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.';
                if (urlStr.includes('/login')) {
                    redirectMsg = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
                } else if (urlStr.includes('/access-denied')) {
                    redirectMsg = 'Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.';
                }
                if (alertDiv) {
                    alertDiv.textContent = redirectMsg;
                    alertDiv.hidden = false;
                }
                setSubmittingState(modal, false);
                return;
            }

            // 2. Content-Type verification
            let contentType = '';
            if (response.headers) {
                if (typeof response.headers.get === 'function') {
                    contentType = response.headers.get('content-type') || response.headers.get('Content-Type') || '';
                } else if (typeof response.headers === 'object') {
                    contentType = response.headers['content-type'] || response.headers['Content-Type'] || '';
                }
            }
            const isJson = contentType.includes('application/json');

            if (response.ok) {
                if (!isJson) {
                    if (alertDiv) {
                        alertDiv.textContent = 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.';
                        alertDiv.hidden = false;
                    }
                    setSubmittingState(modal, false);
                    return;
                }

                let updatedPost = null;
                try {
                    updatedPost = await response.json();
                } catch (_) {
                    if (alertDiv) {
                        alertDiv.textContent = 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.';
                        alertDiv.hidden = false;
                    }
                    setSubmittingState(modal, false);
                    return;
                }

                // Payload validation: object, matching id, string caption
                const isObject = typeof updatedPost === 'object' && updatedPost !== null;
                const hasMatchingId = isObject && String(updatedPost.id) === String(submittedPostId);
                const hasStringCaption = isObject && typeof updatedPost.caption === 'string';

                if (!isObject || !hasMatchingId || !hasStringCaption) {
                    if (alertDiv) {
                        alertDiv.textContent = 'Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.';
                        alertDiv.hidden = false;
                    }
                    setSubmittingState(modal, false);
                    return;
                }

                if (submittedSessionId !== currentEditSessionId) {
                    return;
                }

                if (submittedCardEl) {
                    const captionEl = submittedCardEl.querySelector('.post-caption');
                    if (captionEl) {
                        captionEl.textContent = updatedPost.caption;
                    }
                }
                setSubmittingState(modal, false);
                closeEditModal(doc);
            } else {
                let errorMsg = 'Không thể cập nhật bài viết. Vui lòng thử lại.';
                if (isJson) {
                    try {
                        const errData = await response.json();
                        if (errData && errData.message) {
                            errorMsg = errData.message;
                        }
                    } catch (_) {
                        // Non-JSON or empty response body
                    }
                }

                if (response.status === 401) {
                    errorMsg = 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
                } else if (response.status === 403) {
                    errorMsg = 'Bạn không có quyền chỉnh sửa bài viết này.';
                } else if (response.status === 404) {
                    errorMsg = 'Bài viết không tồn tại hoặc đã bị xóa.';
                }

                if (submittedSessionId !== currentEditSessionId) {
                    return;
                }

                if (alertDiv) {
                    alertDiv.textContent = errorMsg;
                    alertDiv.hidden = false;
                }
                setSubmittingState(modal, false);
            }
        } catch (err) {
            if (submittedSessionId !== currentEditSessionId) {
                return;
            }
            if (alertDiv) {
                alertDiv.textContent = 'Lỗi kết nối máy chủ. Vui lòng thử lại.';
                alertDiv.hidden = false;
            }
            setSubmittingState(modal, false);
        }
    }

    function initDelegation(doc) {
        const targetDoc = doc || (typeof document !== 'undefined' ? document : null);
        if (!targetDoc || targetDoc._communityDelegationInitialized) return;
        targetDoc._communityDelegationInitialized = true;

        targetDoc.addEventListener('click', function (e) {
            const target = e.target;
            if (!target) return;

            // 1. Toggle action menu trigger
            const trigger = target.closest ? target.closest('[data-action="toggle-post-menu"]') : null;
            if (trigger) {
                e.preventDefault();
                e.stopPropagation();
                togglePostMenu(trigger);
                return;
            }

            // 2. Edit post item click
            const editBtn = target.closest ? target.closest('[data-action="edit-post"]') : null;
            if (editBtn) {
                e.preventDefault();
                e.stopPropagation();
                if (isSubmitting) return;
                const cardEl = editBtn.closest('.community-post-card');
                const postId = editBtn.getAttribute('data-post-id') || (cardEl ? cardEl.getAttribute('data-post-id') : null);
                if (postId && cardEl) {
                    openEditModal(postId, cardEl, targetDoc);
                }
                return;
            }

            // 3. Close edit modal click
            const closeBtn = target.closest ? target.closest('[data-action="close-edit-modal"]') : null;
            if (closeBtn) {
                e.preventDefault();
                if (isSubmitting) return;
                closeEditModal(targetDoc);
                return;
            }

            // 4. Outside clicks close open dropdown menus
            if (!target.closest || !target.closest('.post-actions-dropdown')) {
                closeAllPostMenus(targetDoc);
            }
        });

        targetDoc.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' || e.keyCode === 27) {
                if (isSubmitting) return;
                const modal = targetDoc.getElementById('communityEditPostModal');
                if (modal && !modal.hidden) {
                    closeEditModal(targetDoc);
                } else {
                    closeAllPostMenus(targetDoc);
                }
            }
        });
    }

    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', function () {
                initDelegation(document);
            });
        } else {
            initDelegation(document);
        }
    }

    return {
        create: createPostCardElement,
        resolveCurrentUserId: resolveCurrentUserId,
        openEditModal: openEditModal,
        closeEditModal: closeEditModal,
        closeAllPostMenus: closeAllPostMenus,
        togglePostMenu: togglePostMenu,
        getOrCreateEditModal: getOrCreateEditModal,
        handleEditSubmit: handleEditSubmit,
        initDelegation: initDelegation,
        getCurrentEditSessionId: function () {
            return currentEditSessionId;
        },
        isSubmitting: function () {
            return isSubmitting;
        }
    };
});
