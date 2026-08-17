package com.universe.wiki.infrastructure.persistence.image;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataWikiImageJpaRepository
        extends JpaRepository<
                WikiImageJpaEntity,
                String
        > {

    /*
     * =====================================================
     * DUPLICATE DETECTION
     * =====================================================
     */

    Optional<WikiImageJpaEntity>
            findByContentHash(
                    String contentHash
            );


    /*
     * =====================================================
     * IMAGE URL LOOKUP
     * =====================================================
     */

    List<WikiImageJpaEntity>
            findByUrlIn(
                    Collection<String> urls
            );


    /*
     * =====================================================
     * ALL ORPHAN IMAGES
     * =====================================================
     *
     * Dùng cho D4:
     * ảnh không được article hiện tại
     * và revision nào tham chiếu.
     */

    @Query("""
            SELECT image
            FROM WikiImageJpaEntity image
            WHERE NOT EXISTS (
                SELECT articleReference.id
                FROM WikiArticleImageReferenceJpaEntity
                        articleReference
                WHERE articleReference.imageId = image.id
            )
            AND NOT EXISTS (
                SELECT revisionReference.id
                FROM WikiRevisionImageReferenceJpaEntity
                        revisionReference
                WHERE revisionReference.imageId = image.id
            )
            """)
    List<WikiImageJpaEntity>
            findOrphanImages();


    /*
     * =====================================================
     * CLEANUP CANDIDATES
     * =====================================================
     *
     * D5:
     *
     * orphan
     * +
     * created_at <= cutoff
     *
     * cutoff hiện tại = now - 7 ngày.
     */

    @Query("""
            SELECT image
            FROM WikiImageJpaEntity image
            WHERE image.createdAt <= :cutoff
            AND NOT EXISTS (
                SELECT articleReference.id
                FROM WikiArticleImageReferenceJpaEntity
                        articleReference
                WHERE articleReference.imageId = image.id
            )
            AND NOT EXISTS (
                SELECT revisionReference.id
                FROM WikiRevisionImageReferenceJpaEntity
                        revisionReference
                WHERE revisionReference.imageId = image.id
            )
            ORDER BY image.createdAt ASC
            """)
    List<WikiImageJpaEntity>
            findCleanupCandidates(
                    @Param("cutoff")
                    Instant cutoff
            );
}