/**
 * KiemLai Community V1 — Composer Controller
 *
 * Handles post creation via POST /api/community/posts:
 * - Caption character counter and limit validation (<= 2000 chars).
 * - Client-side image validation (image/jpeg, image/png, image/webp, max 10MB).
 * - Image attachment preview and removal.
 * - CSRF token extraction and injection.
 * - Double-submit lock and loading spinner.
 * - Error alert handling.
 * - Success callback triggering feed reload on NEWEST tab.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.CommunityComposer = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.CommunityComposer = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    const MAX_CAPTION_LENGTH = 2000;
    const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

    let injectedFetch = null;
    let previewGeneration = 0;
    let activeDoc = null;

    function getHeaderValue(headers, name) {
        if (!headers) return '';
        if (typeof headers.get === 'function') return headers.get(name) || '';
        return headers[name] || headers[name.toLowerCase()] || '';
    }

    async function parseComposerResponse(response) {
        const isRedirected = Boolean(
            response.redirected ||
            (response.url && (response.url.includes('/login') || response.url.includes('/access-denied')))
        );
        const urlStr = response.url || '';

        if (isRedirected || urlStr.includes('/login') || urlStr.includes('/access-denied')) {
            if (urlStr.includes('/login')) {
                throw new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
            }
            if (urlStr.includes('/access-denied')) {
                throw new Error('Yêu cầu không hợp lệ hoặc phiên làm việc đã hết hạn. Vui lòng tải lại trang.');
            }
            throw new Error('Phiên làm việc đã thay đổi. Vui lòng tải lại trang.');
        }

        const rawContentType = getHeaderValue(response.headers, 'content-type');
        const isHtml = rawContentType.toLowerCase().includes('text/html');
        const isJson = rawContentType
            ? rawContentType.toLowerCase().includes('application/json')
            : (response.headers === undefined);

        if (response.status === 201) {
            if (isHtml || !isJson) {
                throw new Error('Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
            }
            try {
                return await response.json();
            } catch (_) {
                throw new Error('Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
            }
        }

        // Any other 2xx status (e.g. 200, 204) is NOT accepted as successful create
        if (response.status >= 200 && response.status < 300) {
            throw new Error('Phản hồi máy chủ không hợp lệ. Vui lòng thử lại.');
        }

        if (response.status === 401) {
            throw new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
        }
        if (response.status === 403) {
            throw new Error('Bạn không có quyền thực hiện hành động này.');
        }

        if (isJson && !isHtml) {
            let errJson = null;
            try {
                errJson = await response.json();
            } catch (_) {
                // Malformed JSON error body: swallow parser error and fall through to generic error
            }
            if (errJson && typeof errJson.message === 'string' && errJson.message.trim().length > 0) {
                throw new Error(errJson.message.trim());
            }
        }

        throw new Error('Đã xảy ra lỗi khi đăng bài. Vui lòng thử lại.');
    }

    function setupPendingTray(trigger, list) {
        if (!trigger || !list || trigger.__pendingTrayBound) return;
        trigger.__pendingTrayBound = true;

        function toggleTray() {
            const isExpanded = trigger.getAttribute('aria-expanded') === 'true';
            if (isExpanded) {
                trigger.setAttribute('aria-expanded', 'false');
                list.setAttribute('hidden', '');
            } else {
                trigger.setAttribute('aria-expanded', 'true');
                list.removeAttribute('hidden');
            }
        }

        trigger.addEventListener('click', function () {
            toggleTray();
        });

        trigger.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13 || e.keyCode === 32) {
                if (typeof e.preventDefault === 'function') {
                    e.preventDefault();
                }
                toggleTray();
            }
        });
    }

    function initComposer(options) {
        const opts = options || {};
        const doc = opts.document || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;
        activeDoc = doc;

        if (opts.fetch) {
            injectedFetch = opts.fetch;
        }

        const pendingTrigger = doc.getElementById('communityOwnPendingTrigger');
        const pendingList = doc.getElementById('communityOwnPendingList');
        if (pendingTrigger && pendingList) {
            setupPendingTray(pendingTrigger, pendingList);
        }

        const form = doc.getElementById('communityComposerForm');
        if (!form) {
            return {
                togglePendingTray: function (expand) {
                    const trigger = doc.getElementById('communityOwnPendingTrigger');
                    const list = doc.getElementById('communityOwnPendingList');
                    if (trigger && list) {
                        const current = trigger.getAttribute('aria-expanded') === 'true';
                        const target = typeof expand === 'boolean' ? expand : !current;
                        trigger.setAttribute('aria-expanded', target ? 'true' : 'false');
                        if (target) {
                            list.removeAttribute('hidden');
                        } else {
                            list.setAttribute('hidden', '');
                        }
                    }
                },
                isPendingTrayExpanded: function () {
                    const trigger = doc.getElementById('communityOwnPendingTrigger');
                    return trigger ? trigger.getAttribute('aria-expanded') === 'true' : false;
                },
                getPendingCount: function () {
                    const badge = doc.getElementById('communityOwnPendingCount');
                    if (badge && badge.textContent) {
                        const parsed = parseInt(badge.textContent.trim(), 10);
                        return isNaN(parsed) ? 0 : parsed;
                    }
                    const list = doc.getElementById('communityOwnPendingList');
                    return list ? list.querySelectorAll('.community-post-card--pending').length : 0;
                }
            };
        }

        const composerTrigger = doc.getElementById('communityComposerTrigger');
        const composerPanel = doc.getElementById('communityComposerPanel');
        const collapseBtn = doc.getElementById('composerCollapseBtn');
        const captionInput = doc.getElementById('composerCaption');
        const charCountEl = doc.getElementById('composerCharCount');
        const imageInput = doc.getElementById('composerImageInput');
        const addImageBtn = doc.getElementById('composerAddImageBtn');
        const imagePreviewContainer = doc.getElementById('composerImagePreview');
        const imagePreviewImg = doc.getElementById('composerImagePreviewImg');
        const removeImageBtn = doc.getElementById('composerRemoveImageBtn');
        const submitBtn = doc.getElementById('composerSubmitBtn');
        const submitSpinner = doc.getElementById('composerSubmitSpinner');
        const errorAlert = doc.getElementById('composerError');
        const successAlert = doc.getElementById('composerSuccess');

        let isSubmitting = false;

        function expandComposer(options) {
            const o = options || {};
            if (composerTrigger) {
                composerTrigger.setAttribute('aria-expanded', 'true');
                composerTrigger.setAttribute('hidden', '');
            }
            if (composerPanel) {
                composerPanel.removeAttribute('hidden');
            }
            hideSuccess();
            hideError();
            if (o.focusInput !== false && captionInput && typeof captionInput.focus === 'function') {
                captionInput.focus();
            }
        }

        function collapseComposer(options) {
            const o = options || {};
            if (composerPanel) {
                composerPanel.setAttribute('hidden', '');
            }
            if (composerTrigger) {
                composerTrigger.removeAttribute('hidden');
                composerTrigger.setAttribute('aria-expanded', 'false');
                if (o.focusTrigger && typeof composerTrigger.focus === 'function') {
                    composerTrigger.focus();
                }
            }
        }

        if (composerTrigger) {
            composerTrigger.setAttribute('aria-expanded', 'false');
            composerTrigger.removeAttribute('hidden');
            if (composerPanel) {
                composerPanel.setAttribute('hidden', '');
            }

            composerTrigger.addEventListener('click', function () {
                expandComposer();
            });

            composerTrigger.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13 || e.keyCode === 32) {
                    if (typeof e.preventDefault === 'function') {
                        e.preventDefault();
                    }
                    expandComposer();
                }
            });
        }

        if (collapseBtn) {
            collapseBtn.addEventListener('click', function () {
                collapseComposer({ focusTrigger: true });
            });
        }

        if (composerPanel) {
            composerPanel.addEventListener('keydown', function (e) {
                if (e.key === 'Escape' || e.keyCode === 27) {
                    collapseComposer({ focusTrigger: true });
                }
            });
        }

        // Caption character count
        if (captionInput && charCountEl) {
            captionInput.addEventListener('input', function () {
                hideSuccess();
                hideError();
                const len = captionInput.value.length;
                charCountEl.textContent = len + '/' + MAX_CAPTION_LENGTH;
                if (len > MAX_CAPTION_LENGTH) {
                    charCountEl.classList.add('text-danger');
                } else {
                    charCountEl.classList.remove('text-danger');
                }
            });
        }

        // Add Image button triggers hidden file input
        if (addImageBtn && imageInput) {
            addImageBtn.addEventListener('click', function () {
                imageInput.click();
            });
        }

        // Image selection & preview with monotonic generation guard
        if (imageInput) {
            imageInput.addEventListener('change', function () {
                hideError();
                const currentGen = ++previewGeneration;
                const file = imageInput.files && imageInput.files[0];
                if (!file) {
                    clearImagePreview();
                    return;
                }

                if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
                    showError('Định dạng ảnh không hợp lệ. Chỉ chấp nhận JPG, PNG hoặc WebP.');
                    clearImageInput();
                    return;
                }

                if (file.size > MAX_IMAGE_SIZE_BYTES) {
                    showError('Kích thước ảnh vượt quá giới hạn 10 MB.');
                    clearImageInput();
                    return;
                }

                const reader = new FileReader();
                reader.onload = function (e) {
                    if (currentGen !== previewGeneration) {
                        return; // Discard stale preview callback
                    }
                    if (imagePreviewImg && imagePreviewContainer) {
                        imagePreviewImg.src = e.target.result;
                        imagePreviewContainer.removeAttribute('hidden');
                    }
                };
                reader.readAsDataURL(file);
            });
        }

        // Remove attached image
        if (removeImageBtn) {
            removeImageBtn.addEventListener('click', function () {
                previewGeneration++;
                clearImageInput();
                clearImagePreview();
            });
        }

        // Form submission
        form.addEventListener('submit', function (e) {
            if (e && typeof e.preventDefault === 'function') {
                e.preventDefault();
            }
            if (isSubmitting) {
                return;
            }

            hideError();
            hideSuccess();

            const caption = captionInput ? captionInput.value.trim() : '';
            if (!caption) {
                showError('Vui lòng nhập nội dung bài viết.');
                if (captionInput && typeof captionInput.focus === 'function') {
                    captionInput.focus();
                }
                return;
            }

            if (caption.length > MAX_CAPTION_LENGTH) {
                showError('Nội dung bài viết vượt quá 2.000 ký tự.');
                return;
            }

            const formData = (typeof FormData !== 'undefined') ? new FormData() : { append: () => {} };
            formData.append('caption', caption);

            const file = imageInput && imageInput.files && imageInput.files[0];
            if (file) {
                if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
                    showError('Định dạng ảnh không hợp lệ. Chỉ chấp nhận JPG, PNG hoặc WebP.');
                    return;
                }
                if (file.size > MAX_IMAGE_SIZE_BYTES) {
                    showError('Kích thước ảnh vượt quá giới hạn 10 MB.');
                    return;
                }
                formData.append('image', file);
            }

            // Lock submission
            setSubmitting(true);

            // Extract CSRF
            const headers = {};
            const csrfTokenMeta = (doc && typeof doc.querySelector === 'function') ? doc.querySelector('meta[name="_csrf"]') : document.querySelector('meta[name="_csrf"]');
            const csrfHeaderMeta = (doc && typeof doc.querySelector === 'function') ? doc.querySelector('meta[name="_csrf_header"]') : document.querySelector('meta[name="_csrf_header"]');
            if (csrfTokenMeta && csrfHeaderMeta) {
                const headerName = csrfHeaderMeta.getAttribute('content');
                const token = csrfTokenMeta.getAttribute('content');
                if (headerName && token) {
                    headers[headerName] = token;
                }
            }

            const requestOptions = {
                method: 'POST',
                headers: headers,
                body: formData
            };
            const requestPromise = injectedFetch
                ? injectedFetch('/api/community/posts', requestOptions)
                : fetch('/api/community/posts', requestOptions);

            requestPromise
                .then(function (response) {
                    return parseComposerResponse(response);
                })
                .then(function (createdPost) {
                    // Canonical 201 create success committed: reset composer and clear errors
                    try {
                        resetComposer();
                    } catch (resetErr) {
                        console.error('Composer reset error:', resetErr);
                    }

                    if (createdPost && createdPost.status === 'PENDING_REVIEW') {
                        showSuccess('Bài viết đã được gửi và đang chờ quản trị viên duyệt.');
                        try {
                            renderOwnPendingPost(createdPost);
                        } catch (renderPendingErr) {
                            console.error('Pending post render error:', renderPendingErr);
                        }
                        collapseComposer({ focusTrigger: false });
                        return;
                    }

                    collapseComposer({ focusTrigger: false });

                    // Refresh feed on NEWEST tab as an independent post-success concern
                    try {
                        const feedModule = (typeof window !== 'undefined' && window.CommunityFeed)
                            ? window.CommunityFeed
                            : ((typeof globalThis !== 'undefined' && globalThis.CommunityFeed) ? globalThis.CommunityFeed : null);

                        if (feedModule && typeof feedModule.refreshFeed === 'function') {
                            const refreshResult = feedModule.refreshFeed('NEWEST');
                            if (refreshResult && typeof refreshResult.catch === 'function') {
                                refreshResult.catch(function (err) {
                                    // Feed refresh failure handled by feed error UI, never composer
                                    console.error('Post-success feed refresh error:', err);
                                });
                            }
                        } else {
                            const win = (typeof window !== 'undefined') ? window : ((typeof globalThis !== 'undefined') ? globalThis : null);
                            if (win && win.location) {
                                win.location.href = '/community?feed=NEWEST';
                            }
                        }
                    } catch (postSuccessError) {
                        // Presentation/feed error must NEVER invalidate post creation
                        console.error('Post-success presentation error:', postSuccessError);
                    }
                })
                .catch(function (error) {
                    if (error && (error.name === 'TypeError' || error.message === 'Failed to fetch' || (error.message && error.message.toLowerCase().includes('network')))) {
                        showError('Không thể kết nối đến máy chủ. Vui lòng kiểm tra mạng.');
                    } else {
                        showError((error && error.message) ? error.message : 'Không thể tạo bài viết.');
                    }
                })
                .finally(function () {
                    setSubmitting(false);
                });
        });

        function setSubmitting(loading) {
            isSubmitting = loading;
            if (submitBtn) {
                submitBtn.disabled = loading;
            }
            if (submitSpinner) {
                if (loading) {
                    submitSpinner.removeAttribute('hidden');
                } else {
                    submitSpinner.setAttribute('hidden', '');
                }
            }
            if (captionInput) {
                captionInput.disabled = loading;
            }
            if (addImageBtn) {
                addImageBtn.disabled = loading;
            }
        }

        function showError(msg) {
            if (errorAlert) {
                errorAlert.textContent = msg;
                errorAlert.removeAttribute('hidden');
            }
        }

        function hideError() {
            if (errorAlert) {
                errorAlert.textContent = '';
                errorAlert.setAttribute('hidden', '');
            }
        }

        function showSuccess(msg) {
            if (successAlert) {
                successAlert.textContent = msg;
                successAlert.removeAttribute('hidden');
            }
        }

        function hideSuccess() {
            if (successAlert) {
                successAlert.textContent = '';
                successAlert.setAttribute('hidden', '');
            }
        }

        function clearImageInput() {
            if (imageInput) {
                imageInput.value = '';
                try {
                    if (Array.isArray(imageInput.files)) {
                        imageInput.files = [];
                    }
                } catch (_) {}
            }
        }

        function clearImagePreview() {
            if (imagePreviewContainer) {
                imagePreviewContainer.setAttribute('hidden', '');
            }
            if (imagePreviewImg) {
                imagePreviewImg.src = '';
            }
        }

        function renderOwnPendingPost(createdPost, targetDoc) {
            const d = targetDoc || doc;
            if (!createdPost || !d) return;

            let pendingSection = d.getElementById('communityOwnPendingSection');
            let pendingList = d.getElementById('communityOwnPendingList');
            let pendingTrigger = d.getElementById('communityOwnPendingTrigger');

            if (!pendingSection || !pendingList) {
                pendingSection = d.createElement('section');
                pendingSection.id = 'communityOwnPendingSection';
                pendingSection.className = 'community-own-pending-section mb-4';
                pendingSection.setAttribute('aria-label', 'Bài viết đang chờ duyệt');

                const noscript = d.createElement('noscript');
                noscript.innerHTML = '<style>#communityOwnPendingTrigger { display: none !important; } #communityOwnPendingList { display: flex !important; }</style>';
                pendingSection.appendChild(noscript);

                pendingTrigger = d.createElement('button');
                pendingTrigger.type = 'button';
                pendingTrigger.id = 'communityOwnPendingTrigger';
                pendingTrigger.className = 'community-pending-tray-trigger';
                pendingTrigger.setAttribute('aria-expanded', 'false');
                pendingTrigger.setAttribute('aria-controls', 'communityOwnPendingList');

                const leftDiv = d.createElement('div');
                leftDiv.className = 'pending-tray-left';
                const icon = d.createElement('i');
                icon.className = 'fa-solid fa-clock-rotate-left pending-tray-icon';
                icon.setAttribute('aria-hidden', 'true');
                const title = d.createElement('span');
                title.className = 'pending-tray-title';
                title.textContent = 'Bài viết đang chờ duyệt';
                leftDiv.appendChild(icon);
                leftDiv.appendChild(title);

                const rightDiv = d.createElement('div');
                rightDiv.className = 'pending-tray-right';
                const countBadge = d.createElement('span');
                countBadge.id = 'communityOwnPendingCount';
                countBadge.className = 'pending-tray-count-badge';
                countBadge.textContent = '1';
                countBadge.setAttribute('aria-label', '1 bài viết đang chờ duyệt');
                const chevron = d.createElement('i');
                chevron.className = 'fa-solid fa-chevron-right pending-tray-chevron';
                chevron.setAttribute('aria-hidden', 'true');
                rightDiv.appendChild(countBadge);
                rightDiv.appendChild(chevron);

                pendingTrigger.appendChild(leftDiv);
                pendingTrigger.appendChild(rightDiv);
                pendingSection.appendChild(pendingTrigger);

                pendingList = d.createElement('div');
                pendingList.id = 'communityOwnPendingList';
                pendingList.className = 'community-pending-list d-flex flex-column gap-3 mt-3';
                pendingList.setAttribute('hidden', '');
                pendingSection.appendChild(pendingList);

                setupPendingTray(pendingTrigger, pendingList);

                const sortDropdown = d.getElementById('communityFeedSortDropdown');
                const feedContainer = d.querySelector ? d.querySelector('.community-feed-container') : null;
                const ref = sortDropdown || feedContainer;
                const parent = (ref && ref.parentNode) || d.body;
                if (parent) {
                    if (typeof parent.insertBefore === 'function' && ref) {
                        parent.insertBefore(pendingSection, ref);
                    } else if (typeof parent.appendChild === 'function') {
                        parent.appendChild(pendingSection);
                    }
                }
            } else if (pendingTrigger && !pendingTrigger.__pendingTrayBound) {
                setupPendingTray(pendingTrigger, pendingList);
            }

            const composerAvatarImg = d.querySelector('.composer-avatar');
            const composerNameEl = d.querySelector('.composer-author-name');
            const avatarSrc = (composerAvatarImg && composerAvatarImg.getAttribute('src')) || '/images/default_avatar.jpg';
            const authorName = (composerNameEl && composerNameEl.textContent && composerNameEl.textContent.trim()) || 'Người dùng';

            // Check if existing card for this postId is already in pendingList
            const existingCard = createdPost.id
                ? pendingList.querySelector('.community-post-card--pending[data-post-id="' + createdPost.id + '"]')
                : null;

            const card = existingCard || d.createElement('article');
            card.className = 'community-post-card community-post-card--pending';
            if (createdPost.id) {
                card.setAttribute('data-post-id', String(createdPost.id));
            }
            if (createdPost.authorUserId) {
                card.setAttribute('data-author-id', String(createdPost.authorUserId));
            }
            if (existingCard) {
                while (card.firstChild) {
                    card.removeChild(card.firstChild);
                }
            }

            const header = d.createElement('header');
            header.className = 'post-header';

            const authorInfo = d.createElement('div');
            authorInfo.className = 'post-author-info';

            const avatar = d.createElement('img');
            avatar.src = avatarSrc;
            avatar.alt = 'Ảnh đại diện';
            avatar.className = 'post-author-avatar';
            authorInfo.appendChild(avatar);

            const meta = d.createElement('div');
            meta.className = 'post-meta';

            const authorRow = d.createElement('div');
            authorRow.className = 'post-author-row';
            const nameSpan = d.createElement('span');
            nameSpan.className = 'post-author-name';
            nameSpan.textContent = authorName;
            authorRow.appendChild(nameSpan);
            meta.appendChild(authorRow);

            const timeEl = d.createElement('time');
            timeEl.className = 'post-time';
            timeEl.setAttribute('data-relative-time', '');
            const timeVal = createdPost.reviewRequestedAt || createdPost.createdAt || new Date().toISOString();
            timeEl.setAttribute('datetime', timeVal);
            timeEl.textContent = 'Vừa xong';
            meta.appendChild(timeEl);
            authorInfo.appendChild(meta);
            header.appendChild(authorInfo);
            card.appendChild(header);

            const captionP = d.createElement('p');
            captionP.className = 'post-caption';
            captionP.textContent = createdPost.pendingCaption || createdPost.caption || '';
            card.appendChild(captionP);

            const imageUrl = createdPost.imageUrl || (createdPost.imageMediaAssetId ? ('/media/assets/' + encodeURIComponent(createdPost.imageMediaAssetId) + '/content') : null);
            if (imageUrl) {
                const imgContainer = d.createElement('div');
                imgContainer.className = 'post-image-container';
                const img = d.createElement('img');
                img.src = imageUrl;
                img.alt = 'Ảnh đính kèm';
                img.className = 'post-image';
                img.loading = 'lazy';
                imgContainer.appendChild(img);
                card.appendChild(imgContainer);
            }

            const footer = d.createElement('footer');
            footer.className = 'post-footer';
            const statusDiv = d.createElement('div');
            statusDiv.className = 'post-pending-status';
            const isEditPending = Boolean(createdPost.pendingCaption);
            const isReReview = Boolean(createdPost.publishedAt);
            if (isEditPending) {
                statusDiv.textContent = '⏳ Đang chờ duyệt chỉnh sửa';
            } else if (isReReview) {
                statusDiv.textContent = '⏳ Đang chờ duyệt lại';
            } else {
                statusDiv.textContent = '⏳ Đang chờ duyệt';
            }
            footer.appendChild(statusDiv);
            card.appendChild(footer);

            if (!existingCard) {
                if (typeof pendingList.insertBefore === 'function' && pendingList.firstChild) {
                    pendingList.insertBefore(card, pendingList.firstChild);
                } else if (typeof pendingList.appendChild === 'function') {
                    pendingList.appendChild(card);
                }
            }

            // Update count badge
            const countBadge = d.getElementById('communityOwnPendingCount');
            if (countBadge) {
                const count = pendingList.querySelectorAll('.community-post-card--pending').length;
                countBadge.textContent = String(count);
                countBadge.setAttribute('aria-label', count + ' bài viết đang chờ duyệt');
            }
        }

        function resetComposer() {
            previewGeneration++;
            if (captionInput) {
                captionInput.value = '';
                captionInput.disabled = false;
            }
            if (charCountEl) {
                charCountEl.textContent = '0/' + MAX_CAPTION_LENGTH;
                charCountEl.classList.remove('text-danger');
            }
            clearImageInput();
            clearImagePreview();
            hideError();
        }

        return {
            resetComposer: resetComposer,
            setSubmitting: setSubmitting,
            expandComposer: expandComposer,
            collapseComposer: collapseComposer,
            isExpanded: function () {
                if (composerTrigger) {
                    return composerTrigger.getAttribute('aria-expanded') === 'true';
                }
                return composerPanel ? !composerPanel.hasAttribute('hidden') : true;
            },
            togglePendingTray: function (expand) {
                const trigger = doc.getElementById('communityOwnPendingTrigger');
                const list = doc.getElementById('communityOwnPendingList');
                if (trigger && list) {
                    const current = trigger.getAttribute('aria-expanded') === 'true';
                    const target = typeof expand === 'boolean' ? expand : !current;
                    trigger.setAttribute('aria-expanded', target ? 'true' : 'false');
                    if (target) {
                        list.removeAttribute('hidden');
                    } else {
                        list.setAttribute('hidden', '');
                    }
                }
            },
            isPendingTrayExpanded: function () {
                const trigger = doc.getElementById('communityOwnPendingTrigger');
                return trigger ? trigger.getAttribute('aria-expanded') === 'true' : false;
            },
            getPendingCount: function () {
                const badge = doc.getElementById('communityOwnPendingCount');
                if (badge && badge.textContent) {
                    const parsed = parseInt(badge.textContent.trim(), 10);
                    return isNaN(parsed) ? 0 : parsed;
                }
                const list = doc.getElementById('communityOwnPendingList');
                return list ? list.querySelectorAll('.community-post-card--pending').length : 0;
            }
        };
    }

    if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
        document.addEventListener('DOMContentLoaded', function () {
            initComposer();
        });
    }

    return {
        init: initComposer,
        initComposer: initComposer,
        parseComposerResponse: parseComposerResponse,
        renderOwnPendingPost: function (post, customDoc) {
            return renderOwnPendingPost(post, customDoc || activeDoc);
        },
        setupPendingTray: setupPendingTray,
        getPreviewGeneration: function () { return previewGeneration; }
    };
});
