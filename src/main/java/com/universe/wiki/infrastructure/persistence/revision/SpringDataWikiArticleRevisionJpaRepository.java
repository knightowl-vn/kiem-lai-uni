package com.universe.wiki.infrastructure.persistence.revision;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            DELETE FROM WikiArticleRevisionJpaEntity revision
            WHERE revision.articleId = :articleId
            """)
    int deleteAllByArticleId(
            @Param("articleId")
            String articleId
    );
}