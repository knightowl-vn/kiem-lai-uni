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
}
