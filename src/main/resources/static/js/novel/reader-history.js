/**
 * KiemLai Universe — Novel Reader History Tracker
 *
 * Tự động ghi nhận lịch sử đọc chương khi người dùng đã đăng nhập mở trang đọc chương.
 * Hỗ trợ cập nhật khi chuyển chương liền mạch qua sự kiện 'kiemlai:chapter-changed'.
 * Tuần tự hóa các request ghi nhận theo thứ tự sự kiện (Promise queue serialization).
 * Chụp snapshot bất biến của thông tin chương ngay khi sự kiện phát sinh.
 * Người dùng ẩn danh không gửi yêu cầu.
 * Lỗi ghi nhận (mạng, timeout, conflict) hoàn toàn im lặng, không làm gián đoạn trải nghiệm đọc truyện.
 */
(function () {
    'use strict';

    let writeQueue = Promise.resolve();

    function recordReadingHistory() {
        const tracker = document.getElementById("novelReadingHistoryTracker");
        if (!tracker) {
            return;
        }

        const chapterId = tracker.dataset.chapterId;
        const historyUrl = tracker.dataset.historyUrl || ("/novel/chapters/" + encodeURIComponent(chapterId) + "/history");
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

        writeQueue = writeQueue.then(function () {
            return fetch(historyUrl, {
                method: "POST",
                headers: headers
            });
        }).catch(function (error) {
            console.debug("Không thể ghi nhận lịch sử đọc:", error);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", recordReadingHistory);
    } else {
        recordReadingHistory();
    }

    document.addEventListener("kiemlai:chapter-changed", recordReadingHistory);
})();
