package com.universe.interaction.infrastructure.persistence.reaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ReactionJpaEntity}.
 */
public interface SpringDataReactionRepository extends JpaRepository<ReactionJpaEntity, String> {

    Optional<ReactionJpaEntity> findByUserIdAndTargetTypeAndTargetId(
            String userId,
            String targetType,
            String targetId
    );

    List<ReactionJpaEntity> findByUserIdAndTargetTypeAndTargetIdIn(
            String userId,
            String targetType,
            Collection<String> targetIds
    );

    @Query("SELECT r.reactionType, COUNT(r) FROM ReactionJpaEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId GROUP BY r.reactionType")
    List<Object[]> countReactionsByTargetGroupedByType(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );

    @Query("SELECT r.targetId, r.reactionType, COUNT(r) FROM ReactionJpaEntity r WHERE r.targetType = :targetType AND r.targetId IN :targetIds GROUP BY r.targetId, r.reactionType")
    List<Object[]> countReactionsByTargetIdsGroupedByType(
            @Param("targetType") String targetType,
            @Param("targetIds") Collection<String> targetIds
    );

    @Query("SELECT COUNT(r) FROM ReactionJpaEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId")
    long countTotalReactionsByTarget(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );

    @Modifying
    @Query("DELETE FROM ReactionJpaEntity r WHERE r.userId = :userId AND r.targetType = :targetType AND r.targetId = :targetId")
    int deleteByUserIdAndTargetTypeAndTargetId(
            @Param("userId") String userId,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId
    );
}
