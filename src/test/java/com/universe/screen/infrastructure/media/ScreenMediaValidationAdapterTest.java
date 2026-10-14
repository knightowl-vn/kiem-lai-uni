package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenMediaValidationAdapterTest {

    @Mock
    private MediaContract mediaContract;

    private ScreenMediaValidationAdapter adapter;

    private final UUID assetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new ScreenMediaValidationAdapter(mediaContract);
    }

    @Test
    @DisplayName("Validation: null asset ID is treated as valid optional no-op")
    void shouldAcceptNullAssetIdWithoutCallingMediaContract() {
        assertThatCode(() -> adapter.validateImageMediaAsset(null))
                .doesNotThrowAnyException();

        verify(mediaContract, never()).getAssetDetail(any());
    }

    private static UUID any() {
        return org.mockito.ArgumentMatchers.any();
    }

    @Test
    @DisplayName("Validation: active IMAGE asset passes validation")
    void shouldAcceptActiveImageAsset() {
        MediaAssetDetailDTO detail = createDetail(assetId, MediaTypeDTO.IMAGE, MediaAssetStatusDTO.ACTIVE);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatCode(() -> adapter.validateImageMediaAsset(assetId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Validation: archived IMAGE asset passes validation")
    void shouldAcceptArchivedImageAsset() {
        MediaAssetDetailDTO detail = createDetail(assetId, MediaTypeDTO.IMAGE, MediaAssetStatusDTO.ARCHIVED);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatCode(() -> adapter.validateImageMediaAsset(assetId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Validation: missing asset throws ScreenMediaAssetNotFoundException")
    void shouldRejectMissingAsset() {
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.validateImageMediaAsset(assetId))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class)
                .hasMessageContaining(assetId.toString());
    }

    @Test
    @DisplayName("Validation: deleted asset throws ScreenMediaAssetNotFoundException")
    void shouldRejectDeletedAsset() {
        MediaAssetDetailDTO detail = createDetail(assetId, MediaTypeDTO.IMAGE, MediaAssetStatusDTO.DELETED);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> adapter.validateImageMediaAsset(assetId))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class)
                .hasMessageContaining(assetId.toString());
    }

    @ParameterizedTest
    @EnumSource(value = MediaTypeDTO.class, names = {"VIDEO", "AUDIO", "DOCUMENT"})
    @DisplayName("Validation: non-IMAGE media types throw InvalidScreenMediaAssetTypeException")
    void shouldRejectNonImageMediaTypes(MediaTypeDTO nonImageType) {
        MediaAssetDetailDTO detail = createDetail(assetId, nonImageType, MediaAssetStatusDTO.ACTIVE);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> adapter.validateImageMediaAsset(assetId))
                .isInstanceOf(InvalidScreenMediaAssetTypeException.class)
                .hasMessageContaining(assetId.toString())
                .hasMessageContaining(nonImageType.name());
    }

    @Test
    @DisplayName("Validation: null video asset ID is treated as valid optional no-op")
    void shouldAcceptNullVideoAssetIdWithoutCallingMediaContract() {
        assertThatCode(() -> adapter.validateVideoMediaAsset(null))
                .doesNotThrowAnyException();

        verify(mediaContract, never()).getAssetDetail(any());
    }

    @Test
    @DisplayName("Validation: active VIDEO asset passes validation")
    void shouldAcceptActiveVideoAsset() {
        MediaAssetDetailDTO detail = createDetail(assetId, MediaTypeDTO.VIDEO, MediaAssetStatusDTO.ACTIVE);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatCode(() -> adapter.validateVideoMediaAsset(assetId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Validation: missing video asset throws ScreenMediaAssetNotFoundException")
    void shouldRejectMissingVideoAsset() {
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.validateVideoMediaAsset(assetId))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class)
                .hasMessageContaining(assetId.toString());
    }

    @Test
    @DisplayName("Validation: deleted video asset throws ScreenMediaAssetNotFoundException")
    void shouldRejectDeletedVideoAsset() {
        MediaAssetDetailDTO detail = createDetail(assetId, MediaTypeDTO.VIDEO, MediaAssetStatusDTO.DELETED);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> adapter.validateVideoMediaAsset(assetId))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class)
                .hasMessageContaining(assetId.toString());
    }

    @ParameterizedTest
    @EnumSource(value = MediaTypeDTO.class, names = {"IMAGE", "AUDIO", "DOCUMENT"})
    @DisplayName("Validation: non-VIDEO media types throw InvalidScreenMediaAssetTypeException")
    void shouldRejectNonVideoMediaTypes(MediaTypeDTO nonVideoType) {
        MediaAssetDetailDTO detail = createDetail(assetId, nonVideoType, MediaAssetStatusDTO.ACTIVE);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        assertThatThrownBy(() -> adapter.validateVideoMediaAsset(assetId))
                .isInstanceOf(InvalidScreenMediaAssetTypeException.class)
                .hasMessageContaining(assetId.toString())
                .hasMessageContaining(nonVideoType.name());
    }

    private MediaAssetDetailDTO createDetail(UUID id, MediaTypeDTO type, MediaAssetStatusDTO status) {
        Instant now = Instant.parse("2026-10-06T10:00:00Z");
        MediaVersionDTO version = new MediaVersionDTO(
                UUID.randomUUID(), id, 1, "https://cdn.example.com/asset.jpg", "image/jpeg", 1024L, "cover.jpg", now
        );
        return new MediaAssetDetailDTO(
                id, type, MediaVisibilityDTO.PUBLIC, status, 1, now, now, version
        );
    }
}
