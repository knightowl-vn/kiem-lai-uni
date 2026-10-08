package com.universe.screen.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenFranchiseJpaRepository extends JpaRepository<ScreenFranchiseJpaEntity, String> {

    Optional<ScreenFranchiseJpaEntity> findBySlug(String slug);

    @Query("SELECT f FROM ScreenFranchiseJpaEntity f ORDER BY f.name ASC, f.id ASC")
    List<ScreenFranchiseJpaEntity> findAllOrderByNameAsc();

    boolean existsBySlug(String slug);
}
