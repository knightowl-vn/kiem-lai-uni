package com.universe.identity.application.profile;

import com.universe.identity.application.ports.LegacyAvatarStoragePort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteAvatarServiceTest {

    private static final String USER_EMAIL = "athena@example.com";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private LegacyAvatarStoragePort legacyAvatarStoragePort;

    @Mock
    private MediaContract mediaContract;

    private DeleteAvatarService service;

    @BeforeEach
    void setUp() {
        service = new DeleteAvatarService(userRepository, legacyAvatarStoragePort, mediaContract);
    }

    private User createUserWithMediaAvatar(UUID assetId) {
        return User.rehydrate(
                USER_ID,
                USER_EMAIL,
                "$2a$10$hash",
                "Athena",
                assetId,
                "/media/assets/" + assetId + "/content",
                true,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                1L,
                NOW
        );
    }

    private User createUserWithLegacyCloudinaryAvatar() {
        return User.rehydrate(
                USER_ID,
                USER_EMAIL,
                "$2a$10$hash",
                "Athena",
                null,
                "https://res.cloudinary.com/kiemlai/image/upload/v12345/kiemlai/avatars/" + USER_ID + ".jpg",
                true,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                1L,
                NOW
        );
    }

    private User createUserWithGoogleAvatar() {
        return User.rehydrate(
                USER_ID,
                USER_EMAIL,
                null,
                "Athena",
                null,
                "https://lh3.googleusercontent.com/a/ACg8ocI-sample-avatar.png",
                false,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.GOOGLE,
                "google-sub-123",
                1L,
                NOW
        );
    }

    private User createUserWithNoAvatar(boolean customized) {
        return User.rehydrate(
                USER_ID,
                USER_EMAIL,
                "$2a$10$hash",
                "Athena",
                null,
                null,
                customized,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                1L,
                NOW
        );
    }

    private MediaAssetDetailDTO createMediaDetail(UUID assetId, MediaAssetStatusDTO status) {
        return new MediaAssetDetailDTO(
                assetId,
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                status,
                1,
                NOW,
                NOW,
                null
        );
    }

    @Test
    @DisplayName("ACTIVE Media avatar calls Media delete, clears avatar on User, and saves")
    void shouldDeleteActiveMediaAvatar() {
        User user = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(createMediaDetail(MEDIA_ASSET_ID, MediaAssetStatusDTO.ACTIVE)));

        service.execute(USER_EMAIL);

        verify(mediaContract).delete(MEDIA_ASSET_ID);
        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("ARCHIVED Media avatar calls Media delete, clears avatar on User, and saves")
    void shouldDeleteArchivedMediaAvatar() {
        User user = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(createMediaDetail(MEDIA_ASSET_ID, MediaAssetStatusDTO.ARCHIVED)));

        service.execute(USER_EMAIL);

        verify(mediaContract).delete(MEDIA_ASSET_ID);
        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("DELETED Media avatar skips Media delete call (retry-safe), clears avatar on User, and saves")
    void shouldSkipMediaDeleteWhenAlreadyDeleted() {
        User user = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(createMediaDetail(MEDIA_ASSET_ID, MediaAssetStatusDTO.DELETED)));

        service.execute(USER_EMAIL);

        verify(mediaContract, never()).delete(any());
        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("Media asset not found fails and preserves Identity state without saving")
    void shouldPreserveIdentityStateWhenMediaAssetNotFound() {
        User user = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(USER_EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không tìm thấy thông tin Media asset");

        verify(mediaContract, never()).delete(any());
        verify(userRepository, never()).save(any());
        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
    }

    @Test
    @DisplayName("Media delete failure preserves Identity state without saving")
    void shouldPreserveIdentityStateWhenMediaDeleteFails() {
        User user = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(createMediaDetail(MEDIA_ASSET_ID, MediaAssetStatusDTO.ACTIVE)));
        doThrow(new RuntimeException("Media delete error")).when(mediaContract).delete(MEDIA_ASSET_ID);

        assertThatThrownBy(() -> service.execute(USER_EMAIL))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Media delete error");

        verify(userRepository, never()).save(any());
        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
    }

    @Test
    @DisplayName("full retry scenario: Run 1 Media delete succeeds + DB save fails; Run 2 sees DELETED + skips Media delete + DB save succeeds")
    void shouldHandleFullRetryScenario() {
        // --- Run 1: Media is ACTIVE, Media delete succeeds, DB save fails ---
        User userRun1 = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(userRun1));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(createMediaDetail(MEDIA_ASSET_ID, MediaAssetStatusDTO.ACTIVE)));
        doThrow(new RuntimeException("Database timeout on user save")).when(userRepository).save(userRun1);

        assertThatThrownBy(() -> service.execute(USER_EMAIL))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database timeout on user save");

        verify(mediaContract).delete(MEDIA_ASSET_ID);

        // --- Run 2: Retry with fresh User loaded from DB (still has avatarMediaAssetId), Media reports DELETED ---
        User userRun2 = createUserWithMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(userRun2));
        when(mediaContract.getAssetDetail(MEDIA_ASSET_ID))
                .thenReturn(Optional.of(createMediaDetail(MEDIA_ASSET_ID, MediaAssetStatusDTO.DELETED)));
        // DB save succeeds on retry
        org.mockito.Mockito.reset(userRepository);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(userRun2));

        service.execute(USER_EMAIL);

        // Verify delete was NOT called again during Run 2 (total invocations on mediaContract.delete is still 1 from Run 1)
        verify(mediaContract, times(1)).delete(any());
        assertThat(userRun2.getAvatarMediaAssetId()).isNull();
        assertThat(userRun2.getAvatarUrl()).isNull();
        assertThat(userRun2.isAvatarCustomized()).isTrue();
        verify(userRepository).save(userRun2);
    }

    @Test
    @DisplayName("legacy Cloudinary avatar calls LegacyAvatarStoragePort.deleteAvatar and saves User")
    void shouldDeleteLegacyCloudinaryAvatar() {
        User user = createUserWithLegacyCloudinaryAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(legacyAvatarStoragePort.isLegacyAvatarUrl(user.getAvatarUrl())).thenReturn(true);

        service.execute(USER_EMAIL);

        verify(legacyAvatarStoragePort).deleteAvatar(USER_ID);
        verify(mediaContract, never()).getAssetDetail(any());
        verify(mediaContract, never()).delete(any());
        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("legacy Cloudinary delete failure preserves Identity state without saving")
    void shouldPreserveIdentityStateWhenCloudinaryDeleteFails() {
        User user = createUserWithLegacyCloudinaryAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(legacyAvatarStoragePort.isLegacyAvatarUrl(user.getAvatarUrl())).thenReturn(true);
        doThrow(new IllegalStateException("Cloudinary unreachable")).when(legacyAvatarStoragePort).deleteAvatar(USER_ID);

        assertThatThrownBy(() -> service.execute(USER_EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloudinary unreachable");

        verify(userRepository, never()).save(any());
        assertThat(user.getAvatarUrl()).isNotNull();
    }

    @Test
    @DisplayName("Google/external avatar calls neither Cloudinary nor Media, clears avatar, and saves User")
    void shouldClearGoogleExternalAvatarWithoutCallingCloudinaryOrMedia() {
        User user = createUserWithGoogleAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(legacyAvatarStoragePort.isLegacyAvatarUrl(user.getAvatarUrl())).thenReturn(false);

        service.execute(USER_EMAIL);

        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        verify(mediaContract, never()).getAssetDetail(any());
        verify(mediaContract, never()).delete(any());
        assertThat(user.getAvatarMediaAssetId()).isNull();
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("no avatar with avatarCustomized=false marks customized and saves User")
    void shouldMarkCustomizedAndSaveWhenNoAvatarAndNotCustomized() {
        User user = createUserWithNoAvatar(false);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        service.execute(USER_EMAIL);

        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        verify(mediaContract, never()).getAssetDetail(any());
        verify(mediaContract, never()).delete(any());
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("already removed/customized avatar avoids unnecessary database save")
    void shouldNotSaveWhenAvatarAlreadyRemovedAndCustomized() {
        User user = createUserWithNoAvatar(true);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        service.execute(USER_EMAIL);

        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        verify(mediaContract, never()).getAssetDetail(any());
        verify(mediaContract, never()).delete(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws IllegalStateException when user is not found")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute("unknown@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Không tìm thấy tài khoản đang đăng nhập.");

        verify(legacyAvatarStoragePort, never()).deleteAvatar(any());
        verify(mediaContract, never()).getAssetDetail(any());
        verify(userRepository, never()).save(any());
    }
}
