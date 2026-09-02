package com.universe.novel.application.profile;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.novel.application.ports.NovelProfileRepositoryPort;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.shared.time.ClockPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateNovelProfileUseCaseTest {

    private static final UUID PROFILE_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-22T11:30:00Z");

    private static final Instant NOW =
            Instant.parse("2026-08-22T18:00:00Z");

    private static final byte[] VALID_IMAGE_BYTES =
            new byte[]{1, 2, 3, 4, 5};

    @Mock
    private NovelProfileRepositoryPort
            novelProfileRepositoryPort;

    @Mock
    private MediaContract
            mediaContract;

    @Mock
    private ClockPort
            clockPort;

    private UpdateNovelProfileUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateNovelProfileUseCase(
                novelProfileRepositoryPort,
                mediaContract,
                clockPort
        );
    }

    @Test
    @DisplayName("Không chọn ảnh mới (coverUpload == null) -> Không gọi MediaContract, giữ nguyên coverImageUrl và coverMediaAssetId")
    void shouldPreserveExistingCoverWhenNoNewCoverUploaded() {
        UUID existingMediaAssetId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai (Đại Kết Cục)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "COMPLETED",
                null
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả cũ",
                "https://example.com/existing-cover.jpg",
                existingMediaAssetId,
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        NovelProfileDTO updatedResult = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai (Đại Kết Cục)",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "https://example.com/existing-cover.jpg",
                existingMediaAssetId,
                "COMPLETED",
                CREATED_AT,
                NOW
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(
                "kiem-lai",
                "Kiếm Lai (Đại Kết Cục)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "https://example.com/existing-cover.jpg",
                existingMediaAssetId,
                "COMPLETED",
                NOW
        )).thenReturn(updatedResult);

        NovelProfileDTO result = useCase.execute(command);

        assertThat(result.coverImageUrl())
                .isEqualTo("https://example.com/existing-cover.jpg");
        assertThat(result.coverMediaAssetId())
                .isEqualTo(existingMediaAssetId);

        verify(novelProfileRepositoryPort).findBySlug("kiem-lai");
        verifyNoInteractions(mediaContract);
        verify(novelProfileRepositoryPort).update(
                "kiem-lai",
                "Kiếm Lai (Đại Kết Cục)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "https://example.com/existing-cover.jpg",
                existingMediaAssetId,
                "COMPLETED",
                NOW
        );
    }

    @Test
    @DisplayName("Upload ảnh mới khi coverMediaAssetId == null (ảnh đầu tiên qua Media) -> Gọi uploadAsset, lưu assetId mới, bảo toàn raw legacy URL")
    void shouldUploadInitialMediaAssetAndPersistReturnedAssetId() {
        UUID newAssetId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        InputStream in = new ByteArrayInputStream(VALID_IMAGE_BYTES);

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                in,
                VALID_IMAGE_BYTES.length,
                "image/png",
                "new-cover.png"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                "ONGOING",
                coverUpload
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                "https://example.com/legacy-cover.jpg",
                null, // Chưa có media asset
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        NovelProfileDTO updatedResult = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                "https://example.com/legacy-cover.jpg",
                newAssetId,
                "ONGOING",
                CREATED_AT,
                NOW
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(newAssetId));
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(
                eq("kiem-lai"),
                eq("Kiếm Lai"),
                eq("Phong Hỏa Hí Chư Hầu"),
                eq("Mô tả"),
                eq("https://example.com/legacy-cover.jpg"),
                eq(newAssetId),
                eq("ONGOING"),
                eq(NOW)
        )).thenReturn(updatedResult);

        NovelProfileDTO result = useCase.execute(command);

        assertThat(result.coverMediaAssetId()).isEqualTo(newAssetId);
        assertThat(result.coverImageUrl()).isEqualTo("https://example.com/legacy-cover.jpg");

        ArgumentCaptor<UploadMediaAssetRequestDTO> captor =
                ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(captor.capture());

        UploadMediaAssetRequestDTO capturedReq = captor.getValue();
        assertThat(capturedReq.content()).isSameAs(in);
        assertThat(capturedReq.sizeBytes()).isEqualTo((long) VALID_IMAGE_BYTES.length);
        assertThat(capturedReq.mimeType()).isEqualTo("image/png");
        assertThat(capturedReq.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(capturedReq.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(capturedReq.originalFilename()).isEqualTo("new-cover.png");

        verify(mediaContract, never()).uploadVersion(any());
        verify(novelProfileRepositoryPort).update(
                "kiem-lai",
                "Kiếm Lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                "https://example.com/legacy-cover.jpg",
                newAssetId,
                "ONGOING",
                NOW
        );
    }

    @Test
    @DisplayName("Upload ảnh thay thế khi coverMediaAssetId != null -> Gọi uploadVersion với cùng assetId, không gọi uploadAsset")
    void shouldUploadReplacementVersionForExistingMediaAsset() {
        UUID existingAssetId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        InputStream in = new ByteArrayInputStream(VALID_IMAGE_BYTES);

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                in,
                VALID_IMAGE_BYTES.length,
                "image/webp",
                "cover-v2.webp"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai (Bản Mới)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "ONGOING",
                coverUpload
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả cũ",
                "https://example.com/legacy.jpg",
                existingAssetId,
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        NovelProfileDTO updatedResult = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai (Bản Mới)",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "https://example.com/legacy.jpg",
                existingAssetId,
                "ONGOING",
                CREATED_AT,
                NOW
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(mediaContract.uploadVersion(any(UploadMediaAssetVersionRequestDTO.class)))
                .thenReturn(new UploadMediaAssetVersionResponseDTO(existingAssetId, 2));
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(
                eq("kiem-lai"),
                eq("Kiếm Lai (Bản Mới)"),
                eq("Phong Hỏa Hí Chư Hầu"),
                eq("Mô tả mới"),
                eq("https://example.com/legacy.jpg"),
                eq(existingAssetId),
                eq("ONGOING"),
                eq(NOW)
        )).thenReturn(updatedResult);

        NovelProfileDTO result = useCase.execute(command);

        assertThat(result.coverMediaAssetId()).isEqualTo(existingAssetId);
        assertThat(result.coverImageUrl()).isEqualTo("https://example.com/legacy.jpg");

        ArgumentCaptor<UploadMediaAssetVersionRequestDTO> captor =
                ArgumentCaptor.forClass(UploadMediaAssetVersionRequestDTO.class);
        verify(mediaContract).uploadVersion(captor.capture());

        UploadMediaAssetVersionRequestDTO capturedReq = captor.getValue();
        assertThat(capturedReq.assetId()).isEqualTo(existingAssetId);
        assertThat(capturedReq.content()).isSameAs(in);
        assertThat(capturedReq.sizeBytes()).isEqualTo((long) VALID_IMAGE_BYTES.length);
        assertThat(capturedReq.mimeType()).isEqualTo("image/webp");
        assertThat(capturedReq.originalFilename()).isEqualTo("cover-v2.webp");

        verify(mediaContract, never()).uploadAsset(any());
        verify(novelProfileRepositoryPort).update(
                "kiem-lai",
                "Kiếm Lai (Bản Mới)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "https://example.com/legacy.jpg",
                existingAssetId,
                "ONGOING",
                NOW
        );
    }

    @Test
    @DisplayName("Upload ảnh đầu tiên thành công nhưng lưu DB thất bại -> Compensation gọi MediaContract.delete(newAssetId)")
    void shouldCompensateByDeletingMediaAssetWhenInitialPersistenceFails() {
        UUID newAssetId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        InputStream in = new ByteArrayInputStream(VALID_IMAGE_BYTES);

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                in,
                VALID_IMAGE_BYTES.length,
                "image/jpeg",
                "cover.jpg"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                coverUpload
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả",
                "https://example.com/legacy.jpg",
                null,
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(newAssetId));
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Database connection timeout"));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database connection timeout");

        verify(mediaContract).uploadAsset(any());
        verify(mediaContract).delete(newAssetId);
    }

    @Test
    @DisplayName("Upload ảnh đầu tiên: persistence thất bại và compensation delete cũng thất bại -> Gắn compensation exception vào suppressed")
    void shouldAttachSuppressedExceptionWhenCompensationFails() {
        UUID newAssetId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        InputStream in = new ByteArrayInputStream(VALID_IMAGE_BYTES);

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                in,
                VALID_IMAGE_BYTES.length,
                "image/jpeg",
                "cover.jpg"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                coverUpload
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả",
                null,
                null,
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(newAssetId));
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Primary DB failure"));
        org.mockito.Mockito.doThrow(new RuntimeException("Compensation delete failure"))
                .when(mediaContract).delete(newAssetId);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Primary DB failure")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).hasSize(1);
                    assertThat(ex.getSuppressed()[0].getMessage()).isEqualTo("Compensation delete failure");
                });

        verify(mediaContract).delete(newAssetId);
    }

    @Test
    @DisplayName("Upload version thay thế thành công nhưng lưu DB thất bại -> KHÔNG gọi MediaContract.delete")
    void shouldNotDeleteExistingAssetWhenReplacementPersistenceFails() {
        UUID existingAssetId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        InputStream in = new ByteArrayInputStream(VALID_IMAGE_BYTES);

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                in,
                VALID_IMAGE_BYTES.length,
                "image/png",
                "cover-v2.png"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                coverUpload
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả",
                "https://example.com/legacy.jpg",
                existingAssetId,
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(mediaContract.uploadVersion(any(UploadMediaAssetVersionRequestDTO.class)))
                .thenReturn(new UploadMediaAssetVersionResponseDTO(existingAssetId, 2));
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Database error on update"));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error on update");

        verify(mediaContract).uploadVersion(any());
        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("Media upload ném exception -> Không gọi novelProfileRepositoryPort.update")
    void shouldNotUpdateRepositoryWhenMediaUploadFails() {
        InputStream in = new ByteArrayInputStream(VALID_IMAGE_BYTES);

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                in,
                VALID_IMAGE_BYTES.length,
                "image/png",
                "cover.png"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                coverUpload
        );

        NovelProfileDTO existingProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả",
                null,
                null,
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenThrow(new RuntimeException("Media storage unavailable"));

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Media storage unavailable");

        verify(novelProfileRepositoryPort, never()).update(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Validation thất bại (tiêu đề rỗng) -> Ném IllegalArgumentException trước khi tương tác Media hoặc DB")
    void shouldFailValidationBeforeAnySideEffectsWhenTitleIsBlank() {
        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "   ",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                null
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tiêu đề tiểu thuyết không được để trống.");

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Validation file thất bại (size > 5MB) -> Ném IllegalArgumentException trước khi tương tác Media hoặc DB")
    void shouldFailValidationWhenCoverFileSizeExceedsLimit() {
        NovelCoverUpload oversizedUpload = new NovelCoverUpload(
                new ByteArrayInputStream(VALID_IMAGE_BYTES),
                5L * 1024 * 1024 + 1,
                "image/jpeg",
                "large.jpg"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                oversizedUpload
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ảnh bìa không được vượt quá 5 MB.");

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Validation file thất bại (MIME type không hợp lệ) -> Ném IllegalArgumentException trước khi tương tác")
    void shouldFailValidationWhenMimeTypeIsUnsupported() {
        NovelCoverUpload invalidMimeUpload = new NovelCoverUpload(
                new ByteArrayInputStream(VALID_IMAGE_BYTES),
                100L,
                "image/gif",
                "animated.gif"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                invalidMimeUpload
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP.");

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Validation file thất bại (Extension không hợp lệ) -> Ném IllegalArgumentException trước khi tương tác")
    void shouldFailValidationWhenExtensionIsUnsupported() {
        NovelCoverUpload invalidExtUpload = new NovelCoverUpload(
                new ByteArrayInputStream(VALID_IMAGE_BYTES),
                100L,
                "image/png",
                "file.bmp"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                invalidExtUpload
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP.");

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Validation file thất bại (file rỗng size = 0) -> Ném IllegalArgumentException trước khi tương tác")
    void shouldFailValidationWhenCoverFileIsEmpty() {
        NovelCoverUpload emptyUpload = new NovelCoverUpload(
                new ByteArrayInputStream(new byte[0]),
                0L,
                "image/jpeg",
                "empty.jpg"
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                emptyUpload
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vui lòng chọn file ảnh bìa hợp lệ.");

        verifyNoInteractions(mediaContract);
        verifyNoInteractions(novelProfileRepositoryPort);
    }
}
