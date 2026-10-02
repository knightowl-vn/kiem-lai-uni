package com.universe.community.contracts.dto;

/**
 * Request payload for editing an existing Community post's caption.
 */
public record EditCommunityPostCaptionRequestDTO(
        String caption
) {
}
