package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.shared.exceptions.BaseApplicationException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenVideoDeliveryAdapter Tests")
class ScreenVideoDeliveryAdapterTest {

    @Mock
    private MediaContract mediaContract;

    private ScreenVideoDeliveryAdapter adapter;

    private final Instant now = Instant.parse("2026-11-05T12:00:00Z");

    @BeforeEach
    void setUp() {
        adapter = new ScreenVideoDeliveryAdapter(mediaContract);
    }

    private MediaVersionDTO createVersion(UUID assetId, int versionNumber, String mimeType) {
        return new MediaVersionDTO(
                UUID.randomUUID(), assetId, versionNumber,
                null, mimeType, 1024L * 1024L * 50L,
                "sample.mp4", now
        );
    }

    private MediaVersionDTO createVersion(UUID assetId, int versionNumber) {
        return createVersion(assetId, versionNumber, "video/mp4");
    }

    private MediaAssetDetailDTO createDetail(
            UUID assetId, MediaTypeDTO type, MediaVisibilityDTO visibility,
            MediaAssetStatusDTO status, MediaVersionDTO version
    ) {
        return new MediaAssetDetailDTO(
                assetId, type, visibility, status,
                version != null ? version.versionNumber() : 1,
                now, now, version
        );
    }

    @Test
    @DisplayName("Active, public VIDEO asset resolves canonical delivery URL successfully")
    void activePublicVideoResolvesDeliveryUrl() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1);
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isPresent();
        assertThat(result.get().mediaAssetId()).isEqualTo(assetId);
        assertThat(result.get().deliveryUrl()).isEqualTo("/media/assets/" + assetId + "/content");
        assertThat(result.get().mimeType()).isEqualTo("video/mp4");
    }

    @Test
    @DisplayName("WebM video is rejected under Screen V1 canonical MP4 policy")
    void webmVideoRejectedUnderScreenV1Policy() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1, "video/webm");
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Unsupported video MIME type (e.g. video/quicktime, image/png) returns Optional.empty")
    void unsupportedMimeTypeReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1, "video/quicktime");
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Blank or null version MIME type returns Optional.empty")
    void blankOrNullMimeTypeReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1, "   ");
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("MEDIA_ASSET_VERSION_NOT_FOUND exception from MediaContract returns Optional.empty safely")
    void missingVersionExceptionFromMediaContractReturnsEmptySafely() {
        UUID assetId = UUID.randomUUID();
        when(mediaContract.getAssetDetail(assetId)).thenThrow(new BaseApplicationException(
                "MEDIA_ASSET_VERSION_NOT_FOUND",
                "Media asset version not found for asset: " + assetId + ", version: 1"
        ) {});

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("MEDIA_ASSET_NOT_FOUND exception from MediaContract returns Optional.empty safely")
    void missingAssetExceptionFromMediaContractReturnsEmptySafely() {
        UUID assetId = UUID.randomUUID();
        when(mediaContract.getAssetDetail(assetId)).thenThrow(new BaseApplicationException(
                "MEDIA_ASSET_NOT_FOUND",
                "Media asset not found: " + assetId
        ) {});

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Unexpected RuntimeException (e.g. database disconnect, NPE) propagates without being swallowed")
    void unexpectedRuntimeExceptionFromMediaContractPropagates() {
        UUID assetId = UUID.randomUUID();
        when(mediaContract.getAssetDetail(assetId)).thenThrow(new IllegalStateException("Database connection pool exhausted"));

        assertThatThrownBy(() -> adapter.resolvePlaybackDelivery(assetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database connection pool exhausted");
    }

    @Test
    @DisplayName("Unrelated BaseApplicationException (e.g. storage error) propagates without being disguised")
    void unrelatedBaseApplicationExceptionPropagates() {
        UUID assetId = UUID.randomUUID();
        when(mediaContract.getAssetDetail(assetId)).thenThrow(new BaseApplicationException(
                "STORAGE_UNAVAILABLE",
                "Storage backend down"
        ) {});

        assertThatThrownBy(() -> adapter.resolvePlaybackDelivery(assetId))
                .isInstanceOf(BaseApplicationException.class)
                .hasMessageContaining("Storage backend down");
    }

    @Test
    @DisplayName("Null asset ID returns Optional.empty without calling MediaContract")
    void nullAssetIdReturnsEmpty() {
        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(mediaContract);
    }

    @Test
    @DisplayName("Missing media asset returns Optional.empty")
    void missingAssetReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.empty());

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("DELETED media asset returns Optional.empty")
    void deletedAssetReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1);
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.DELETED, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ARCHIVED media asset returns Optional.empty")
    void archivedAssetReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1);
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ARCHIVED, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("PRIVATE media asset returns Optional.empty")
    void privateAssetReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1);
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PRIVATE,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("RESTRICTED media asset returns Optional.empty")
    void restrictedAssetReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1);
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.RESTRICTED,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Non-video media asset (e.g. IMAGE) returns Optional.empty")
    void nonVideoAssetReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaVersionDTO version = createVersion(assetId, 1);
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.IMAGE, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE, version
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Asset without currentVersion returns Optional.empty")
    void assetWithoutVersionReturnsEmpty() {
        UUID assetId = UUID.randomUUID();
        MediaAssetDetailDTO detail = createDetail(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC,
                MediaAssetStatusDTO.ACTIVE, null
        );

        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        Optional<ScreenPlaybackDeliveryInfo> result = adapter.resolvePlaybackDelivery(assetId);

        assertThat(result).isEmpty();
    }
}
