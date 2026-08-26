/**
 * KiemLai Universe — Novel Reader History Tracker
 *
 * Tự động ghi nhận lịch sử đọc chương khi người dùng đã đăng nhập mở trang đọc chương.
 * Người dùng ẩn danh không gửi yêu cầu.
 * Lỗi ghi nhận (mạng, timeout, conflict) hoàn toàn im lặng, không làm gián đoạn trải nghiệm đọc truyện.
 */
document.addEventListener("DOMContentLoaded", function () {
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

    fetch(historyUrl, {
        method: "POST",
        headers: headers
    }).catch(function (error) {
        console.debug("Không thể ghi nhận lịch sử đọc:", error);
    });
});
