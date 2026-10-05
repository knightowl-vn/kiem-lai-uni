package com.universe.search.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommunityProfileSearchItemDTO — Structural and Privacy Contract Tests")
class CommunityProfileSearchItemDTOTest {

    @Test
    @DisplayName("DTO creates valid immutable instance with non-null required fields")
    void validInstantiation() {
        CommunityProfileSearchItemDTO dto = new CommunityProfileSearchItemDTO(
                "Trần Bình An",
                "tranbinhan",
                "https://images.kiemlai.com/avatar.jpg",
                "/community/@tranbinhan"
        );

        assertThat(dto.displayName()).isEqualTo("Trần Bình An");
        assertThat(dto.publicHandle()).isEqualTo("tranbinhan");
        assertThat(dto.avatarUrl()).isEqualTo("https://images.kiemlai.com/avatar.jpg");
        assertThat(dto.profileUrl()).isEqualTo("/community/@tranbinhan");
    }

    @Test
    @DisplayName("DTO allows null avatarUrl")
    void allowsNullAvatarUrl() {
        CommunityProfileSearchItemDTO dto = new CommunityProfileSearchItemDTO(
                "Trần Bình An",
                "tranbinhan",
                null,
                "/community/@tranbinhan"
        );

        assertThat(dto.avatarUrl()).isNull();
    }

    @Test
    @DisplayName("DTO rejects null displayName, publicHandle, or profileUrl")
    void rejectsNullRequiredFields() {
        assertThatThrownBy(() -> new CommunityProfileSearchItemDTO(
                null,
                "tranbinhan",
                "https://img/1.png",
                "/community/@tranbinhan"
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("displayName must not be null");

        assertThatThrownBy(() -> new CommunityProfileSearchItemDTO(
                "Trần Bình An",
                null,
                "https://img/1.png",
                "/community/@tranbinhan"
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("publicHandle must not be null");

        assertThatThrownBy(() -> new CommunityProfileSearchItemDTO(
                "Trần Bình An",
                "tranbinhan",
                "https://img/1.png",
                null
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("profileUrl must not be null");
    }

    @Test
    @DisplayName("DTO strictly isolates identity: does NOT contain private/internal user fields")
    void privacyContract_noPrivateFieldsExposed() {
        Set<String> declaredFieldNames = Arrays.stream(CommunityProfileSearchItemDTO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        // Must ONLY contain the 4 safe presentation fields
        assertThat(declaredFieldNames).containsExactlyInAnyOrder(
                "displayName",
                "publicHandle",
                "avatarUrl",
                "profileUrl"
        );

        // Explicitly assert absence of private/sensitive identity fields
        assertThat(declaredFieldNames).doesNotContain(
                "userId", "id", "email", "password", "passwordHash",
                "role", "roles", "status", "bio", "createdAt", "updatedAt"
        );
    }

    @Test
    @DisplayName("CommunityProfileSearchResultDTO validates non-null query and items")
    void searchResultDTOValidation() {
        CommunityProfileSearchItemDTO item = new CommunityProfileSearchItemDTO(
                "Trần Bình An", "tranbinhan", null, "/community/@tranbinhan"
        );
        CommunityProfileSearchResultDTO result = new CommunityProfileSearchResultDTO("tran", List.of(item));

        assertThat(result.query()).isEqualTo("tran");
        assertThat(result.items()).containsExactly(item);

        assertThatThrownBy(() -> new CommunityProfileSearchResultDTO(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query must not be null");

        CommunityProfileSearchResultDTO nullItemsResult = new CommunityProfileSearchResultDTO("tran", null);
        assertThat(nullItemsResult.items()).isEmpty();
    }
}
