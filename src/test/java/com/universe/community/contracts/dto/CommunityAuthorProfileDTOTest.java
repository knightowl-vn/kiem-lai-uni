package com.universe.community.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityAuthorProfileDTO Structural and Validation Tests")
class CommunityAuthorProfileDTOTest {

    @Test
    @DisplayName("Should enforce non-null required components in canonical constructor")
    void shouldEnforceNonNullRequiredFields() {
        CommunityNewestFeedResponseDTO emptyPosts = new CommunityNewestFeedResponseDTO(
                List.of(),
                null,
                20,
                false
        );

        assertThatThrownBy(() -> new CommunityAuthorProfileDTO(null, "Display Name", null, null, emptyPosts))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Public handle cannot be null.");

        assertThatThrownBy(() -> new CommunityAuthorProfileDTO("handle", null, null, null, emptyPosts))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Display name cannot be null.");

        assertThatThrownBy(() -> new CommunityAuthorProfileDTO("handle", "Display Name", null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Posts cannot be null.");
    }

    @Test
    @DisplayName("Should allow optional avatarUrl and bio to be null")
    void shouldAllowOptionalFieldsToBeNull() {
        CommunityNewestFeedResponseDTO emptyPosts = new CommunityNewestFeedResponseDTO(
                List.of(),
                null,
                20,
                false
        );

        CommunityAuthorProfileDTO dto = new CommunityAuthorProfileDTO(
                "linh_dao",
                "Linh Đạo",
                null,
                null,
                emptyPosts
        );

        assertThat(dto.publicHandle()).isEqualTo("linh_dao");
        assertThat(dto.displayName()).isEqualTo("Linh Đạo");
        assertThat(dto.avatarUrl()).isNull();
        assertThat(dto.bio()).isNull();
        assertThat(dto.posts()).isSameAs(emptyPosts);
    }

    @Test
    @DisplayName("Should structurally expose only public profile fields and exclude all private Identity fields")
    void shouldExposeOnlyPublicProfileFieldsAndZeroPrivateIdentityFields() {
        RecordComponent[] components = CommunityAuthorProfileDTO.class.getRecordComponents();
        Set<String> componentNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // Expected public components
        assertThat(componentNames).containsExactlyInAnyOrder(
                "publicHandle",
                "displayName",
                "avatarUrl",
                "bio",
                "posts"
        );

        // Explicitly guarantee zero private Identity fields are leaked
        assertThat(componentNames).doesNotContain(
                "userId",
                "id",
                "email",
                "password",
                "passwordHash",
                "role",
                "status",
                "authProvider",
                "providerSubject",
                "providerId",
                "createdAt",
                "updatedAt"
        );
    }
}
