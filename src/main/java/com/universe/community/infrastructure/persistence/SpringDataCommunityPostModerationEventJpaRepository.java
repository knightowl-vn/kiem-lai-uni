package com.universe.community.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link CommunityPostModerationEventJpaEntity}.
 */
@Repository
public interface SpringDataCommunityPostModerationEventJpaRepository extends JpaRepository<CommunityPostModerationEventJpaEntity, String> {

    List<CommunityPostModerationEventJpaEntity> findByPostIdOrderByCreatedAtAsc(String postId);
}
