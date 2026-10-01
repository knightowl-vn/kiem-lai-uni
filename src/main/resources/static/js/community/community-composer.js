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
(function () {
    'use strict';

    const MAX_CAPTION_LENGTH = 2000;
    const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

    document.addEventListener('DOMContentLoaded', initComposer);

    function initComposer() {
        const form = document.getElementById('communityComposerForm');
        if (!form) {
            return; // Guest user or composer not present
        }

        const captionInput = document.getElementById('composerCaption');
        const charCountEl = document.getElementById('composerCharCount');
        const imageInput = document.getElementById('composerImageInput');
        const addImageBtn = document.getElementById('composerAddImageBtn');
        const imagePreviewContainer = document.getElementById('composerImagePreview');
        const imagePreviewImg = document.getElementById('composerImagePreviewImg');
        const removeImageBtn = document.getElementById('composerRemoveImageBtn');
        const submitBtn = document.getElementById('composerSubmitBtn');
        const submitSpinner = document.getElementById('composerSubmitSpinner');
        const errorAlert = document.getElementById('composerError');

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

        // Image selection & preview
        if (imageInput) {
            imageInput.addEventListener('change', function () {
                hideError();
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
                clearImageInput();
                clearImagePreview();
            });
        }

        // Form submission
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (isSubmitting) {
                return;
            }

            hideError();

            const caption = captionInput ? captionInput.value.trim() : '';
            if (!caption) {
                showError('Vui lòng nhập nội dung bài viết.');
                if (captionInput) {
                    captionInput.focus();
                }
                return;
            }

            if (caption.length > MAX_CAPTION_LENGTH) {
                showError('Nội dung bài viết vượt quá 2.000 ký tự.');
                return;
            }

            const formData = new FormData();
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
            const csrfTokenMeta = document.querySelector('meta[name="_csrf"]');
            const csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
            if (csrfTokenMeta && csrfHeaderMeta) {
                const headerName = csrfHeaderMeta.getAttribute('content');
                const token = csrfTokenMeta.getAttribute('content');
                if (headerName && token) {
                    headers[headerName] = token;
                }
            }

            fetch('/api/community/posts', {
                method: 'POST',
                headers: headers,
                body: formData
            })
                .then(function (response) {
                    if (response.status === 201) {
                        return response.json();
                    }
                    if (response.status === 401) {
                        throw new Error('Vui lòng đăng nhập để đăng bài.');
                    }
                    if (response.status === 400 || response.status === 422) {
                        return response.json().then(function (data) {
                            throw new Error(data.message || 'Dữ liệu không hợp lệ.');
                        }).catch(function (err) {
                            throw new Error(err.message || 'Dữ liệu không hợp lệ.');
                        });
                    }
                    throw new Error('Đã xảy ra lỗi khi đăng bài. Vui lòng thử lại.');
                })
                .then(function (createdPost) {
                    // Reset composer
                    resetComposer();

                    // Refresh feed on NEWEST tab
                    if (window.CommunityFeed && typeof window.CommunityFeed.refreshFeed === 'function') {
                        window.CommunityFeed.refreshFeed('NEWEST');
                    } else {
                        window.location.href = '/community?feed=NEWEST';
                    }
                })
                .catch(function (error) {
                    showError(error.message || 'Không thể tạo bài viết.');
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
    }
})();
