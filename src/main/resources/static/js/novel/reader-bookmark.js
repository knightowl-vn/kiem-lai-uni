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
