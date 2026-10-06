package com.universe.screen.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataScreenFranchiseJpaRepository extends JpaRepository<ScreenFranchiseJpaEntity, String> {

    Optional<ScreenFranchiseJpaEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
