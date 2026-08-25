/**
 * KiemLai Universe — Novel Reader Progress Tracker
 *
 * Tự động ghi nhận tiến độ đọc khi người dùng đã đăng nhập mở trang đọc chương.
 * Người dùng ẩn danh không gửi yêu cầu.
 * Lỗi ghi nhận không làm gián đoạn trải nghiệm đọc truyện.
 */
document.addEventListener("DOMContentLoaded", function () {
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
});
