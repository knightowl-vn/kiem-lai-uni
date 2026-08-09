package com.universe.wiki.infrastructure.persistence.revision;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataWikiArticleRevisionJpaRepository
        extends JpaRepository<
                WikiArticleRevisionJpaEntity,
                String
        > {

    Optional<WikiArticleRevisionJpaEntity>
            findByArticleIdAndRevisionNumber(
                    String articleId,
                    long revisionNumber
            );

    Page<WikiArticleRevisionJpaEntity>
            findByArticleId(
                    String articleId,
                    Pageable pageable
            );
}