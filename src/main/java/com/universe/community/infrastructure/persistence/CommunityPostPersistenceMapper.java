package com.universe.community.infrastructure.persistence;

import com.universe.community.application.dto.CommunityPostPublicDTO;
import com.universe.community.domain.CommunityPost;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistence mapper converting between domain {@link CommunityPost}, {@link CommunityPostJpaEntity},
 * and {@link CommunityPostPublicDTO}.
 */
@Component
public class CommunityPostPersistenceMapper {

    /**
     * Maps a domain {@link CommunityPost} to a {@link CommunityPostJpaEntity}.
     */
    public CommunityPostJpaEntity toJpaEntity(CommunityPost domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain post cannot be null.");
        }

        return new CommunityPostJpaEntity(
                domain.getId().toString(),
                domain.getAuthorUserId().toString(),
                domain.getCaption(),
                domain.getImageMediaAssetId() != null ? domain.getImageMediaAssetId().toString() : null,
                domain.getContentVersion(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
        );
    }

    /**
     * Reconstitutes a domain {@link CommunityPost} from a {@link CommunityPostJpaEntity}.
     */
    public CommunityPost toDomain(CommunityPostJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommunityPostJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Post ID");
        UUID authorUserId = parseUuid(entity.getAuthorUserId(), "Author user ID");
        UUID imageMediaAssetId = entity.getImageMediaAssetId() != null
                ? parseUuid(entity.getImageMediaAssetId(), "Image media asset ID")
                : null;

        return CommunityPost.rehydrate(
                id,
                authorUserId,
                entity.getCaption(),
                imageMediaAssetId,
                entity.getContentVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Maps a {@link CommunityPostJpaEntity} to a public read projection {@link CommunityPostPublicDTO}.
     */
    public CommunityPostPublicDTO toPublicDTO(CommunityPostJpaEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("CommunityPostJpaEntity cannot be null.");
        }

        UUID id = parseUuid(entity.getId(), "Post ID");
        UUID authorUserId = parseUuid(entity.getAuthorUserId(), "Author user ID");
        UUID imageMediaAssetId = entity.getImageMediaAssetId() != null
                ? parseUuid(entity.getImageMediaAssetId(), "Image media asset ID")
                : null;

        return new CommunityPostPublicDTO(
                id,
                authorUserId,
                entity.getCaption(),
                imageMediaAssetId,
                entity.getContentVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    /**
     * Maps a domain {@link CommunityPost} to a public read projection {@link CommunityPostPublicDTO}.
     */
    public CommunityPostPublicDTO toPublicDTO(CommunityPost domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain post cannot be null.");
        }

        return new CommunityPostPublicDTO(
                domain.getId(),
                domain.getAuthorUserId(),
                domain.getCaption(),
                domain.getImageMediaAssetId(),
                domain.getContentVersion(),
                domain.getCreatedAt(),
                domain.getUpdatedAt()
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
