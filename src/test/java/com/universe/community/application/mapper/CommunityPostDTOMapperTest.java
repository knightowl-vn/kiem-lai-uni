package com.universe.community.application.mapper;

import com.universe.community.contracts.dto.CommunityPostPublicDTO;
import com.universe.community.domain.CommunityPost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityPostDTOMapperTest {

    @Test
    @DisplayName("Should correctly project from domain CommunityPost with image")
    void shouldProjectFromDomainWithImage() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID imageId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Instant now = Instant.now();

        CommunityPost post = CommunityPost.create(postId, authorId, "Domain post caption", imageId, now);
        CommunityPostPublicDTO dto = CommunityPostDTOMapper.toPublicDTO(post);

        assertThat(dto.id()).isEqualTo(postId);
        assertThat(dto.authorUserId()).isEqualTo(authorId);
        assertThat(dto.caption()).isEqualTo("Domain post caption");
        assertThat(dto.imageMediaAssetId()).isEqualTo(imageId);
        assertThat(dto.imageUrl()).isEqualTo("/media/assets/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/content");
        assertThat(dto.contentVersion()).isEqualTo(0);
        assertThat(dto.createdAt()).isEqualTo(now);
        assertThat(dto.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should correctly project from domain CommunityPost without image")
    void shouldProjectFromDomainWithoutImage() {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        CommunityPost post = CommunityPost.create(postId, authorId, "Domain post without image", null, now);
        CommunityPostPublicDTO dto = CommunityPostDTOMapper.toPublicDTO(post);

        assertThat(dto.id()).isEqualTo(postId);
        assertThat(dto.authorUserId()).isEqualTo(authorId);
        assertThat(dto.caption()).isEqualTo("Domain post without image");
        assertThat(dto.imageMediaAssetId()).isNull();
        assertThat(dto.imageUrl()).isNull();
        assertThat(dto.contentVersion()).isEqualTo(0);
        assertThat(dto.createdAt()).isEqualTo(now);
        assertThat(dto.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Should throw NullPointerException when projecting null domain CommunityPost")
    void shouldThrowWhenDomainPostIsNull() {
        assertThatThrownBy(() -> CommunityPostDTOMapper.toPublicDTO(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("CommunityPost domain aggregate cannot be null.");
    }
}
