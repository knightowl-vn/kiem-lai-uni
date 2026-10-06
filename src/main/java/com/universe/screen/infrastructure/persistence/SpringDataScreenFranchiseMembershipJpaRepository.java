package com.universe.screen.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataScreenFranchiseMembershipJpaRepository extends JpaRepository<ScreenFranchiseMembershipJpaEntity, String> {

    Optional<ScreenFranchiseMembershipJpaEntity> findByFranchiseIdAndTitleId(String franchiseId, String titleId);

    List<ScreenFranchiseMembershipJpaEntity> findByFranchiseId(String franchiseId);

    boolean existsByFranchiseIdAndTitleId(String franchiseId, String titleId);
}
