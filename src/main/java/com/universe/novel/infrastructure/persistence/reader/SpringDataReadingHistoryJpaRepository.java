package com.universe.novel.infrastructure.persistence.reader;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataReadingHistoryJpaRepository
        extends JpaRepository<ReadingHistoryJpaEntity, String> {

    Optional<ReadingHistoryJpaEntity> findByUserIdAndChapterId(String userId, String chapterId);

    boolean existsByUserIdAndChapterId(String userId, String chapterId);

    @Query(
            value = """
                    SELECT
                        h.chapter_id AS chapterId,
                        c.chapter_number AS chapterNumber,
                        c.title AS chapterTitle,
                        c.slug AS chapterSlug,
                        v.title AS volumeTitle,
                        h.last_read_at AS lastReadAt
                    FROM novel_reading_history h
                    INNER JOIN novel_chapters c
                        ON c.id = h.chapter_id
                    INNER JOIN novel_volumes v
                        ON v.id = c.volume_id
                    WHERE h.user_id = :userId
                      AND c.status = 'PUBLISHED'
                      AND v.status = 'PUBLISHED'
                    ORDER BY h.last_read_at DESC
                    """,
            nativeQuery = true
    )
    List<ReaderReadingHistoryProjection> findPublishedReadingHistoryByUserId(
            @Param("userId") String userId
    );
}
