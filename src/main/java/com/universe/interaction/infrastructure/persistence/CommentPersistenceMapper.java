package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.CommentTargetType;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link Comment} and {@link CommentJpaEntity}.
 *
 * <p>Preserves all domain invariants during round trip:
 * <ul>
 *   <li>Exact scalar UUID &harr; CHAR(36) String conversion;</li>
 *   <li>Target type and target ID preservation;</li>
 *   <li>Author user ID preservation;</li>
 *   <li>Nullable parent comment ID preservation (null for root, UUID string for reply);</li>
 *   <li>Body preservation for ACTIVE comments and null body for DELETED tombstones;</li>
 *   <li>Timestamp preservation (createdAt, updatedAt, deletedAt);</li>
 *   <li>Rehydration via {@link Comment#rehydrate}.</li>
 * </ul>
 */
@Component
public class CommentPersistenceMapper {

    /**
     * Maps a domain {@link Comment} to a new {@link CommentJpaEntity}.
     */
    public CommentJpaEntity toJpaEntity(Comment domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain comment cannot be null.");
        }

        return new CommentJpaEntity(
                domain.getId().toString(),
                domain.getTargetType().name(),
                domain.getTargetId().toString(),
                domain.getAuthorUserId().toString(),
                domain.getParentCommentId() != null ? domain.getParentCommentId().toString() : null,
                domain.getBody(),
                domain.getStatus().name(),
                domain.getCreatedAt(),
                domain.getUpdatedAt(),
                domain.getDeletedAt()
        );
    }

    /**
     * Reconstitutes a domain {@link Comment} from a {@link CommentJpaEntity}.
     */
    public Comment toDomain(CommentJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommentJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Comment ID");
        CommentTargetType targetType = parseTargetType(entity.getTargetType());
        UUID targetId = parseUuid(entity.getTargetId(), "Target ID");
        CommentTarget target = new CommentTarget(targetType, targetId);
        UUID authorUserId = parseUuid(entity.getAuthorUserId(), "Author user ID");
        UUID parentCommentId = entity.getParentCommentId() != null
                ? parseUuid(entity.getParentCommentId(), "Parent comment ID")
                : null;
        CommentStatus status = parseStatus(entity.getStatus());

        return Comment.rehydrate(
                id,
                target,
                authorUserId,
                parentCommentId,
                entity.getBody(),
                status,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
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

    private CommentTargetType parseTargetType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Target type in database cannot be null or blank.");
        }
        try {
            return CommentTargetType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid target type in database: " + raw, ex);
        }
    }

    private CommentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Comment status in database cannot be null or blank.");
        }
        try {
            return CommentStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid comment status in database: " + raw, ex);
        }
    }
}
