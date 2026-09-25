package com.universe.wiki.application.ports;

import com.universe.wiki.domain.credit.WikiContributionCredit;

import java.util.Optional;
import java.util.UUID;

/**
 * Cổng giao tiếp lưu trữ và truy xuất bản ghi ghi nhận công trạng người đóng góp (Wiki Contribution Credit).
 */
public interface WikiContributionCreditRepositoryPort {

    /**
     * Tìm kiếm bản ghi ghi nhận công trạng theo ID đóng góp.
     *
     * @param contributionId ID đóng góp Wiki
     * @return Optional chứa bản ghi nếu tìm thấy
     */
    Optional<WikiContributionCredit> findByContributionId(UUID contributionId);

    /**
     * Lưu bản ghi ghi nhận công trạng (tạo mới hoặc cập nhật trạng thái thu hồi).
     *
     * Ràng buộc duy nhất uq_wiki_contribution_credits_contribution trên database
     * là thẩm quyền tối hậu để bảo vệ chống trùng lặp ghi nhận công trạng khi xảy ra tranh chấp (race condition).
     *
     * @param credit Aggregate công trạng cần lưu
     * @return Aggregate sau khi đã được lưu thành công
     */
    WikiContributionCredit save(WikiContributionCredit credit);
}
