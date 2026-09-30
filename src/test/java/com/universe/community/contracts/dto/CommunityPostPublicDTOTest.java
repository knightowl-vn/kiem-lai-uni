package com.universe.community.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityPostPublicDTOTest {

    @Test
    @DisplayName("Should return canonical media delivery URL when imageMediaAssetId is present")
    void shouldReturnCanonicalMediaDeliveryUrlWhenImagePresent() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID imageId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Instant now = Instant.now();

        CommunityPostPublicDTO dto = new CommunityPostPublicDTO(
                postId,
                authorId,
                "Caption with photo",
                imageId,
                0,
                now,
                now
        );

        assertThat(dto.imageUrl()).isEqualTo("/media/assets/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/content");
    }

    @Test
    @DisplayName("Should return null imageUrl when imageMediaAssetId is null")
    void shouldReturnNullImageUrlWhenCaptionOnly() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        CommunityPostPublicDTO dto = new CommunityPostPublicDTO(
                postId,
                authorId,
                "Caption-only post",
                null,
                0,
                now,
                now
        );

        assertThat(dto.imageUrl()).isNull();
    }

    @Test
    @DisplayName("Should enforce non-null required fields")
    void shouldEnforceNonNullFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> new CommunityPostPublicDTO(null, id, "caption", null, 0, now, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Post ID");

        assertThatThrownBy(() -> new CommunityPostPublicDTO(id, null, "caption", null, 0, now, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Author user ID");

        assertThatThrownBy(() -> new CommunityPostPublicDTO(id, id, null, null, 0, now, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Caption");

        assertThatThrownBy(() -> new CommunityPostPublicDTO(id, id, "caption", null, 0, null, now))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp");

        assertThatThrownBy(() -> new CommunityPostPublicDTO(id, id, "caption", null, 0, now, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UpdatedAt timestamp");
    }
}
