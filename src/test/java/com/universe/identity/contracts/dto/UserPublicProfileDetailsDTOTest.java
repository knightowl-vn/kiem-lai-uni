package com.universe.identity.contracts.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UserPublicProfileDetailsDTO Contract and Privacy Tests")
class UserPublicProfileDetailsDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("UserPublicProfileDetailsDTO must have exactly 5 record components")
    void shouldHaveExactlyFiveRecordComponents() {
        RecordComponent[] components = UserPublicProfileDetailsDTO.class.getRecordComponents();
        assertThat(components).hasSize(5);

        List<String> componentNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactly(
                "userId",
                "displayName",
                "avatarUrl",
                "publicHandle",
                "bio"
        );
    }

    @Test
    @DisplayName("Existing UserPublicProfileDTO summary must remain strictly 4 record components")
    void shouldPreserveExistingUserPublicProfileDTORecordComponents() {
        RecordComponent[] components = UserPublicProfileDTO.class.getRecordComponents();
        assertThat(components).hasSize(4);

        List<String> componentNames = Arrays.stream(components)
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentNames).containsExactly(
                "userId",
                "displayName",
                "avatarUrl",
                "publicHandle"
        );
    }

    @Test
    @DisplayName("Validation: userId and displayName must be non-null, publicHandle must be non-blank")
    void shouldEnforceConstructorInvariants() {
        UUID validId = UUID.randomUUID();

        // null userId
        assertThatThrownBy(() -> new UserPublicProfileDetailsDTO(null, "Name", "/a.png", "handle", "bio"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId cannot be null");

        // null displayName
        assertThatThrownBy(() -> new UserPublicProfileDetailsDTO(validId, null, "/a.png", "handle", "bio"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("displayName cannot be null");

        // null publicHandle
        assertThatThrownBy(() -> new UserPublicProfileDetailsDTO(validId, "Name", "/a.png", null, "bio"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicHandle cannot be null or blank");

        // blank publicHandle
        assertThatThrownBy(() -> new UserPublicProfileDetailsDTO(validId, "Name", "/a.png", "   ", "bio"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicHandle cannot be null or blank");
    }

    @Test
    @DisplayName("Nullable avatarUrl and bio must be preserved as null")
    void shouldAllowNullAvatarUrlAndNullBio() {
        UUID validId = UUID.randomUUID();
        UserPublicProfileDetailsDTO dto = new UserPublicProfileDetailsDTO(validId, "Anonymous", null, "anon_user", null);

        assertThat(dto.userId()).isEqualTo(validId);
        assertThat(dto.displayName()).isEqualTo("Anonymous");
        assertThat(dto.avatarUrl()).isNull();
        assertThat(dto.publicHandle()).isEqualTo("anon_user");
        assertThat(dto.bio()).isNull();
    }

    @Test
    @DisplayName("Privacy Proof: Serialized JSON must contain ONLY public fields and zero sensitive account fields")
    void shouldNotExposeAnyPrivateIdentityFieldsInJson() throws Exception {
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UserPublicProfileDetailsDTO dto = new UserPublicProfileDetailsDTO(
                userId,
                "Athena Goddess",
                "/media/assets/11111111-1111-1111-1111-111111111111/content",
                "athena_goddess",
                "Chính nghĩa và trí tuệ."
        );

        String json = objectMapper.writeValueAsString(dto);
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});

        // Assert strictly allowed public fields
        assertThat(map.keySet()).containsExactlyInAnyOrder(
                "userId",
                "displayName",
                "avatarUrl",
                "publicHandle",
                "bio"
        );

        // Explicit absence assertions for private/internal Identity fields
        assertThat(map).doesNotContainKey("email");
        assertThat(map).doesNotContainKey("passwordHash");
        assertThat(map).doesNotContainKey("password_hash");
        assertThat(map).doesNotContainKey("role");
        assertThat(map).doesNotContainKey("status");
        assertThat(map).doesNotContainKey("authProvider");
        assertThat(map).doesNotContainKey("auth_provider");
        assertThat(map).doesNotContainKey("providerSubject");
        assertThat(map).doesNotContainKey("provider_subject");
        assertThat(map).doesNotContainKey("aggregateVersion");
        assertThat(map).doesNotContainKey("persistenceVersion");
        assertThat(map).doesNotContainKey("failedLoginCount");
        assertThat(map).doesNotContainKey("lockedUntil");
        assertThat(map).doesNotContainKey("createdAt");
        assertThat(map).doesNotContainKey("updatedAt");
    }
}
