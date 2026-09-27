package com.universe.wiki.application.ports;

import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;

import java.util.UUID;

/**
 * Port truy vấn danh sách đóng góp Wiki của người dùng đã xác thực.
 */
public interface UserWikiContributionsQueryPort {

    /**
     * Lấy danh sách đóng góp của người dùng có phân trang.
     *
     * @param userId ID người dùng
     * @param page   số trang (0-based)
     * @param size   kích thước trang
     * @return {@link UserWikiContributionPageDTO}
     */
    UserWikiContributionPageDTO findByUserId(UUID userId, int page, int size);
}
