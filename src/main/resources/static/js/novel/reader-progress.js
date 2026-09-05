/**
 * KiemLai Universe — Novel Reader Progress Tracker
 *
 * Tự động ghi nhận tiến độ đọc khi người dùng đã đăng nhập mở trang đọc chương.
 * Hỗ trợ cập nhật khi chuyển chương liền mạch qua sự kiện 'kiemlai:chapter-changed'.
 * Người dùng ẩn danh không gửi yêu cầu.
 * Lỗi ghi nhận không làm gián đoạn trải nghiệm đọc truyện.
 */
(function () {
    'use strict';

    function recordReadingProgress() {
        const tracker = document.getElementById("novelReadingProgressTracker");
        if (!tracker) {
            return;
        }

        const chapterId = tracker.dataset.chapterId;
        const csrfToken = tracker.dataset.csrfToken;
        const csrfHeader = tracker.dataset.csrfHeader;

        if (!chapterId) {
            return;
        }

        const headers = {
            "Content-Type": "application/json"
        };

        if (csrfToken && csrfHeader) {
            headers[csrfHeader] = csrfToken;
        }

        fetch("/novel/chapters/" + encodeURIComponent(chapterId) + "/progress", {
            method: "POST",
            headers: headers
        }).catch(function (error) {
            console.debug("Không thể ghi nhận tiến độ đọc:", error);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", recordReadingProgress);
    } else {
        recordReadingProgress();
    }

    document.addEventListener("kiemlai:chapter-changed", recordReadingProgress);
})();
