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

    boolean existsBySlug(
            String slug
    );

    boolean existsBySortOrder(
            int sortOrder
    );
}