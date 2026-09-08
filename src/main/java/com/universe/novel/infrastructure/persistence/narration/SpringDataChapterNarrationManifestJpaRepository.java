package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link ChapterNarrationManifestJpaEntity}.
 */
public interface SpringDataChapterNarrationManifestJpaRepository extends JpaRepository<ChapterNarrationManifestJpaEntity, String> {
}
