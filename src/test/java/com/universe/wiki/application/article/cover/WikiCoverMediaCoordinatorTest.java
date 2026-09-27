package com.universe.wiki.application.article.cover;

import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaVersionUploadOutcome;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
import com.universe.media.contracts.dto.UploadMediaAssetVersionConditionalResponseDTO;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiCoverMediaCoordinatorTest {

    @Mock
    private MediaContract mediaContract;

    private WikiCoverMediaCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new WikiCoverMediaCoordinator(mediaContract);
    }

    @Test
    @DisplayName("Tải lên ảnh bìa lần đầu: gọi uploadAsset và generateImageVariant w300")
    void shouldUploadInitialCoverAndRequestVariant() {
        UUID assetId = UUID.randomUUID();
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        WikiCoverUpload upload = new WikiCoverUpload(stream, 3, "image/jpeg", "cover.jpg");

        UploadMediaAssetResponseDTO responseDTO = new UploadMediaAssetResponseDTO(assetId);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class))).thenReturn(responseDTO);

        UUID resultId = coordinator.uploadInitialCover(upload);

        assertThat(resultId).isEqualTo(assetId);

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());
        assertThat(uploadCaptor.getValue().originalFilename()).isEqualTo("cover.jpg");
        assertThat(uploadCaptor.getValue().mimeType()).isEqualTo("image/jpeg");
        assertThat(uploadCaptor.getValue().mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(uploadCaptor.getValue().visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(uploadCaptor.getValue().clientTag()).isEqualTo(WikiCoverMediaCoordinator.WIKI_ARTICLE_COVER_CLIENT_TAG);
        assertThat(uploadCaptor.getValue().clientTag()).isEqualTo("wiki.article.cover");

        ArgumentCaptor<GenerateImageVariantRequestDTO> variantCaptor = ArgumentCaptor.forClass(GenerateImageVariantRequestDTO.class);
        verify(mediaContract).generateImageVariant(variantCaptor.capture());
        assertThat(variantCaptor.getValue().mediaAssetId()).isEqualTo(assetId);
        assertThat(variantCaptor.getValue().targetWidth()).isEqualTo(300);
    }

    @Test
    @DisplayName("Thay thế ảnh bìa version mới: gọi uploadVersionIfContentChanged và generateImageVariant w300 khi VERSION_CREATED")
    void shouldReplaceCoverVersionAndRequestVariant() {
        UUID assetId = UUID.randomUUID();
        InputStream stream = new ByteArrayInputStream(new byte[]{4, 5, 6, 7});
        WikiCoverUpload upload = new WikiCoverUpload(stream, 4, "image/png", "cover-v2.png");

        UploadMediaAssetVersionConditionalResponseDTO versionResponseDTO =
                UploadMediaAssetVersionConditionalResponseDTO.versionCreated(assetId, 2);

        when(mediaContract.uploadVersionIfContentChanged(any(UploadMediaAssetVersionRequestDTO.class))).thenReturn(versionResponseDTO);

        MediaVersionUploadOutcome outcome = coordinator.replaceCoverVersion(assetId, upload);

        assertThat(outcome).isEqualTo(MediaVersionUploadOutcome.VERSION_CREATED);

        ArgumentCaptor<UploadMediaAssetVersionRequestDTO> versionCaptor = ArgumentCaptor.forClass(UploadMediaAssetVersionRequestDTO.class);
        verify(mediaContract).uploadVersionIfContentChanged(versionCaptor.capture());
        assertThat(versionCaptor.getValue().assetId()).isEqualTo(assetId);
        assertThat(versionCaptor.getValue().mimeType()).isEqualTo("image/png");
        assertThat(versionCaptor.getValue().originalFilename()).isEqualTo("cover-v2.png");

        ArgumentCaptor<GenerateImageVariantRequestDTO> variantCaptor = ArgumentCaptor.forClass(GenerateImageVariantRequestDTO.class);
        verify(mediaContract).generateImageVariant(variantCaptor.capture());
        assertThat(variantCaptor.getValue().mediaAssetId()).isEqualTo(assetId);
        assertThat(variantCaptor.getValue().targetWidth()).isEqualTo(300);
    }

    @Test
    @DisplayName("Thay thế ảnh bìa trùng nội dung (UNCHANGED): không tạo biến thể w300")
    void shouldNotGenerateVariantWhenCoverUnchanged() {
        UUID assetId = UUID.randomUUID();
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        WikiCoverUpload upload = new WikiCoverUpload(stream, 3, "image/jpeg", "cover-same.jpg");

        UploadMediaAssetVersionConditionalResponseDTO unchangedResponse =
                UploadMediaAssetVersionConditionalResponseDTO.unchanged(assetId, 1);

        when(mediaContract.uploadVersionIfContentChanged(any(UploadMediaAssetVersionRequestDTO.class))).thenReturn(unchangedResponse);

        MediaVersionUploadOutcome outcome = coordinator.replaceCoverVersion(assetId, upload);

        assertThat(outcome).isEqualTo(MediaVersionUploadOutcome.UNCHANGED);
        verify(mediaContract).uploadVersionIfContentChanged(any(UploadMediaAssetVersionRequestDTO.class));
        verify(mediaContract, never()).generateImageVariant(any());
    }

    @Test
    @DisplayName("Tạo biến thể lỗi: không làm gián đoạn luồng chính")
    void shouldSwallowExceptionWhenGenerateVariantFails() {
        UUID assetId = UUID.randomUUID();
        doThrow(new RuntimeException("Variant generation failed"))
                .when(mediaContract).generateImageVariant(any(GenerateImageVariantRequestDTO.class));

        assertThatCode(() -> coordinator.generateCoverVariantBestEffort(assetId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Đền bù ảnh bìa khi lỗi: gọi delete và không rethrow")
    void shouldCompensateInitialCover() {
        UUID assetId = UUID.randomUUID();
        RuntimeException original = new RuntimeException("Database failure");

        coordinator.compensateInitialCover(assetId, original);

        verify(mediaContract).delete(assetId);
    }

    @Test
    @DisplayName("Đền bù thất bại: gắn suppressed exception vào originalError")
    void shouldAttachSuppressedWhenCompensationFails() {
        UUID assetId = UUID.randomUUID();
        RuntimeException original = new RuntimeException("Database failure");
        RuntimeException mediaError = new RuntimeException("Media service down");

        doThrow(mediaError).when(mediaContract).delete(assetId);

        assertThatCode(() -> coordinator.compensateInitialCover(assetId, original)).doesNotThrowAnyException();

        assertThat(original.getSuppressed()).contains(mediaError);
    }
}
