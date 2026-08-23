package com.universe.novel.infrastructure.persistence.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataNovelProfileJpaRepository
        extends JpaRepository<NovelProfileJpaEntity, String> {

    Optional<NovelProfileJpaEntity> findBySlug(
            String slug
    );
}