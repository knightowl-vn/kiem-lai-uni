package com.universe.wiki.application.ports;

import com.universe.wiki.domain.contribution.WikiContribution;

import java.util.Optional;
import java.util.UUID;

/**
 * Cổng truy xuất và lưu trữ cho Aggregate Root WikiContribution.
 */
public interface WikiContributionRepositoryPort {

    /**
     * Lưu thông tin đóng góp (tạo mới hoặc cập nhật).
     *
     * @param contribution đối tượng domain cần lưu
     * @return đối tượng domain sau khi lưu (bao gồm version và timestamps cập nhật từ persistence)
     */
    WikiContribution save(WikiContribution contribution);

    /**
     * Tìm kiếm đóng góp theo định danh duy nhất.
     *
     * @param id định danh đóng góp
     * @return Optional chứa WikiContribution nếu tìm thấy, ngược lại rỗng
     */
    Optional<WikiContribution> findById(UUID id);

    /**
     * Tìm kiếm các đóng góp gần đây của một người dùng đối với một bài viết cụ thể từ thời điểm cutoff trở lại đây.
     * Kết quả được sắp xếp giảm dần theo thời gian tạo (mới nhất lên đầu).
     *
     * @param submittedByUserId định danh người dùng gửi đóng góp
     * @param articleId định danh bài viết
     * @param cutoff mốc thời gian bắt đầu xét (ví dụ: now - 60s)
     * @param limit số lượng bản ghi tối đa
     * @return danh sách các đóng góp thỏa mãn điều kiện
     */
    java.util.List<WikiContribution> findRecentCandidates(
            UUID submittedByUserId,
            UUID articleId,
            java.time.Instant cutoff,
            int limit
    );
}
