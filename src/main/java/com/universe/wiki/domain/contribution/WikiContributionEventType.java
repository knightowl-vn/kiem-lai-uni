package com.universe.wiki.domain.contribution;

/**
 * Các loại sự kiện nhật ký quy trình xử lý đóng góp (Workflow Audit Event Types).
 */
public enum WikiContributionEventType {

    /**
     * Bắt đầu xem xét và tiếp nhận đóng góp (NEW -> REVIEWING).
     */
    REVIEW_STARTED,

    /**
     * Tiếp nhận xử lý đóng góp từ trạng thái REVIEWING chưa phân công (Legacy claim).
     */
    CLAIMED,

    /**
     * Super Admin bàn giao đóng góp sang cho người phụ trách mới.
     */
    REASSIGNED,

    /**
     * Bản cập nhật bài viết Wiki được lưu có liên kết với đóng góp này.
     */
    ARTICLE_UPDATE_LINKED,

    /**
     * Hoàn tất giải quyết đóng góp (REVIEWING -> RESOLVED).
     */
    RESOLVED,

    /**
     * Từ chối đóng góp (REVIEWING -> REJECTED).
     */
    REJECTED
}
