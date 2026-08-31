package com.universe.novel.infrastructure.persistence.reader;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataReadingProgressJpaRepository
        extends JpaRepository<ReadingProgressJpaEntity, String> {

    Optional<ReadingProgressJpaEntity> findByUserId(String userId);

    boolean existsByUserId(String userId);
}
