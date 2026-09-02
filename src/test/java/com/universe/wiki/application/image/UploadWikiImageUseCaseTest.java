package com.universe.wiki.application.image;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.ports.WikiImageRepositoryPort;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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
class UploadWikiImageUseCaseTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-02T12:00:00Z");

    @Mock
    private MediaContract mediaContract;

    @Mock
    private WikiImageRepositoryPort imageRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private UploadWikiImageUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new UploadWikiImageUseCase(
                mediaContract,
                imageRepositoryPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Upload ảnh Wiki hợp lệ gọi MediaContract.uploadAsset và lưu WikiImageAsset media-backed")
    void shouldUploadValidWikiImageThroughMediaContract() {
        byte[] content = "valid-image-bytes".getBytes(StandardCharsets.UTF_8);
        UUID createdAssetId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(clockPort.now()).thenReturn(FIXED_NOW);
        when(imageRepositoryPort.findByContentHash(any())).thenReturn(Optional.empty());
        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(createdAssetId));

        WikiImageUploadResult result = useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/webp",
                "test.webp"
        );

        assertThat(result.url()).isEqualTo("/media/assets/" + createdAssetId + "/content");
        assertThat(result.publicId()).isNull();

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor =
                ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());

        UploadMediaAssetRequestDTO uploadRequest = uploadCaptor.getValue();
        assertThat(uploadRequest.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(uploadRequest.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(uploadRequest.mimeType()).isEqualTo("image/webp");
        assertThat(uploadRequest.originalFilename()).isEqualTo("test.webp");
        assertThat(uploadRequest.sizeBytes()).isEqualTo((long) content.length);

        ArgumentCaptor<WikiImageAsset> assetCaptor =
                ArgumentCaptor.forClass(WikiImageAsset.class);
        verify(imageRepositoryPort).save(assetCaptor.capture());

        WikiImageAsset savedAsset = assetCaptor.getValue();
        assertThat(savedAsset.mediaAssetId()).isEqualTo(createdAssetId);
        assertThat(savedAsset.publicId()).isNull();
        assertThat(savedAsset.url()).isEqualTo("/media/assets/" + createdAssetId + "/content");
        assertThat(savedAsset.sourceContentType()).isEqualTo("image/webp");
        assertThat(savedAsset.sizeBytes()).isEqualTo((long) content.length);
        assertThat(savedAsset.createdAt()).isEqualTo(FIXED_NOW);

        verify(mediaContract, never()).uploadVersion(any());
    }

    @Test
    @DisplayName("Tái sử dụng bản ghi legacy khi content hash khớp và không gọi MediaContract")
    void shouldReuseLegacyImageOnContentHashMatchWithoutCallingMedia() {
        byte[] content = "legacy-image-content".getBytes(StandardCharsets.UTF_8);
        String expectedHash = calculateSha256(content);

        WikiImageAsset legacyAsset = new WikiImageAsset(
                UUID.randomUUID(),
                expectedHash,
                "https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp",
                "kiemlai/wiki/legacy-public-id",
                "image/webp",
                content.length,
                Instant.parse("2026-08-01T00:00:00Z")
        );

        when(imageRepositoryPort.findByContentHash(expectedHash))
                .thenReturn(Optional.of(legacyAsset));

        WikiImageUploadResult result = useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/webp",
                "legacy.webp"
        );

        assertThat(result.url()).isEqualTo("https://res.cloudinary.com/demo/image/upload/v1/kiemlai/wiki/legacy.webp");
        assertThat(result.publicId()).isEqualTo("kiemlai/wiki/legacy-public-id");

        verify(mediaContract, never()).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());
        verify(imageRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Tái sử dụng bản ghi Media-backed khi content hash khớp và không gọi MediaContract")
    void shouldReuseMediaBackedImageOnContentHashMatchWithoutCallingMedia() {
        byte[] content = "media-backed-content".getBytes(StandardCharsets.UTF_8);
        String expectedHash = calculateSha256(content);
        UUID existingAssetId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        WikiImageAsset mediaAsset = new WikiImageAsset(
                UUID.randomUUID(),
                expectedHash,
                "/media/assets/" + existingAssetId + "/content",
                null,
                existingAssetId,
                "image/png",
                content.length,
                Instant.parse("2026-08-15T00:00:00Z")
        );

        when(imageRepositoryPort.findByContentHash(expectedHash))
                .thenReturn(Optional.of(mediaAsset));

        WikiImageUploadResult result = useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "photo.png"
        );

        assertThat(result.url()).isEqualTo("/media/assets/" + existingAssetId + "/content");
        assertThat(result.publicId()).isNull();

        verify(mediaContract, never()).uploadAsset(any());
        verify(mediaContract, never()).uploadVersion(any());
        verify(imageRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Không lưu bản ghi Wiki khi Media upload thất bại")
    void shouldNotPersistWikiRowWhenMediaUploadFails() {
        byte[] content = "upload-fail-bytes".getBytes(StandardCharsets.UTF_8);

        when(imageRepositoryPort.findByContentHash(any())).thenReturn(Optional.empty());
        when(mediaContract.uploadAsset(any()))
                .thenThrow(new RuntimeException("Media storage I/O error"));

        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/jpeg",
                "sample.jpg"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Media storage I/O error");

        verify(imageRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Bù trừ (delete Media asset) khi lưu DB Wiki thất bại và bảo toàn ngoại lệ gốc")
    void shouldCompensateMediaAssetWhenWikiPersistenceFails() {
        byte[] content = "persist-fail-bytes".getBytes(StandardCharsets.UTF_8);
        UUID createdAssetId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        when(clockPort.now()).thenReturn(FIXED_NOW);
        when(imageRepositoryPort.findByContentHash(any())).thenReturn(Optional.empty());
        when(mediaContract.uploadAsset(any()))
                .thenReturn(new UploadMediaAssetResponseDTO(createdAssetId));

        doThrow(new RuntimeException("DB connection dropped"))
                .when(imageRepositoryPort).save(any(WikiImageAsset.class));

        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "diagram.png"
        ))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB connection dropped");

        verify(mediaContract).delete(createdAssetId);
    }

    @Test
    @DisplayName("Gắn ngoại lệ compensation vào suppressed khi bù trừ delete thất bại")
    void shouldAttachSuppressedWhenCompensationFails() {
        byte[] content = "suppressed-fail-bytes".getBytes(StandardCharsets.UTF_8);
        UUID createdAssetId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        when(clockPort.now()).thenReturn(FIXED_NOW);
        when(imageRepositoryPort.findByContentHash(any())).thenReturn(Optional.empty());
        when(mediaContract.uploadAsset(any()))
                .thenReturn(new UploadMediaAssetResponseDTO(createdAssetId));

        RuntimeException dbEx = new RuntimeException("DB constraint error");
        doThrow(dbEx).when(imageRepositoryPort).save(any(WikiImageAsset.class));

        RuntimeException deleteEx = new RuntimeException("Media delete failed");
        doThrow(deleteEx).when(mediaContract).delete(createdAssetId);

        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/webp",
                "art.webp"
        ))
                .isSameAs(dbEx)
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).hasSize(1);
                    assertThat(ex.getSuppressed()[0]).isSameAs(deleteEx);
                });
    }

    @Test
    @DisplayName("Từ chối stream rỗng")
    void shouldRejectEmptyStream() {
        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(new byte[0]),
                0,
                "image/png",
                "empty.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vui lòng chọn một ảnh Wiki.");

        verify(mediaContract, never()).uploadAsset(any());
    }

    @Test
    @DisplayName("Từ chối stream null")
    void shouldRejectNullStream() {
        assertThatThrownBy(() -> useCase.execute(
                (InputStream) null,
                0,
                "image/png",
                "test.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vui lòng chọn một ảnh Wiki.");
    }

    @Test
    @DisplayName("Từ chối loại file không hỗ trợ")
    void shouldRejectUnsupportedImageType() {
        byte[] content = new byte[]{1, 2, 3};
        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/svg+xml",
                "test.svg"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
    }

    @Test
    @DisplayName("Từ chối phần mở rộng không hợp lệ")
    void shouldRejectUnsupportedExtension() {
        byte[] content = new byte[]{1, 2, 3};
        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "test.gif"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
    }

    @Test
    @DisplayName("Từ chối tên file rỗng")
    void shouldRejectBlankFilename() {
        byte[] content = new byte[]{1, 2, 3};
        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "  "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tên file ảnh không hợp lệ.");
    }

    @Test
    @DisplayName("Từ chối ảnh lớn hơn 5 MB trong quá trình stream")
    void shouldRejectImageLargerThanFiveMegabytesDuringStreaming() {
        byte[] content = new byte[5 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> useCase.execute(
                new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "large.png"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ảnh Wiki không được vượt quá 5 MB.");

        verify(mediaContract, never()).uploadAsset(any());
        verify(imageRepositoryPort, never()).save(any());
    }

    private String calculateSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}