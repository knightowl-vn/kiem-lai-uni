package com.universe.wiki.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WikiPublicCoverImageDeliveryAdapter Tests")
class WikiPublicCoverImageDeliveryAdapterTest {

    private static final UUID ASSET_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ASSET_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DIRECT_URL_1 = "https://res.cloudinary.com/test/w300_1.webp";
    private static final String DIRECT_URL_2 = "https://res.cloudinary.com/test/w300_2.webp";

    @Mock
    private MediaContract mediaContract;

    @Test
    @DisplayName("Delegates batch image resolution to MediaContract when direct delivery is enabled")
    void shouldDelegateBatchResolutionToMediaContractWhenEnabled() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, true);
        List<UUID> assetIds = List.of(ASSET_ID_1, ASSET_ID_2);
        Map<UUID, String> expectedMap = Map.of(ASSET_ID_1, DIRECT_URL_1, ASSET_ID_2, DIRECT_URL_2);

        when(mediaContract.resolvePublicImageDeliveryUrls(assetIds, 300, true)).thenReturn(expectedMap);

        Map<UUID, String> result = adapter.resolvePublicCoverUrls(assetIds, 300, true);

        assertThat(result).isEqualTo(expectedMap);
        verify(mediaContract).resolvePublicImageDeliveryUrls(assetIds, 300, true);
    }

    @Test
    @DisplayName("Returns empty map and skips MediaContract call when asset IDs collection is null or empty")
    void shouldReturnEmptyMapWhenAssetIdsNullOrEmpty() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, true);

        Map<UUID, String> resultNull = adapter.resolvePublicCoverUrls(null, 300, true);
        assertThat(resultNull).isEmpty();

        Map<UUID, String> resultEmpty = adapter.resolvePublicCoverUrls(List.of(), 300, true);
        assertThat(resultEmpty).isEmpty();

        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Returns empty map and skips MediaContract call when direct delivery kill-switch is disabled")
    void shouldReturnEmptyMapWhenBatchDisabled() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, false);
        List<UUID> assetIds = List.of(ASSET_ID_1);

        Map<UUID, String> result = adapter.resolvePublicCoverUrls(assetIds, 300, true);

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Returns empty map when MediaContract returns null for batch resolution")
    void shouldHandleNullReturnFromMediaContractBatchGracefully() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, true);
        List<UUID> assetIds = List.of(ASSET_ID_1);

        when(mediaContract.resolvePublicImageDeliveryUrls(assetIds, 300, true)).thenReturn(null);

        Map<UUID, String> result = adapter.resolvePublicCoverUrls(assetIds, 300, true);

        assertThat(result).isNotNull().isEmpty();
        verify(mediaContract).resolvePublicImageDeliveryUrls(assetIds, 300, true);
    }

    @Test
    @DisplayName("Delegates single image resolution to MediaContract when direct delivery is enabled")
    void shouldDelegateSingleResolutionToMediaContractWhenEnabled() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, true);

        when(mediaContract.resolvePublicImageDeliveryUrl(ASSET_ID_1, 300, true)).thenReturn(Optional.of(DIRECT_URL_1));

        Optional<String> result = adapter.resolvePublicCoverUrl(ASSET_ID_1, 300, true);

        assertThat(result).contains(DIRECT_URL_1);
        verify(mediaContract).resolvePublicImageDeliveryUrl(ASSET_ID_1, 300, true);
    }

    @Test
    @DisplayName("Returns empty Optional and skips MediaContract call when asset ID is null for single resolution")
    void shouldReturnEmptyOptionalWhenAssetIdNull() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, true);

        Optional<String> result = adapter.resolvePublicCoverUrl(null, 300, true);

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrl(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Returns empty Optional and skips MediaContract call when direct delivery kill-switch is disabled")
    void shouldReturnEmptyOptionalWhenSingleDisabled() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, false);

        Optional<String> result = adapter.resolvePublicCoverUrl(ASSET_ID_1, 300, true);

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrl(any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("Returns empty Optional when MediaContract returns null for single resolution")
    void shouldHandleNullReturnFromMediaContractSingleGracefully() {
        WikiPublicCoverImageDeliveryAdapter adapter = new WikiPublicCoverImageDeliveryAdapter(mediaContract, true);

        when(mediaContract.resolvePublicImageDeliveryUrl(ASSET_ID_1, 300, true)).thenReturn(null);

        Optional<String> result = adapter.resolvePublicCoverUrl(ASSET_ID_1, 300, true);

        assertThat(result).isNotNull().isEmpty();
        verify(mediaContract).resolvePublicImageDeliveryUrl(ASSET_ID_1, 300, true);
    }
}
