package com.universe.novel.infrastructure.persistence.anchor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SpringDataChapterCommentAnchorJpaRepository
        extends JpaRepository<ChapterCommentAnchorJpaEntity, String> {

    List<ChapterCommentAnchorJpaEntity> findByChapterId(String chapterId);

    List<ChapterCommentAnchorJpaEntity> findByRootCommentIdIn(Collection<String> rootCommentIds);
}
