package com.universe.wiki.infrastructure.persistence.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface
        SpringDataWikiArticleImageReferenceJpaRepository
        extends JpaRepository<
                WikiArticleImageReferenceJpaEntity,
                String
        > {

    List<WikiArticleImageReferenceJpaEntity>
            findByArticleId(
                    String articleId
            );
}