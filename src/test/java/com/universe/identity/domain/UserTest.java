package com.universe.identity.domain;

import com.universe.identity.contracts.events.UserRegisteredEvent;
import com.universe.shared.events.DomainEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-05T12:00:00Z"
            );

    @Test
    @DisplayName(
            "Tạo tài khoản local với dữ liệu mặc định hợp lệ"
    )
    void shouldCreateLocalUserWithDefaultValues() {
        User user =
                User.createLocal(
                        USER_ID,
                        new Email(
                                "athena@example.com"
                        ),
                        "$2a$10$hashedPassword",
                        "Athena",
                        NOW
                );

        assertThat(user.getId())
                .isEqualTo(USER_ID);

        assertThat(user.getEmail().value())
                .isEqualTo(
                        "athena@example.com"
                );

        assertThat(user.getPasswordHash())
                .isEqualTo(
                        "$2a$10$hashedPassword"
                );

        assertThat(user.getDisplayName())
                .isEqualTo("Athena");

        assertThat(user.getAvatarMediaAssetId())
                .isNull();

        assertThat(user.getAvatarUrl())
                .isNull();

        assertThat(user.getBio())
                .isNull();

        assertThat(user.getStatus())
                .isEqualTo(
                        UserStatus.ACTIVE
                );

        assertThat(user.getRole())
                .isEqualTo(
                        UserRole.USER
                );

        assertThat(user.getAuthProvider())
                .isEqualTo(
                        AuthProvider.LOCAL
                );

        assertThat(user.getProviderSubject())
                .isNull();

        assertThat(user.getAggregateVersion())
                .isEqualTo(1L);

        assertThat(user.getCreatedAt())
                .isEqualTo(NOW);

        assertThat(user.hasPassword())
                .isTrue();

        assertThat(user.isGoogleAccount())
                .isFalse();
    }

    @Test
    @DisplayName(
            "Tạo tài khoản local phát đúng UserRegisteredEvent"
    )
    void shouldPublishRegisteredEventWhenCreatingLocalUser() {
        User user =
                User.createLocal(
                        USER_ID,
                        new Email(
                                "athena@example.com"
                        ),
                        "$2a$10$hashedPassword",
                        "Athena",
                        NOW
                );

        List<DomainEvent> events =
                user.domainEventsSnapshot();

        assertThat(events)
                .hasSize(1);

        assertThat(events.get(0))
                .isInstanceOf(
                        UserRegisteredEvent.class
                );

        UserRegisteredEvent event =
                (UserRegisteredEvent)
                        events.get(0);

        assertThat(event.eventId())
                .isNotNull();

        assertThat(event.eventType())
                .isEqualTo(
                        "UserRegisteredEvent"
                );

        assertThat(event.aggregateId())
                .isEqualTo(USER_ID);

        assertThat(event.email())
                .isEqualTo(
                        "athena@example.com"
                );

        assertThat(event.displayName())
                .isEqualTo("Athena");

        assertThat(event.occurredAt())
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName(
            "Tạo tài khoản Google không có mật khẩu local"
    )
    void shouldCreateGoogleUserWithoutLocalPassword() {
        User user =
                User.createGoogle(
                        USER_ID,
                        new Email(
                                "athena@example.com"
                        ),
                        "Athena",
                        "https://example.com/avatar.png",
                        "google-subject-123",
                        NOW
                );

        assertThat(user.getPasswordHash())
                .isNull();

        assertThat(user.hasPassword())
                .isFalse();

        assertThat(user.getAuthProvider())
                .isEqualTo(
                        AuthProvider.GOOGLE
                );

        assertThat(user.isGoogleAccount())
                .isTrue();

        assertThat(user.getProviderSubject())
                .isEqualTo(
                        "google-subject-123"
                );

        assertThat(user.getAvatarMediaAssetId())
                .isNull();

        assertThat(user.getAvatarUrl())
                .isEqualTo(
                        "https://example.com/avatar.png"
                );

        assertThat(user.getStatus())
                .isEqualTo(
                        UserStatus.ACTIVE
                );

        assertThat(user.getRole())
                .isEqualTo(
                        UserRole.USER
                );

        assertThat(
                user.domainEventsSnapshot()
        )
                .hasSize(1);
    }

    @Test
    @DisplayName(
            "Rehydrate không phát domain event"
    )
    void shouldNotPublishEventWhenRehydratingUser() {
        User user =
                User.rehydrate(
                        USER_ID,
                        "athena@example.com",
                        "$2a$10$hashedPassword",
                        "Athena",
                        null,
                        "https://example.com/avatar.png",
                        true,
                        "Bio của Athena",
                        UserStatus.ACTIVE,
                        UserRole.ADMIN,
                        AuthProvider.LOCAL,
                        null,
                        5L,
                        NOW
                );

        assertThat(user.getId())
                .isEqualTo(USER_ID);

        assertThat(user.getAggregateVersion())
                .isEqualTo(5L);

        assertThat(user.getRole())
                .isEqualTo(
                        UserRole.ADMIN
                );

        assertThat(user.isAvatarCustomized())
                .isTrue();

        assertThat(
                user.domainEventsSnapshot()
        )
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Cập nhật tên hiển thị mới làm tăng aggregate version"
    )
    void shouldIncreaseVersionWhenDisplayNameChanges() {
        User user =
                createLocalUser();

        long versionBefore =
                user.getAggregateVersion();

        user.updateDisplayName(
                "Athena Updated"
        );

        assertThat(user.getDisplayName())
                .isEqualTo(
                        "Athena Updated"
                );

        assertThat(user.getAggregateVersion())
                .isEqualTo(
                        versionBefore + 1
                );
    }

    @Test
    @DisplayName(
            "Cập nhật cùng tên hiển thị không làm tăng aggregate version"
    )
    void shouldNotIncreaseVersionWhenDisplayNameDoesNotChange() {
        User user =
                createLocalUser();

        long versionBefore =
                user.getAggregateVersion();

        user.updateDisplayName(
                "Athena"
        );

        assertThat(user.getAggregateVersion())
                .isEqualTo(versionBefore);
    }

    @Test
    @DisplayName(
            "Xóa avatar đánh dấu người dùng đã tùy chỉnh avatar"
    )
    void shouldMarkAvatarAsCustomizedWhenRemovingAvatar() {
        User user =
                User.rehydrate(
                        USER_ID,
                        "athena@example.com",
                        "$2a$10$hashedPassword",
                        "Athena",
                        null,
                        "https://example.com/avatar.png",
                        false,
                        null,
                        UserStatus.ACTIVE,
                        UserRole.USER,
                        AuthProvider.GOOGLE,
                        "google-subject-123",
                        2L,
                        NOW
                );

        long versionBefore =
                user.getAggregateVersion();

        user.removeAvatar();

        assertThat(user.getAvatarMediaAssetId())
                .isNull();

        assertThat(user.getAvatarUrl())
                .isNull();

        assertThat(user.isAvatarCustomized())
                .isTrue();

        assertThat(user.getAggregateVersion())
                .isEqualTo(
                        versionBefore + 1
                );
    }

    @Test
    @DisplayName(
            "Khóa và kích hoạt lại tài khoản thành công"
    )
    void shouldBlockAndActivateUser() {
        User user =
                createLocalUser();

        user.block();

        assertThat(user.getStatus())
                .isEqualTo(
                        UserStatus.BLOCKED
                );

        user.activate();

        assertThat(user.getStatus())
                .isEqualTo(
                        UserStatus.ACTIVE
                );
    }

    @Test
    @DisplayName(
            "Cấm tài khoản thành công"
    )
    void shouldBanUser() {
        User user =
                createLocalUser();

        user.ban();

        assertThat(user.getStatus())
                .isEqualTo(
                        UserStatus.BANNED
                );
    }

    @Test
    @DisplayName(
            "Không cho block tài khoản đã bị banned"
    )
    void shouldRejectBlockingBannedUser() {
        User user =
                createLocalUser();

        user.ban();

        assertThatThrownBy(
                user::block
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Tài khoản đã bị cấm vĩnh viễn."
                );
    }

    @Test
    @DisplayName(
            "Clear domain events xóa toàn bộ event đang chờ"
    )
    void shouldClearDomainEvents() {
        User user =
                createLocalUser();

        assertThat(
                user.domainEventsSnapshot()
        )
                .hasSize(1);

        user.clearDomainEvents();

        assertThat(
                user.domainEventsSnapshot()
        )
                .isEmpty();
    }

    @Test
    @DisplayName(
            "Domain event snapshot không thể bị chỉnh sửa"
    )
    void shouldReturnUnmodifiableDomainEventSnapshot() {
        User user =
                createLocalUser();

        List<DomainEvent> events =
                user.domainEventsSnapshot();

        assertThatCode(() ->
                events.get(0)
        )
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                events.clear()
        )
                .isInstanceOf(
                        UnsupportedOperationException.class
                );
    }

    @Test
    @DisplayName(
            "Cập nhật media avatar lưu ID + URL + customized=true và tăng version"
    )
    void shouldUpdateMediaAvatarAndMarkCustomized() {
        User user = createLocalUser();
        UUID mediaAssetId = UUID.randomUUID();
        String deliveryUrl = "/media/assets/" + mediaAssetId + "/content";

        long versionBefore = user.getAggregateVersion();

        user.updateMediaAvatar(mediaAssetId, deliveryUrl);

        assertThat(user.getAvatarMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(user.getAvatarUrl()).isEqualTo(deliveryUrl);
        assertThat(user.isAvatarCustomized()).isTrue();
        assertThat(user.getAggregateVersion()).isEqualTo(versionBefore + 1);

        // Updating with exact same values should be idempotent and not increment version
        user.updateMediaAvatar(mediaAssetId, deliveryUrl);
        assertThat(user.getAggregateVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName(
            "Cập nhật avatar bằng URL-only xóa bỏ Media reference về null"
    )
    void shouldClearMediaAssetIdWhenUpdatingUrlOnlyAvatar() {
        User user = createLocalUser();
        UUID mediaAssetId = UUID.randomUUID();
        user.updateMediaAvatar(mediaAssetId, "/media/assets/" + mediaAssetId + "/content");

        long versionBefore = user.getAggregateVersion();

        user.updateAvatarUrl("https://example.com/legacy.png");

        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isEqualTo("https://example.com/legacy.png");
        assertThat(user.isAvatarCustomized()).isTrue();
        assertThat(user.getAggregateVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName(
            "Xóa avatar xóa cả Media reference và URL"
    )
    void shouldClearBothMediaAssetIdAndUrlWhenRemovingAvatar() {
        User user = createLocalUser();
        UUID mediaAssetId = UUID.randomUUID();
        user.updateMediaAvatar(mediaAssetId, "/media/assets/" + mediaAssetId + "/content");

        long versionBefore = user.getAggregateVersion();

        user.removeAvatar();

        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.isAvatarCustomized()).isTrue();
        assertThat(user.getAggregateVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName(
            "updateMediaAvatar từ chối null mediaAssetId hoặc URL rỗng"
    )
    void shouldRejectInvalidArgumentsInUpdateMediaAvatar() {
        User user = createLocalUser();
        UUID mediaAssetId = UUID.randomUUID();

        assertThatThrownBy(() -> user.updateMediaAvatar(null, "/media/assets/1/content"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> user.updateMediaAvatar(mediaAssetId, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> user.updateMediaAvatar(mediaAssetId, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "Rehydrate hỗ trợ cả có avatarMediaAssetId và null"
    )
    void shouldRehydrateUserWithAndWithoutMediaAssetId() {
        UUID mediaAssetId = UUID.randomUUID();

        User userWithMedia = User.rehydrate(
                USER_ID,
                "athena@example.com",
                "$2a$10$hashedPassword",
                "Athena",
                mediaAssetId,
                "/media/assets/" + mediaAssetId + "/content",
                true,
                "Bio",
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                3L,
                NOW
        );

        assertThat(userWithMedia.getAvatarMediaAssetId()).isEqualTo(mediaAssetId);
        assertThat(userWithMedia.getAvatarUrl()).isEqualTo("/media/assets/" + mediaAssetId + "/content");

        User userWithoutMedia = User.rehydrate(
                USER_ID,
                "athena@example.com",
                "$2a$10$hashedPassword",
                "Athena",
                null,
                "https://example.com/legacy.png",
                true,
                "Bio",
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                3L,
                NOW
        );

        assertThat(userWithoutMedia.getAvatarMediaAssetId()).isNull();
        assertThat(userWithoutMedia.getAvatarUrl()).isEqualTo("https://example.com/legacy.png");
    }

    private User createLocalUser() {
        return User.createLocal(
                USER_ID,
                new Email(
                        "athena@example.com"
                ),
                "$2a$10$hashedPassword",
                "Athena",
                NOW
        );
    }
}