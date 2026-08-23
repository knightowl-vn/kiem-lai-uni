package com.universe.novel.infrastructure.persistence.volume;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface SpringDataVolumeJpaRepository
        extends JpaRepository<VolumeJpaEntity, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
	        select v
	        from VolumeJpaEntity v
	        where v.id = :id
	        """)
	Optional<VolumeJpaEntity> findByIdForUpdate(
	        @Param("id") String id
	);

	Optional<VolumeJpaEntity> findBySlug(
            String slug
    );

    List<VolumeJpaEntity> findAllByOrderBySortOrderAsc();
    
    @Query(
            value = """
                    select
                        v.id as id,
                        v.title as title,
                        v.slug as slug,
                        v.sort_order as sortOrder,
                        count(c.id) as publishedChapterCount
                    from novel_volumes v
                    left join novel_chapters c
                        on c.volume_id = v.id
                        and c.status = 'PUBLISHED'
                    where v.status = 'PUBLISHED'
                    group by
                        v.id,
                        v.title,
                        v.slug,
                        v.sort_order
                    order by v.sort_order asc
                    """,
            nativeQuery = true
    )
    List<ReaderVolumeListItemProjection> findPublishedReaderVolumes();

    boolean existsBySlug(
            String slug
    );

    boolean existsBySortOrder(
            int sortOrder
    );
}
