package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.CommentRevision;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link CommentRevision} and {@link CommentRevisionJpaEntity}.
 *
 * <p>Preserves all domain invariants during round trip:
 * <ul>
 *   <li>Exact scalar UUID &harr; CHAR(36) String conversion;</li>
 *   <li>Parent comment ID preservation;</li>
 *   <li>Revision number preservation;</li>
 *   <li>Body preservation;</li>
 *   <li>Timestamp preservation (createdAt).</li>
 * </ul>
 */
@Component
public class CommentRevisionPersistenceMapper {

    /**
     * Maps a domain {@link CommentRevision} to a new {@link CommentRevisionJpaEntity}.
     */
    public CommentRevisionJpaEntity toJpaEntity(CommentRevision domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain CommentRevision cannot be null.");
        }

        return new CommentRevisionJpaEntity(
                domain.getId().toString(),
                domain.getCommentId().toString(),
                domain.getRevisionNumber(),
                domain.getBody(),
                domain.getCreatedAt()
        );
    }

    /**
     * Reconstitutes a domain {@link CommentRevision} from a {@link CommentRevisionJpaEntity}.
     */
    public CommentRevision toDomain(CommentRevisionJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommentRevisionJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Revision ID");
        UUID commentId = parseUuid(entity.getCommentId(), "Comment ID");

        return new CommentRevision(
                id,
                commentId,
                entity.getRevisionNumber(),
                entity.getBody(),
                entity.getCreatedAt()
        );
    }

    private UUID parseUuid(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(fieldName + " in database cannot be null or blank.");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(fieldName + " in database has invalid UUID format: " + raw, ex);
        }
    }
}
