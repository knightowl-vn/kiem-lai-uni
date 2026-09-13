package com.universe.identity.infrastructure.persistence;

import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPersistenceMapperTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private UserPersistenceMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new UserPersistenceMapper();
    }

    @Test
    @DisplayName("toJpaEntity ánh xạ đúng avatarMediaAssetId khi có giá trị")
    void shouldMapUserWithAvatarMediaAssetIdToJpaEntity() {
        User user = User.rehydrate(
                USER_ID,
                "test@example.com",
                "$2a$10$hash",
                "Test User",
                MEDIA_ASSET_ID,
                "/media/assets/" + MEDIA_ASSET_ID + "/content",
                true,
                "Bio",
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                1L,
                NOW
        );

        UserJpaEntity entity = mapper.toJpaEntity(user);

        assertThat(entity.getId()).isEqualTo(USER_ID.toString());
        assertThat(entity.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID.toString());
        assertThat(entity.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        assertThat(entity.isAvatarCustomized()).isTrue();
    }

    @Test
    @DisplayName("toJpaEntity ánh xạ đúng avatarMediaAssetId null cho legacy/google user")
    void shouldMapUserWithoutAvatarMediaAssetIdToJpaEntity() {
        User user = User.rehydrate(
                USER_ID,
                "test@example.com",
                "$2a$10$hash",
                "Test User",
                null,
                "https://example.com/legacy.png",
                false,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.GOOGLE,
                "google-sub",
                1L,
                NOW
        );

        UserJpaEntity entity = mapper.toJpaEntity(user);

        assertThat(entity.getId()).isEqualTo(USER_ID.toString());
        assertThat(entity.getAvatarMediaAssetId()).isNull();
        assertThat(entity.getAvatarUrl()).isEqualTo("https://example.com/legacy.png");
        assertThat(entity.isAvatarCustomized()).isFalse();
    }

    @Test
    @DisplayName("updateJpaEntity cập nhật avatarMediaAssetId chính xác")
    void shouldUpdateJpaEntityWithAvatarMediaAssetId() {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(USER_ID.toString());
        entity.setAvatarMediaAssetId("old-id");

        User user = User.rehydrate(
                USER_ID,
                "test@example.com",
                "$2a$10$hash",
                "Test User",
                MEDIA_ASSET_ID,
                "/media/assets/" + MEDIA_ASSET_ID + "/content",
                true,
                "Bio",
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                2L,
                NOW
        );

        mapper.updateJpaEntity(user, entity);

        assertThat(entity.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID.toString());
        assertThat(entity.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
    }

    @Test
    @DisplayName("toDomain ánh xạ đúng avatarMediaAssetId khi JPA entity có UUID hợp lệ")
    void shouldMapJpaEntityWithAvatarMediaAssetIdToDomain() {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(USER_ID.toString());
        entity.setEmail("test@example.com");
        entity.setPasswordHash("$2a$10$hash");
        entity.setDisplayName("Test User");
        entity.setAvatarMediaAssetId(MEDIA_ASSET_ID.toString());
        entity.setAvatarUrl("/media/assets/" + MEDIA_ASSET_ID + "/content");
        entity.setAvatarCustomized(true);
        entity.setBio("Bio");
        entity.setStatus("ACTIVE");
        entity.setRole(UserRole.USER);
        entity.setAuthProvider("LOCAL");
        entity.setAggregateVersion(1L);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        User user = mapper.toDomain(entity);

        assertThat(user.getId()).isEqualTo(USER_ID);
        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        assertThat(user.isAvatarCustomized()).isTrue();
    }

    @Test
    @DisplayName("toDomain ánh xạ đúng avatarMediaAssetId null cho legacy row")
    void shouldMapLegacyJpaEntityWithNullAvatarMediaAssetIdToDomain() {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(USER_ID.toString());
        entity.setEmail("test@example.com");
        entity.setPasswordHash("$2a$10$hash");
        entity.setDisplayName("Test User");
        entity.setAvatarMediaAssetId(null);
        entity.setAvatarUrl("https://example.com/legacy.png");
        entity.setAvatarCustomized(false);
        entity.setStatus("ACTIVE");
        entity.setRole(UserRole.USER);
        entity.setAuthProvider("LOCAL");
        entity.setAggregateVersion(1L);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        User user = mapper.toDomain(entity);

        assertThat(user.getId()).isEqualTo(USER_ID);
        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/legacy.png");
        assertThat(user.isAvatarCustomized()).isFalse();
    }

    @Test
    @DisplayName("toDomain ném IllegalStateException khi avatarMediaAssetId trong database không đúng định dạng UUID")
    void shouldThrowWhenAvatarMediaAssetIdIsInvalidUuid() {
        UserJpaEntity entity = new UserJpaEntity();
        entity.setId(USER_ID.toString());
        entity.setEmail("test@example.com");
        entity.setDisplayName("Test User");
        entity.setAvatarMediaAssetId("invalid-uuid-string");
        entity.setStatus("ACTIVE");
        entity.setAuthProvider("LOCAL");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThatThrownBy(() -> mapper.toDomain(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Avatar Media Asset ID trong database không đúng định dạng UUID");
    }
}
