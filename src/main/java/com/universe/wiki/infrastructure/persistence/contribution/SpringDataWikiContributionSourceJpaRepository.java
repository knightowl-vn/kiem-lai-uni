package com.universe.wiki.infrastructure.persistence.contribution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Đếm số lượng nguồn tham khảo theo danh sách ID đóng góp.
     * Trả về danh sách [contributionId, count].
     */
    @Query("SELECT s.contributionId, COUNT(s) FROM WikiContributionSourceJpaEntity s WHERE s.contributionId IN :contributionIds GROUP BY s.contributionId")
    List<Object[]> countSourcesByContributionIds(@Param("contributionIds") Collection<String> contributionIds);
}
