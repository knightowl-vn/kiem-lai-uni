package com.universe.wiki.domain.contribution;

/**
 * Kết quả xử lý cụ thể của một đóng góp Wiki đã được giải quyết (RESOLVED).
 */
public enum WikiContributionResolutionOutcome {

    /**
     * Đóng góp đã được tiếp thu và cập nhật nội dung vào bài viết Wiki (kèm bằng chứng revision).
     */
    APPLIED,

    /**
     * Đóng góp đã được xem xét nhưng nội dung bài viết hiện tại đã đầy đủ/chính xác, không cần sửa đổi.
     */
    NO_CHANGE_NEEDED,

    /**
     * Đóng góp trùng lặp với nội dung hoặc đóng góp khác đã được xử lý trước đó.
     */
    DUPLICATE
}
