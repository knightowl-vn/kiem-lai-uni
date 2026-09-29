package com.universe.community.infrastructure.persistence;

import com.universe.community.application.dto.CommunityPostRevisionPublicDTO;
import com.universe.community.domain.CommunityPostRevision;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link CommunityPostRevision},
 * {@link CommunityPostRevisionJpaEntity}, and {@link CommunityPostRevisionPublicDTO}.
 */
@Component
public class CommunityPostRevisionPersistenceMapper {

    /**
     * Maps a domain {@link CommunityPostRevision} to a {@link CommunityPostRevisionJpaEntity}.
     */
    public CommunityPostRevisionJpaEntity toJpaEntity(CommunityPostRevision domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain revision cannot be null.");
        }

        return new CommunityPostRevisionJpaEntity(
                domain.getId().toString(),
                domain.getPostId().toString(),
                domain.getRevisionNumber(),
                domain.getEditorUserId().toString(),
                domain.getPreviousCaption(),
                domain.getCaption(),
                domain.getEditedAt()
        );
    }

    /**
     * Reconstitutes a domain {@link CommunityPostRevision} from a {@link CommunityPostRevisionJpaEntity}.
     */
    public CommunityPostRevision toDomain(CommunityPostRevisionJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommunityPostRevisionJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Revision ID");
        UUID postId = parseUuid(entity.getPostId(), "Post ID");
        UUID editorUserId = parseUuid(entity.getEditorUserId(), "Editor user ID");

        return new CommunityPostRevision(
                id,
                postId,
                entity.getRevisionNumber(),
                editorUserId,
                entity.getPreviousCaption(),
                entity.getCaption(),
                entity.getEditedAt()
        );
    }

    /**
     * Maps a {@link CommunityPostRevisionJpaEntity} to a public read projection {@link CommunityPostRevisionPublicDTO}.
     */
    public CommunityPostRevisionPublicDTO toPublicDTO(CommunityPostRevisionJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommunityPostRevisionJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Revision ID");
        UUID postId = parseUuid(entity.getPostId(), "Post ID");
        UUID editorUserId = parseUuid(entity.getEditorUserId(), "Editor user ID");

        return new CommunityPostRevisionPublicDTO(
                id,
                postId,
                entity.getRevisionNumber(),
                editorUserId,
                entity.getPreviousCaption(),
                entity.getCaption(),
                entity.getEditedAt()
        );
    }

    /**
     * Maps a domain {@link CommunityPostRevision} to a public read projection {@link CommunityPostRevisionPublicDTO}.
     */
    public CommunityPostRevisionPublicDTO toPublicDTO(CommunityPostRevision domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain revision cannot be null.");
        }

        return new CommunityPostRevisionPublicDTO(
                domain.getId(),
                domain.getPostId(),
                domain.getRevisionNumber(),
                domain.getEditorUserId(),
                domain.getPreviousCaption(),
                domain.getCaption(),
                domain.getEditedAt()
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
