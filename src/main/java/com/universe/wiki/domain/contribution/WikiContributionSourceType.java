package com.universe.wiki.domain.contribution;

/**
 * Phân loại nguồn tham khảo / bằng chứng đi kèm đóng góp bài viết Wiki.
 */
public enum WikiContributionSourceType {

    /**
     * Đường dẫn nội bộ thuộc KiemLai Universe (Wiki hoặc Novel) tuân thủ chính sách đường dẫn an toàn.
     */
    INTERNAL,

    /**
     * Đường dẫn HTTP(S) tuyệt đối trỏ đến trang web bên ngoài.
     */
    EXTERNAL
}
