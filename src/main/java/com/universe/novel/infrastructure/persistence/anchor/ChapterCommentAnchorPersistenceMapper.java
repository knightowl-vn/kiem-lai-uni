package com.universe.novel.infrastructure.persistence.anchor;

import com.universe.novel.domain.anchor.ChapterCommentAnchor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persistence mapper translating between {@link ChapterCommentAnchor} domain aggregates
 * and {@link ChapterCommentAnchorJpaEntity} database entities.
 */
@Component
public class ChapterCommentAnchorPersistenceMapper {

    public ChapterCommentAnchorJpaEntity toJpaEntity(ChapterCommentAnchor domain) {
        if (domain == null) {
            return null;
        }
        return new ChapterCommentAnchorJpaEntity(
                domain.getRootCommentId().toString(),
                domain.getChapterId().toString(),
                domain.getContentVersion(),
                domain.getBlockKey(),
                domain.getAnchorKind(),
                domain.getStartOffset(),
                domain.getEndOffset(),
                domain.getSelectedText(),
                domain.getContextBefore(),
                domain.getContextAfter(),
                domain.getCreatedAt()
        );
    }

    public ChapterCommentAnchor toDomain(ChapterCommentAnchorJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return ChapterCommentAnchor.rehydrate(
                UUID.fromString(entity.getRootCommentId()),
                UUID.fromString(entity.getChapterId()),
                entity.getContentVersion(),
                entity.getBlockKey(),
                entity.getAnchorKind(),
                entity.getStartOffset(),
                entity.getEndOffset(),
                entity.getSelectedText(),
                entity.getContextBefore(),
                entity.getContextAfter(),
                entity.getCreatedAt()
        );
    }
}
