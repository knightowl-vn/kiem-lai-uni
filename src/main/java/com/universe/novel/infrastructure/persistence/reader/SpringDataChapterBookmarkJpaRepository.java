package com.universe.novel.infrastructure.persistence.reader;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataChapterBookmarkJpaRepository
        extends JpaRepository<ChapterBookmarkJpaEntity, String> {

    boolean existsByUserIdAndChapterId(String userId, String chapterId);

    @Modifying
    @Query("DELETE FROM ChapterBookmarkJpaEntity b WHERE b.userId = :userId AND b.chapterId = :chapterId")
    int deleteByUserIdAndChapterId(
            @Param("userId") String userId,
            @Param("chapterId") String chapterId
    );

    @Query(
            value = """
                    SELECT
                        b.chapter_id AS chapterId,
                        c.chapter_number AS chapterNumber,
                        c.title AS chapterTitle,
                        c.slug AS chapterSlug,
                        v.title AS volumeTitle,
                        b.created_at AS bookmarkedAt
                    FROM novel_chapter_bookmarks b
                    INNER JOIN novel_chapters c
                        ON c.id = b.chapter_id
                    INNER JOIN novel_volumes v
                        ON v.id = c.volume_id
                    WHERE b.user_id = :userId
                      AND c.status = 'PUBLISHED'
                      AND v.status = 'PUBLISHED'
                    ORDER BY b.created_at DESC
                    """,
            nativeQuery = true
    )
    java.util.List<ReaderBookmarkedChapterProjection> findPublishedBookmarkedChaptersByUserId(
            @Param("userId") String userId
    );
}
