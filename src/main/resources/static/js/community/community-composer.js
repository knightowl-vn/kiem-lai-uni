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

    function initComposer(options) {
        const opts = options || {};
        const doc = opts.document || (typeof document !== 'undefined' ? document : null);
        if (!doc) return;

        if (opts.fetch) {
            injectedFetch = opts.fetch;
        }

        const form = doc.getElementById('communityComposerForm');
        if (!form) {
            return; // Guest user or composer not present
        }

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

        let isSubmitting = false;

        // Caption character count
        if (captionInput && charCountEl) {
            captionInput.addEventListener('input', function () {
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
            setSubmitting: setSubmitting
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
        getPreviewGeneration: function () { return previewGeneration; }
    };
});
