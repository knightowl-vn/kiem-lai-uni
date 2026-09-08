package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataNarrationMediaCleanupTaskJpaRepository
        extends JpaRepository<NarrationMediaCleanupTaskJpaEntity, String> {

    Optional<NarrationMediaCleanupTaskJpaEntity> findByMediaAssetId(String mediaAssetId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM NarrationMediaCleanupTaskJpaEntity t WHERE t.mediaAssetId = :mediaAssetId")
    int deleteByMediaAssetId(@Param("mediaAssetId") String mediaAssetId);

    @Query("SELECT t FROM NarrationMediaCleanupTaskJpaEntity t ORDER BY t.createdAt ASC, t.id ASC")
    List<NarrationMediaCleanupTaskJpaEntity> findOldest(Pageable pageable);
}
