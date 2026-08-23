package com.universe.novel.infrastructure.persistence.chapter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataChapterJpaRepository
        extends JpaRepository<ChapterJpaEntity, String> {

    Optional<ChapterJpaEntity> findBySlug(
            String slug
    );

    boolean existsBySlug(
            String slug
    );

    /*
     * Admin Chapter List
     */
    @Query(
            value = """
                    select
                        c.id as id,
                        c.chapterNumber as chapterNumber,
                        c.title as title,
                        c.slug as slug,
                        c.status as status,
                        c.updatedAt as updatedAt
                    from ChapterJpaEntity c
                    where c.volumeId = :volumeId
                    and (
                        :status is null
                        or c.status = :status
                    )
                    and (
                        :keyword is null
                        or lower(c.title) like lower(concat('%', :keyword, '%'))
                        or lower(c.slug) like lower(concat('%', :keyword, '%'))
                    )
                    order by c.chapterNumber asc
                    """,
            countQuery = """
                    select count(c)
                    from ChapterJpaEntity c
                    where c.volumeId = :volumeId
                    and (
                        :status is null
                        or c.status = :status
                    )
                    and (
                        :keyword is null
                        or lower(c.title) like lower(concat('%', :keyword, '%'))
                        or lower(c.slug) like lower(concat('%', :keyword, '%'))
                    )
                    """
    )
    Page<ChapterListItemProjection> findListItems(
            @Param("volumeId") String volumeId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable
    );

    /*
     * Public Reader Chapter List
     *
     * Chỉ trả Chapter PUBLISHED thuộc Volume PUBLISHED.
     * Không load summary/content/audit/version.
     */
    @Query(
            value = """
                    select
                        c.id as id,
                        c.chapter_number as chapterNumber,
                        c.title as title,
                        c.slug as slug
                    from novel_chapters c
                    inner join novel_volumes v
                        on v.id = c.volume_id
                    where c.volume_id = :volumeId
                    and c.status = 'PUBLISHED'
                    and v.status = 'PUBLISHED'
                    order by c.chapter_number asc
                    """,
            nativeQuery = true
    )
    List<ReaderChapterListItemProjection>
            findPublishedReaderChaptersByVolumeId(
                    @Param("volumeId") String volumeId
            );

    /*
     * Public Reader Table of Contents
     *
     * Chỉ trả Chapter PUBLISHED thuộc Volume PUBLISHED.
     * Không phân nhóm Volume, sắp xếp theo chapter_number ASC.
     */
    @Query(
            value = """
                    select
                        c.id as id,
                        c.chapter_number as chapterNumber,
                        c.title as title,
                        c.slug as slug
                    from novel_chapters c
                    inner join novel_volumes v
                        on v.id = c.volume_id
                    where c.status = 'PUBLISHED'
                    and v.status = 'PUBLISHED'
                    order by c.chapter_number asc
                    """,
            nativeQuery = true
    )
    List<ReaderChapterListItemProjection>
            findAllPublishedReaderChaptersOrderByChapterNumber();

    /*
     * Public Reader Chapter Detail
     *
     * Chỉ trả Chapter PUBLISHED thuộc Volume PUBLISHED.
     */
    @Query(
            value = """
                    select
                        c.id as id,
                        c.volume_id as volumeId,
                        c.chapter_number as chapterNumber,
                        c.title as title,
                        c.slug as slug,
                        c.content as content,
                        v.title as volumeTitle,
                        v.slug as volumeSlug,
                        v.sort_order as volumeSortOrder
                    from novel_chapters c
                    inner join novel_volumes v
                        on v.id = c.volume_id
                    where c.slug = :slug
                    and c.status = 'PUBLISHED'
                    and v.status = 'PUBLISHED'
                    """,
            nativeQuery = true
    )
    Optional<ReaderChapterDetailProjection>
            findPublishedReaderChapterBySlug(
                    @Param("slug") String slug
            );

    /*
     * Public Reader Previous Chapter
     *
     * Tìm Chapter PUBLISHED gần nhất phía trước (chapter_number < current).
     */
    @Query(
            value = """
                    select
                        c.id as id,
                        c.chapter_number as chapterNumber,
                        c.title as title,
                        c.slug as slug
                    from novel_chapters c
                    inner join novel_volumes v
                        on v.id = c.volume_id
                    where c.chapter_number < :currentChapterNumber
                    and c.status = 'PUBLISHED'
                    and v.status = 'PUBLISHED'
                    order by c.chapter_number desc
                    limit 1
                    """,
            nativeQuery = true
    )
    Optional<ReaderChapterListItemProjection>
            findPreviousPublishedReaderChapter(
                    @Param("currentChapterNumber") int currentChapterNumber
            );

    /*
     * Public Reader Next Chapter
     *
     * Tìm Chapter PUBLISHED gần nhất phía sau (chapter_number > current).
     */
    @Query(
            value = """
                    select
                        c.id as id,
                        c.chapter_number as chapterNumber,
                        c.title as title,
                        c.slug as slug
                    from novel_chapters c
                    inner join novel_volumes v
                        on v.id = c.volume_id
                    where c.chapter_number > :currentChapterNumber
                    and c.status = 'PUBLISHED'
                    and v.status = 'PUBLISHED'
                    order by c.chapter_number asc
                    limit 1
                    """,
            nativeQuery = true
    )
    Optional<ReaderChapterListItemProjection>
            findNextPublishedReaderChapter(
                    @Param("currentChapterNumber") int currentChapterNumber
            );

    boolean existsByChapterNumber(
            int chapterNumber
    );

    boolean existsByVolumeIdAndStatus(
            String volumeId,
            String status
    );
}