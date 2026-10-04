package com.universe.community.application.mapper;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.domain.CommunityPost;

import java.util.Objects;

/**
 * Application-level mapper that converts domain {@link CommunityPost} aggregates into public {@link CommunityPostPublicDTO} projections.
 */
public final class CommunityPostDTOMapper {

    private CommunityPostDTOMapper() {
    }

    /**
     * Maps a {@link CommunityPost} domain aggregate to a {@link CommunityPostPublicDTO}.
     *
     * @param post the domain post aggregate to project
     * @return the public read projection DTO
     */
    public static CommunityPostPublicDTO toPublicDTO(CommunityPost post) {
        Objects.requireNonNull(post, "CommunityPost domain aggregate cannot be null.");
        return new CommunityPostPublicDTO(
                post.getId(),
                post.getAuthorUserId(),
                post.getCaption(),
                post.getImageMediaAssetId(),
                post.getContentVersion(),
                post.getCreatedAt(),
                post.getUpdatedAt(),
                post.getPublishedAt(),
                post.getStatus() != null ? post.getStatus().name() : null
        );
    }
}
