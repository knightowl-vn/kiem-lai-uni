package com.universe.novel.application.profile;

import com.universe.novel.application.ports.NovelCoverStoragePort;
import com.universe.novel.application.ports.NovelProfileRepositoryPort;
import com.universe.novel.contracts.dto.profile.NovelProfileDTO;
import com.universe.shared.time.ClockPort;

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
    private NovelCoverStoragePort
            novelCoverStoragePort;

    @Mock
    private ClockPort
            clockPort;

    private UpdateNovelProfileUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateNovelProfileUseCase(
                novelProfileRepositoryPort,
                novelCoverStoragePort,
                clockPort
        );
    }

    @Test
    @DisplayName("Không chọn ảnh mới (coverUpload == null) -> Giữ nguyên coverImageUrl hiện có")
    void shouldPreserveExistingCoverUrlWhenNoNewCoverUploaded() {
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
                "COMPLETED",
                NOW
        )).thenReturn(updatedResult);

        NovelProfileDTO result = useCase.execute(command);

        assertThat(result.coverImageUrl())
                .isEqualTo("https://example.com/existing-cover.jpg");

        verify(novelProfileRepositoryPort).findBySlug("kiem-lai");
        verify(novelCoverStoragePort, never()).upload(any(), any());
        verify(novelProfileRepositoryPort).update(
                "kiem-lai",
                "Kiếm Lai (Đại Kết Cục)",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả mới",
                "https://example.com/existing-cover.jpg",
                "COMPLETED",
                NOW
        );
    }

    @Test
    @DisplayName("Chọn ảnh mới hợp lệ -> Gọi NovelCoverStoragePort một lần và cập nhật URL mới")
    void shouldUploadNewCoverAndPersistNewUrlWhenValidCoverProvided() {
        NovelCoverUpload coverUpload = new NovelCoverUpload(
                "new-cover.png",
                "image/png",
                VALID_IMAGE_BYTES
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
                "https://example.com/old-cover.jpg",
                "ONGOING",
                CREATED_AT,
                CREATED_AT
        );

        String newUploadedUrl =
                "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/novel/covers/kiem-lai/new-uuid.webp";

        NovelProfileDTO updatedResult = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                newUploadedUrl,
                "ONGOING",
                CREATED_AT,
                NOW
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(existingProfile));
        when(novelCoverStoragePort.upload("kiem-lai", coverUpload))
                .thenReturn(newUploadedUrl);
        when(clockPort.now()).thenReturn(NOW);
        when(novelProfileRepositoryPort.update(
                "kiem-lai",
                "Kiếm Lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                newUploadedUrl,
                "ONGOING",
                NOW
        )).thenReturn(updatedResult);

        NovelProfileDTO result = useCase.execute(command);

        assertThat(result.coverImageUrl()).isEqualTo(newUploadedUrl);
        verify(novelCoverStoragePort).upload("kiem-lai", coverUpload);
        verify(novelProfileRepositoryPort).update(
                "kiem-lai",
                "Kiếm Lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả",
                newUploadedUrl,
                "ONGOING",
                NOW
        );
    }

    @Test
    @DisplayName("Tiêu đề rỗng + có ảnh hợp lệ -> Validate fail trước, NovelCoverStoragePort TUYỆT ĐỐI KHÔNG được gọi")
    void shouldNeverCallStorageWhenTitleIsBlankEvenWithValidCover() {
        NovelCoverUpload validCover = new NovelCoverUpload(
                "valid-cover.jpg",
                "image/jpeg",
                VALID_IMAGE_BYTES
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "   ",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                validCover
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tiêu đề tiểu thuyết không được để trống");

        verifyNoInteractions(novelCoverStoragePort);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Ảnh không hợp lệ (sai định dạng) -> Validate fail trước, NovelCoverStoragePort TUYỆT ĐỐI KHÔNG được gọi")
    void shouldNeverCallStorageWhenCoverFileIsInvalid() {
        NovelCoverUpload invalidCover = new NovelCoverUpload(
                "document.pdf",
                "application/pdf",
                VALID_IMAGE_BYTES
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                invalidCover
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP");

        verifyNoInteractions(novelCoverStoragePort);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Ảnh vượt quá 5 MB -> Validate fail trước, NovelCoverStoragePort TUYỆT ĐỐI KHÔNG được gọi")
    void shouldNeverCallStorageWhenCoverFileSizeExceeds5MB() {
        byte[] oversizedBytes = new byte[5 * 1024 * 1024 + 1];
        NovelCoverUpload oversizedCover = new NovelCoverUpload(
                "huge.png",
                "image/png",
                oversizedBytes
        );

        UpdateNovelProfileCommand command = new UpdateNovelProfileCommand(
                "Kiếm Lai",
                "Phong Hỏa",
                "Mô tả",
                "ONGOING",
                oversizedCover
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ảnh bìa không được vượt quá 5 MB");

        verifyNoInteractions(novelCoverStoragePort);
        verifyNoInteractions(novelProfileRepositoryPort);
    }

    @Test
    @DisplayName("Tác giả / Mô tả / Trạng thái không hợp lệ -> Validate fail trước, NovelCoverStoragePort TUYỆT ĐỐI KHÔNG được gọi")
    void shouldNeverCallStorageWhenOtherFieldsAreInvalid() {
        NovelCoverUpload validCover = new NovelCoverUpload(
                "valid.webp",
                "image/webp",
                VALID_IMAGE_BYTES
        );

        // Tác giả rỗng
        assertThatThrownBy(() -> useCase.execute(new UpdateNovelProfileCommand(
                "Kiếm Lai", "  ", "Mô tả", "ONGOING", validCover
        ))).isInstanceOf(IllegalArgumentException.class);

        // Mô tả rỗng
        assertThatThrownBy(() -> useCase.execute(new UpdateNovelProfileCommand(
                "Kiếm Lai", "Phong Hỏa", "", "ONGOING", validCover
        ))).isInstanceOf(IllegalArgumentException.class);

        // Trạng thái không hợp lệ
        assertThatThrownBy(() -> useCase.execute(new UpdateNovelProfileCommand(
                "Kiếm Lai", "Phong Hỏa", "Mô tả", "INVALID_STATUS", validCover
        ))).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(novelCoverStoragePort);
        verifyNoInteractions(novelProfileRepositoryPort);
    }
}
