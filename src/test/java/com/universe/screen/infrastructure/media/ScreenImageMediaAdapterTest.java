package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.GenerateImageVariantRequestDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.UploadMediaAssetRequestDTO;
import com.universe.media.contracts.dto.UploadMediaAssetResponseDTO;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenImageMediaAdapterTest {

    @Mock
    private MediaContract mediaContract;

    private ScreenImageMediaAdapter adapter;

    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new ScreenImageMediaAdapter(mediaContract);
    }

    @Test
    @DisplayName("uploadPoster: uploads with screen.poster prefix, slug hint, and triggers 300px variant")
    void shouldUploadPosterWithSlugHintAndVariant() {
        byte[] bytes = new byte[]{1, 2, 3};
        InputStream is = new ByteArrayInputStream(bytes);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(assetId));

        UUID returnedId = adapter.uploadPoster(is, bytes.length, "image/jpeg", "poster.jpg", "kiem-lai-phan-1");

        assertThat(returnedId).isEqualTo(assetId);

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());

        UploadMediaAssetRequestDTO captured = uploadCaptor.getValue();
        assertThat(captured.mimeType()).isEqualTo("image/jpeg");
        assertThat(captured.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(captured.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(captured.originalFilename()).isEqualTo("poster.jpg");
        assertThat(captured.clientTag()).isEqualTo("screen.poster:kiem-lai-phan-1");

        ArgumentCaptor<GenerateImageVariantRequestDTO> variantCaptor = ArgumentCaptor.forClass(GenerateImageVariantRequestDTO.class);
        verify(mediaContract).generateImageVariant(variantCaptor.capture());
        assertThat(variantCaptor.getValue().mediaAssetId()).isEqualTo(assetId);
        assertThat(variantCaptor.getValue().targetWidth()).isEqualTo(300);
    }

    @Test
    @DisplayName("uploadBackdrop: uploads with screen.backdrop prefix and slug hint without generating poster variant")
    void shouldUploadBackdropWithSlugHintWithoutVariant() {
        byte[] bytes = new byte[]{4, 5, 6};
        InputStream is = new ByteArrayInputStream(bytes);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(assetId));

        UUID returnedId = adapter.uploadBackdrop(is, bytes.length, "image/png", "backdrop.png", "kiem-lai-phan-1");

        assertThat(returnedId).isEqualTo(assetId);

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());

        UploadMediaAssetRequestDTO captured = uploadCaptor.getValue();
        assertThat(captured.mimeType()).isEqualTo("image/png");
        assertThat(captured.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(captured.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(captured.originalFilename()).isEqualTo("backdrop.png");
        assertThat(captured.clientTag()).isEqualTo("screen.backdrop:kiem-lai-phan-1");

        verify(mediaContract, never()).generateImageVariant(any());
    }

    @Test
    @DisplayName("uploadPoster: truncates slug hint longer than 40 chars to protect VARCHAR(64) clientTag")
    void shouldTruncateLongSlugHint() {
        byte[] bytes = new byte[]{1};
        InputStream is = new ByteArrayInputStream(bytes);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(assetId));

        String longSlug = "this-is-a-very-long-screen-title-slug-that-exceeds-forty-characters";
        adapter.uploadPoster(is, bytes.length, "image/jpeg", "poster.jpg", longSlug);

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());

        String clientTag = uploadCaptor.getValue().clientTag();
        assertThat(clientTag).startsWith("screen.poster:");
        assertThat(clientTag.length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("compensateUploadedImage: calls mediaContract.delete to clean up orphan asset")
    void shouldCompensateUploadedImage() {
        adapter.compensateUploadedImage(assetId, null);
        verify(mediaContract).delete(assetId);
    }

    @Test
    @DisplayName("compensateUploadedImage: swallows delete error and attaches suppressed exception")
    void shouldHandleCompensationFailureGracefully() {
        doThrow(new RuntimeException("Storage unreachable")).when(mediaContract).delete(assetId);

        RuntimeException primary = new RuntimeException("Primary failure");
        adapter.compensateUploadedImage(assetId, primary);

        assertThat(primary.getSuppressed()).hasSize(1);
        assertThat(primary.getSuppressed()[0].getMessage()).contains("Storage unreachable");
    }

    @Test
    @DisplayName("uploadPoster: normalizes Vietnamese title diacritics including đ in clientTag")
    void shouldNormalizeVietnameseTitleDiacriticsInClientTag() {
        byte[] bytes = new byte[]{1, 2};
        InputStream is = new ByteArrayInputStream(bytes);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(assetId));

        adapter.uploadPoster(is, bytes.length, "image/jpeg", "poster.jpg", "Kiếm Lai");

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());

        assertThat(uploadCaptor.getValue().clientTag()).isEqualTo("screen.poster:kiem-lai");
    }

    @Test
    @DisplayName("uploadBackdrop: normalizes Vietnamese 'Đ' in clientTag")
    void shouldNormalizeVietnameseDInClientTag() {
        byte[] bytes = new byte[]{1, 2};
        InputStream is = new ByteArrayInputStream(bytes);

        when(mediaContract.uploadAsset(any(UploadMediaAssetRequestDTO.class)))
                .thenReturn(new UploadMediaAssetResponseDTO(assetId));

        adapter.uploadBackdrop(is, bytes.length, "image/png", "backdrop.png", "Đại Chúa Tể");

        ArgumentCaptor<UploadMediaAssetRequestDTO> uploadCaptor = ArgumentCaptor.forClass(UploadMediaAssetRequestDTO.class);
        verify(mediaContract).uploadAsset(uploadCaptor.capture());

        assertThat(uploadCaptor.getValue().clientTag()).isEqualTo("screen.backdrop:dai-chua-te");
    }
}
