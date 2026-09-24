package com.universe.wiki.infrastructure.persistence.contribution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA Repository cho thực thể WikiContributionSourceJpaEntity.
 */
@Repository
public interface SpringDataWikiContributionSourceJpaRepository extends JpaRepository<WikiContributionSourceJpaEntity, String> {

    /**
     * Tìm danh sách nguồn tham khảo theo ID đóng góp, bảo đảm sắp xếp theo source_order tăng dần.
     */
    List<WikiContributionSourceJpaEntity> findByContributionIdOrderBySourceOrderAsc(String contributionId);

    /**
     * Xóa các nguồn tham khảo theo ID đóng góp.
     */
    void deleteByContributionId(String contributionId);
}
