package com.universe.screen.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenFranchiseMembershipJpaRepository extends JpaRepository<ScreenFranchiseMembershipJpaEntity, String> {

    Optional<ScreenFranchiseMembershipJpaEntity> findByFranchiseIdAndTitleId(String franchiseId, String titleId);

    @Query("SELECT m FROM ScreenFranchiseMembershipJpaEntity m WHERE m.franchiseId = :franchiseId ORDER BY m.displayOrder ASC, m.id ASC")
    List<ScreenFranchiseMembershipJpaEntity> findByFranchiseId(@Param("franchiseId") String franchiseId);

    boolean existsByFranchiseIdAndTitleId(String franchiseId, String titleId);
}
