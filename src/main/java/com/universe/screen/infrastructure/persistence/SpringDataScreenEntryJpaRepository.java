package com.universe.screen.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenEntryJpaRepository extends JpaRepository<ScreenEntryJpaEntity, String> {

    Optional<ScreenEntryJpaEntity> findByTitleIdAndSlug(String titleId, String slug);

    List<ScreenEntryJpaEntity> findByTitleId(String titleId);

    boolean existsByTitleIdAndSlug(String titleId, String slug);
}
