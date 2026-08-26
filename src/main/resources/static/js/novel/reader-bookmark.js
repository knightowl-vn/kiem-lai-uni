/**
 * Kiem Lai Novel Reader — Chapter Bookmark & Bookmarks List Script
 *
 * Handles toggle bookmark on chapter reader page (POST / DELETE)
 * and removal of bookmarks on the bookmarks list page.
 */
(function () {
    'use strict';

    document.addEventListener('DOMContentLoaded', function () {
        initChapterBookmarkToggle();
        initBookmarkListRemoval();
    });

    function initChapterBookmarkToggle() {
        const bookmarkBtn = document.getElementById('novelChapterBookmarkBtn');
        if (!bookmarkBtn) {
            return;
        }

        bookmarkBtn.addEventListener('click', async function (e) {
            e.preventDefault();

            const chapterId = bookmarkBtn.getAttribute('data-chapter-id');
            const bookmarkUrl = bookmarkBtn.getAttribute('data-bookmark-url');
            const isBookmarked = bookmarkBtn.getAttribute('data-bookmarked') === 'true';
            const csrfToken = bookmarkBtn.getAttribute('data-csrf-token');
            const csrfHeader = bookmarkBtn.getAttribute('data-csrf-header');

            if (!chapterId || !bookmarkUrl) {
                return;
            }

            bookmarkBtn.disabled = true;

            try {
                const method = isBookmarked ? 'DELETE' : 'POST';
                const headers = {
                    'Content-Type': 'application/json'
                };
                if (csrfToken && csrfHeader) {
                    headers[csrfHeader] = csrfToken;
                }

                const response = await fetch(bookmarkUrl, {
                    method: method,
                    headers: headers
                });

                if (response.ok || response.status === 204) {
                    const newBookmarkedState = !isBookmarked;
                    bookmarkBtn.setAttribute('data-bookmarked', String(newBookmarkedState));
                    if (newBookmarkedState) {
                        bookmarkBtn.classList.add('is-bookmarked');
                    } else {
                        bookmarkBtn.classList.remove('is-bookmarked');
                    }

                    const textSpan = bookmarkBtn.querySelector('.novel-bookmark-btn-text');
                    if (textSpan) {
                        textSpan.textContent = newBookmarkedState ? 'Đã đánh dấu' : 'Đánh dấu';
                    }
                } else if (response.status === 409) {
                    showBookmarkLimitToast('Bạn đã đạt giới hạn 100 chương đã đánh dấu. Hãy bỏ một dấu trang trước khi thêm chương mới.');
                } else if (response.status === 401) {
                    window.location.href = '/login';
                }
            } catch (err) {
                console.error('Lỗi khi cập nhật dấu trang:', err);
            } finally {
                bookmarkBtn.disabled = false;
            }
        });
    }

    function showBookmarkLimitToast(message) {
        const existingToast = document.getElementById('novelBookmarkLimitToast');
        if (existingToast) {
            existingToast.remove();
        }

        const toast = document.createElement('div');
        toast.id = 'novelBookmarkLimitToast';
        toast.setAttribute('role', 'alert');
        toast.setAttribute('aria-live', 'polite');
        toast.style.cssText = [
            'position: fixed',
            'bottom: 24px',
            'right: 24px',
            'max-width: 360px',
            'padding: 12px 16px',
            'background-color: #1e293b',
            'color: #f8fafc',
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
            'color: #94a3b8',
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

    function initBookmarkListRemoval() {
        const removeButtons = document.querySelectorAll('.js-novel-bookmark-remove-btn');
        if (!removeButtons || removeButtons.length === 0) {
            return;
        }

        removeButtons.forEach(function (btn) {
            btn.addEventListener('click', async function (e) {
                e.preventDefault();

                const unbookmarkUrl = btn.getAttribute('data-unbookmark-url');
                const csrfToken = btn.getAttribute('data-csrf-token');
                const csrfHeader = btn.getAttribute('data-csrf-header');
                const rowOrCard = btn.closest('.novel-bookmark-item');

                if (!unbookmarkUrl) {
                    return;
                }

                btn.disabled = true;

                try {
                    const headers = {};
                    if (csrfToken && csrfHeader) {
                        headers[csrfHeader] = csrfToken;
                    }

                    const response = await fetch(unbookmarkUrl, {
                        method: 'DELETE',
                        headers: headers
                    });

                    if (response.ok || response.status === 204) {
                        if (rowOrCard) {
                            rowOrCard.remove();
                        }
                        const remaining = document.querySelectorAll('.novel-bookmark-item');
                        if (remaining.length === 0) {
                            const listContainer = document.getElementById('novelBookmarksList');
                            const emptyContainer = document.getElementById('novelBookmarksEmpty');
                            if (listContainer) listContainer.classList.add('d-none');
                            if (emptyContainer) emptyContainer.classList.remove('d-none');
                        }
                    }
                } catch (err) {
                    console.error('Lỗi khi xóa dấu trang:', err);
                    btn.disabled = false;
                }
            });
        });
    }
})();
