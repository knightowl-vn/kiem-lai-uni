package com.universe.identity.application.profile;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserRole;
import com.universe.identity.domain.UserStatus;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateAvatarServiceTest {

    private static final String USER_EMAIL = "athena@example.com";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEDIA_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private MediaContract mediaContract;

    private UpdateAvatarService service;

    @BeforeEach
    void setUp() {
        service = new UpdateAvatarService(userRepository, mediaContract);
    }

    private User createLocalUserWithNoMediaAvatar() {
        return User.rehydrate(
                USER_ID,
                USER_EMAIL,
                "$2a$10$hash",
                "Athena",
                null,
                null,
                false,
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
                "https://lh3.googleusercontent.com/avatar.png",
                false,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.GOOGLE,
                "google-sub",
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
                "https://res.cloudinary.com/kiemlai/avatars/user1.jpg",
                true,
                null,
                UserStatus.ACTIVE,
                UserRole.USER,
                AuthProvider.LOCAL,
                null,
                2L,
                NOW
        );
    }

    private User createUserWithExistingMediaAvatar(UUID assetId) {
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
                2L,
                NOW
        );
    }

    @Test
    @DisplayName("first custom avatar upload calls MediaContract.uploadAsset and updates user")
    void shouldUploadFirstCustomAvatarViaUploadAsset() {
        User user = createLocalUserWithNoMediaAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        UploadMediaAssetResponseDTO responseDTO = new UploadMediaAssetResponseDTO(
                MEDIA_ASSET_ID
        );
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenReturn(responseDTO);

        byte[] content = "valid-avatar-image-data".getBytes(StandardCharsets.UTF_8);
        service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "profile.png"
        );

        ArgumentCaptor<UploadMediaAssetRequestDTO> requestCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(requestCaptor.capture());
        verify(mediaContract, never()).uploadVersion(any());

        UploadMediaAssetRequestDTO capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.mimeType()).isEqualTo("image/png");
        assertThat(capturedRequest.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(capturedRequest.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(capturedRequest.originalFilename()).isEqualTo("profile.png");
        assertThat(capturedRequest.sizeBytes()).isEqualTo(content.length);

        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        assertThat(user.isAvatarCustomized()).isTrue();

        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("uploading custom avatar when user has Google/external avatar creates new Media asset")
    void shouldUploadNewAssetWhenReplacingGoogleAvatar() {
        User user = createUserWithGoogleAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        UploadMediaAssetResponseDTO responseDTO = new UploadMediaAssetResponseDTO(
                MEDIA_ASSET_ID
        );
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenReturn(responseDTO);

        byte[] content = "avatar-bytes".getBytes(StandardCharsets.UTF_8);
        service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/jpeg",
                "google-replacement.jpg"
        );

        verify(mediaContract).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());

        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        assertThat(user.isAvatarCustomized()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("uploading custom avatar when user has legacy Cloudinary avatar creates new Media asset")
    void shouldUploadNewAssetWhenReplacingLegacyCloudinaryAvatar() {
        User user = createUserWithLegacyCloudinaryAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        UploadMediaAssetResponseDTO responseDTO = new UploadMediaAssetResponseDTO(
                MEDIA_ASSET_ID
        );
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenReturn(responseDTO);

        byte[] content = "avatar-bytes".getBytes(StandardCharsets.UTF_8);
        service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/webp",
                "legacy-replacement.webp"
        );

        verify(mediaContract).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());

        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("uploading new avatar when user already has Media avatar calls uploadVersion and never uploadAsset")
    void shouldUploadVersionWhenUserAlreadyHasMediaAvatar() {
        User user = createUserWithExistingMediaAvatar(MEDIA_ASSET_ID);
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        UploadMediaAssetVersionResponseDTO versionResponse = new UploadMediaAssetVersionResponseDTO(
                MEDIA_ASSET_ID,
                2
        );
        when(mediaContract.uploadVersion(any(UploadMediaAssetVersionRequestDTO.class))).thenReturn(versionResponse);

        long initialAggregateVersion = user.getAggregateVersion();

        byte[] content = "new-avatar-version-bytes".getBytes(StandardCharsets.UTF_8);
        service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "avatar-v2.png"
        );

        ArgumentCaptor<UploadMediaAssetVersionRequestDTO> captor = ArgumentCaptor.forClass(UploadMediaAssetVersionRequestDTO.class);
        verify(mediaContract).uploadVersion(captor.capture());
        verify(mediaContract, never()).uploadAsset(any());

        UploadMediaAssetVersionRequestDTO capturedRequest = captor.getValue();
        assertThat(capturedRequest.assetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(capturedRequest.mimeType()).isEqualTo("image/png");
        assertThat(capturedRequest.originalFilename()).isEqualTo("avatar-v2.png");
        assertThat(capturedRequest.sizeBytes()).isEqualTo(content.length);

        // Media asset ID and URL remain stable, aggregateVersion unchanged, zero Identity saves performed
        assertThat(user.getAvatarMediaAssetId()).isEqualTo(MEDIA_ASSET_ID);
        assertThat(user.getAvatarUrl()).isEqualTo("/media/assets/" + MEDIA_ASSET_ID + "/content");
        assertThat(user.getAggregateVersion()).isEqualTo(initialAggregateVersion);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("declared size >2 MB is rejected before spooling or upload")
    void shouldRejectDeclaredSizeOver2Mb() {
        long declaredSize = 2L * 1024 * 1024 + 1;
        byte[] content = "dummy".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                declaredSize,
                "image/png",
                "large.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ảnh đại diện không được vượt quá 2 MB.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("actual stream size >2 MB is rejected during spooling")
    void shouldRejectActualStreamSizeOver2Mb() {
        // Stream with 2 MB + 1 byte
        byte[] chunk = new byte[8192];
        int chunkCount = (2 * 1024 * 1024 / 8192) + 1;
        InputStream oversizedStream = new InputStream() {
            private int chunksSent = 0;
            private int pos = 0;

            @Override
            public int read() {
                if (chunksSent >= chunkCount) return -1;
                pos++;
                if (pos >= 8192) {
                    pos = 0;
                    chunksSent++;
                }
                return 'A';
            }
        };

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                oversizedStream,
                1024, // declared small but stream is large
                "image/png",
                "large.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ảnh đại diện không được vượt quá 2 MB.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("empty stream or declared size <= 0 is rejected")
    void shouldRejectEmptyStream() {
        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(new byte[0]),
                0,
                "image/png",
                "empty.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vui lòng chọn một ảnh đại diện.");

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(new byte[0]),
                10,
                "image/png",
                "empty.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vui lòng chọn một ảnh đại diện.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("unsupported MIME type is rejected")
    void shouldRejectUnsupportedMimeType() {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/gif",
                "anim.gif"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "application/pdf",
                "doc.pdf"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("new-asset Media upload failure does not save User")
    void shouldNotSaveUserWhenMediaUploadFails() {
        User user = createLocalUserWithNoMediaAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));
        when(mediaContract.uploadAsset(any())).thenThrow(new RuntimeException("Media storage unreachable"));

        byte[] content = "valid-data".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "test.png"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Media storage unreachable");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("new-asset DB save failure compensates by deleting the newly created Media asset")
    void shouldCompensateWhenNewAssetDbSaveFails() {
        User user = createLocalUserWithNoMediaAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        UploadMediaAssetResponseDTO responseDTO = new UploadMediaAssetResponseDTO(
                MEDIA_ASSET_ID
        );
        when(mediaContract.uploadAsset(any())).thenReturn(responseDTO);
        doThrow(new RuntimeException("Database error on user save")).when(userRepository).save(any());

        byte[] content = "valid-data".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "test.png"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database error on user save");

        verify(mediaContract).delete(MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("when compensation delete also fails, it is attached as suppressed exception")
    void shouldSuppressCompensationExceptionWhenDeleteFails() {
        User user = createLocalUserWithNoMediaAvatar();
        when(userRepository.findByEmail(new Email(USER_EMAIL))).thenReturn(Optional.of(user));

        UploadMediaAssetResponseDTO responseDTO = new UploadMediaAssetResponseDTO(
                MEDIA_ASSET_ID
        );
        when(mediaContract.uploadAsset(any())).thenReturn(responseDTO);
        doThrow(new RuntimeException("Database error on user save")).when(userRepository).save(any());
        doThrow(new RuntimeException("Compensation delete failed")).when(mediaContract).delete(MEDIA_ASSET_ID);

        byte[] content = "valid-data".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "test.png"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database error on user save")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).hasSize(1);
                    assertThat(ex.getSuppressed()[0]).hasMessage("Compensation delete failed");
                });

        verify(mediaContract).delete(MEDIA_ASSET_ID);
    }

    @Test
    @DisplayName("stream read I/O failure maps to IllegalStateException with user-friendly message")
    void shouldMapStreamIoFailureToIllegalStateException() {
        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("Disk read error");
            }
        };

        assertThatThrownBy(() -> service.execute(
                USER_EMAIL,
                failingStream,
                100,
                "image/png",
                "test.png"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Không thể ghi file tạm khi upload ảnh đại diện.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws IllegalStateException when user is not found")
    void shouldThrowWhenUserNotFound() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.execute(
                "unknown@example.com",
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "test.png"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Không tìm thấy tài khoản đang đăng nhập.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());
    }
}
