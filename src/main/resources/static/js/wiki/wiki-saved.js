/**
 * Kiem Lai Wiki — Saved Articles & Saved List Script
 *
 * Handles toggle save on Wiki article detail page (POST / DELETE)
 * and removal of saved articles on the saved list page.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        initWikiDetailSaveToggle();
        initWikiSavedListRemoval();
    });

    /**
     * Validates whether a redirect URL is an allowed same-origin security destination.
     * Navigation is permitted only when:
     * 1. URL parses safely relative to the current page.
     * 2. Origin strictly matches the current application origin.
     * 3. Pathname is exactly '/login' or '/access-denied' (query strings/fragments allowed).
     *
     * Returns the validated URL string if allowed, or null if rejected.
     */
    function getValidSecurityRedirectUrl(targetUrl) {
        if (!targetUrl || typeof targetUrl !== 'string') {
            return null;
        }

        try {
            const currentOrigin = (window.location && window.location.origin)
                ? window.location.origin
                : new URL(window.location.href).origin;

            const parsed = new URL(targetUrl, window.location.href);

            if (parsed.origin !== currentOrigin) {
                return null;
            }

            if (parsed.pathname === '/login' || parsed.pathname === '/access-denied') {
                return parsed.href;
            }

            return null;
        } catch (e) {
            return null;
        }
    }

    /**
     * Detail Page: Toggle Save / Unsave
     */
    function initWikiDetailSaveToggle() {
        const saveBtn = document.getElementById('wikiSaveArticleBtn');
        if (!saveBtn) {
            return;
        }

        saveBtn.addEventListener('click', async function (e) {
            e.preventDefault();

            const isAuthenticated = saveBtn.getAttribute('data-authenticated') === 'true';
            const loginUrl = saveBtn.getAttribute('data-login-url') || '/login';

            if (!isAuthenticated) {
                window.location.href = loginUrl;
                return;
            }

            const articleId = saveBtn.getAttribute('data-article-id');
            const saveUrl = saveBtn.getAttribute('data-save-url');
            const isSaved = saveBtn.getAttribute('data-saved') === 'true';
            const csrfToken = saveBtn.getAttribute('data-csrf-token');
            const csrfHeader = saveBtn.getAttribute('data-csrf-header');

            if (!articleId || !saveUrl) {
                return;
            }

            saveBtn.disabled = true;

            try {
                const method = isSaved ? 'DELETE' : 'POST';
                const headers = {
                    'Content-Type': 'application/json'
                };
                if (csrfToken && csrfHeader) {
                    headers[csrfHeader] = csrfToken;
                }

                const response = await fetch(saveUrl, {
                    method: method,
                    headers: headers
                });

                if (response.redirected) {
                    const validRedirectUrl = getValidSecurityRedirectUrl(response.url);
                    if (validRedirectUrl) {
                        window.location.href = validRedirectUrl;
                    } else {
                        showWikiToast('Có lỗi xảy ra khi lưu bài viết. Vui lòng thử lại sau.');
                    }
                    return;
                }

                if (response.status === 204) {
                    const newSavedState = !isSaved;
                    saveBtn.setAttribute('data-saved', String(newSavedState));

                    const desktopSpan = saveBtn.querySelector('.wiki-save-label-desktop, .wiki-save-btn-text');
                    const mobileSpan = saveBtn.querySelector('.wiki-save-label-mobile, .wiki-save-btn-text-mobile');

                    if (newSavedState) {
                        saveBtn.classList.add('is-saved');
                        saveBtn.setAttribute('aria-label', 'Bỏ lưu bài viết');
                        saveBtn.setAttribute('title', 'Bỏ lưu bài viết');
                        if (desktopSpan) {
                            desktopSpan.textContent = 'Đã lưu';
                        }
                        if (mobileSpan) {
                            mobileSpan.textContent = 'Đã lưu';
                        }
                    } else {
                        saveBtn.classList.remove('is-saved');
                        saveBtn.setAttribute('aria-label', 'Lưu bài viết');
                        saveBtn.setAttribute('title', 'Lưu bài viết');
                        if (desktopSpan) {
                            desktopSpan.textContent = 'Lưu bài viết';
                        }
                        if (mobileSpan) {
                            mobileSpan.textContent = 'Lưu';
                        }
                    }
                } else if (response.status === 401) {
                    window.location.href = loginUrl;
                } else if (response.status === 404) {
                    showWikiToast('Bài viết không còn khả dụng để lưu.');
                } else {
                    showWikiToast('Có lỗi xảy ra khi lưu bài viết. Vui lòng thử lại sau.');
                }
            } catch (err) {
                console.error('Lỗi khi cập nhật trạng thái lưu bài viết Wiki:', err);
                showWikiToast('Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại kết nối mạng.');
            } finally {
                saveBtn.disabled = false;
            }
        });
    }

    /**
     * Saved List Page: Remove / Unsave Article
     */
    function initWikiSavedListRemoval() {
        const removeButtons = document.querySelectorAll('.js-wiki-unsave-btn');
        if (!removeButtons || removeButtons.length === 0) {
            return;
        }

        removeButtons.forEach(function (btn) {
            btn.addEventListener('click', async function (e) {
                e.preventDefault();

                const unsaveUrl = btn.getAttribute('data-unsave-url');
                const csrfToken = btn.getAttribute('data-csrf-token');
                const csrfHeader = btn.getAttribute('data-csrf-header');
                const itemCard = btn.closest('.wiki-saved-item');

                if (!unsaveUrl) {
                    return;
                }

                btn.disabled = true;

                try {
                    const headers = {};
                    if (csrfToken && csrfHeader) {
                        headers[csrfHeader] = csrfToken;
                    }

                    const response = await fetch(unsaveUrl, {
                        method: 'DELETE',
                        headers: headers
                    });

                    if (response.redirected) {
                        const validRedirectUrl = getValidSecurityRedirectUrl(response.url);
                        if (validRedirectUrl) {
                            window.location.href = validRedirectUrl;
                        } else {
                            showWikiToast('Không thể bỏ lưu bài viết. Vui lòng thử lại sau.');
                            btn.disabled = false;
                        }
                        return;
                    }

                    if (response.status === 204) {
                        if (itemCard) {
                            itemCard.remove();
                        }
                        const remaining = document.querySelectorAll('.wiki-saved-item');
                        if (remaining.length === 0) {
                            const listContainer = document.getElementById('wikiSavedList');
                            const currentPage = listContainer
                                ? parseInt(listContainer.getAttribute('data-current-page') || '0', 10)
                                : 0;
                            if (currentPage > 0) {
                                window.location.href = '/wiki/saved?page=' + (currentPage - 1);
                            } else {
                                window.location.reload();
                            }
                        }
                    } else if (response.status === 401) {
                        window.location.href = '/login';
                    } else {
                        showWikiToast('Không thể bỏ lưu bài viết. Vui lòng thử lại sau.');
                        btn.disabled = false;
                    }
                } catch (err) {
                    console.error('Lỗi khi bỏ lưu bài viết Wiki:', err);
                    showWikiToast('Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại kết nối mạng.');
                    btn.disabled = false;
                }
            });
        });
    }

    /**
     * Generic Toast Notification
     */
    function showWikiToast(message) {
        const existingToast = document.getElementById('wikiSavedToast');
        if (existingToast) {
            existingToast.remove();
        }

        const toast = document.createElement('div');
        toast.id = 'wikiSavedToast';
        toast.setAttribute('role', 'alert');
        toast.setAttribute('aria-live', 'polite');
        toast.style.cssText = [
            'position: fixed',
            'bottom: 24px',
            'right: 24px',
            'max-width: 360px',
            'padding: 12px 16px',
            'background-color: var(--surface-bg, #1e293b)',
            'color: var(--text-primary, #f8fafc)',
            'border: 1px solid var(--border-color, #334155)',
            'font-size: 14px',
            'line-height: 1.5',
            'border-radius: 8px',
            'box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.3), 0 4px 6px -4px rgba(0, 0, 0, 0.2)',
            'z-index: 9999',
            'display: flex',
            'align-items: center',
            'justify-content: space-between',
            'gap: 12px',
            'transition: opacity 0.3s ease, transform 0.3s ease',
            'opacity: 0',
            'transform: translateY(10px)'
        ].join(';');

        const textSpan = document.createElement('span');
        textSpan.textContent = message;
        toast.appendChild(textSpan);

        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.setAttribute('aria-label', 'Đóng');
        closeBtn.innerHTML = '&times;';
        closeBtn.style.cssText = [
            'background: transparent',
            'border: none',
            'color: var(--text-secondary, #94a3b8)',
            'font-size: 18px',
            'line-height: 1',
            'cursor: pointer',
            'padding: 0 4px'
        ].join(';');
        closeBtn.addEventListener('click', function () {
            toast.style.opacity = '0';
            toast.style.transform = 'translateY(10px)';
            setTimeout(function () {
                if (toast.parentNode) {
                    toast.remove();
                }
            }, 300);
        });
        toast.appendChild(closeBtn);

        document.body.appendChild(toast);

        requestAnimationFrame(function () {
            toast.style.opacity = '1';
            toast.style.transform = 'translateY(0)';
        });

        setTimeout(function () {
            if (toast.parentNode) {
                toast.style.opacity = '0';
                toast.style.transform = 'translateY(10px)';
                setTimeout(function () {
                    if (toast.parentNode) {
                        toast.remove();
                    }
                }, 300);
            }
        }, 5000);
    }
})();
