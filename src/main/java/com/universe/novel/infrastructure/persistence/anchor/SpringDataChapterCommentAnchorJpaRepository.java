package com.universe.novel.infrastructure.persistence.anchor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataChapterCommentAnchorJpaRepository
        extends JpaRepository<ChapterCommentAnchorJpaEntity, String> {
}
