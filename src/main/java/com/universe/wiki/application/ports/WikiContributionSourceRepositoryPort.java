package com.universe.wiki.application.ports;

import com.universe.wiki.domain.contribution.WikiContributionSource;

import java.util.List;
import java.util.UUID;

/**
 * Port persistence quản trị lưu trữ và truy vấn nguồn tham khảo của đóng góp bài viết Wiki.
 */
public interface WikiContributionSourceRepositoryPort {

    /**
     * Lưu trữ một nguồn tham khảo.
     *
     * @param source thực thể nguồn tham khảo cần lưu
     * @return thực thể đã được lưu
     */
    WikiContributionSource save(WikiContributionSource source);

    /**
     * Lưu trữ danh sách nguồn tham khảo theo lô.
     *
     * @param sources danh sách nguồn tham khảo cần lưu
     * @return danh sách thực thể đã được lưu
     */
    List<WikiContributionSource> saveAll(List<WikiContributionSource> sources);

    /**
     * Tìm kiếm toàn bộ các nguồn tham khảo của một đóng góp, bảo đảm sắp xếp tăng dần theo sourceOrder.
     *
     * @param contributionId ID của đóng góp
     * @return danh sách các nguồn tham khảo có thứ tự sourceOrder tăng dần (0..4)
     */
    List<WikiContributionSource> findByContributionId(UUID contributionId);
}
