package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenCatalogImageDeliveryAdapter Tests")
class ScreenCatalogImageDeliveryAdapterTest {

    private static final UUID ASSET_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ASSET_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DIRECT_URL_1 = "https://res.cloudinary.com/test/w300_1.jpg";
    private static final String DIRECT_URL_2 = "https://res.cloudinary.com/test/w300_2.jpg";

    @Mock
    private MediaContract mediaContract;

    @Test
    @DisplayName("Delegates batch image resolution to MediaContract when direct delivery is enabled")
    void shouldDelegateBatchResolutionToMediaContractWhenEnabled() {
        ScreenCatalogImageDeliveryAdapter adapter = new ScreenCatalogImageDeliveryAdapter(mediaContract, true);
        List<UUID> assetIds = List.of(ASSET_ID_1, ASSET_ID_2);
        Map<UUID, String> expectedMap = Map.of(ASSET_ID_1, DIRECT_URL_1, ASSET_ID_2, DIRECT_URL_2);

        when(mediaContract.resolvePublicImageDeliveryUrls(assetIds, 300, true)).thenReturn(expectedMap);

        Map<UUID, String> result = adapter.resolvePublicImageUrls(assetIds, 300, true);

        assertThat(result).isEqualTo(expectedMap);
        verify(mediaContract).resolvePublicImageDeliveryUrls(assetIds, 300, true);
    }

    @Test
    @DisplayName("Returns empty map and skips MediaContract call when asset IDs collection is null or empty")
    void shouldReturnEmptyMapWhenAssetIdsNullOrEmpty() {
        ScreenCatalogImageDeliveryAdapter adapter = new ScreenCatalogImageDeliveryAdapter(mediaContract, true);

        Map<UUID, String> resultNull = adapter.resolvePublicImageUrls(null, 300, true);
        assertThat(resultNull).isEmpty();

        Map<UUID, String> resultEmpty = adapter.resolvePublicImageUrls(List.of(), 300, true);
        assertThat(resultEmpty).isEmpty();

        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Integer.class),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    @DisplayName("Returns empty map and skips MediaContract call when direct delivery kill-switch is disabled")
    void shouldReturnEmptyMapWhenDisabled() {
        ScreenCatalogImageDeliveryAdapter adapter = new ScreenCatalogImageDeliveryAdapter(mediaContract, false);
        List<UUID> assetIds = List.of(ASSET_ID_1);

        Map<UUID, String> result = adapter.resolvePublicImageUrls(assetIds, 300, true);

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Integer.class),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }

    @Test
    @DisplayName("Returns empty map when MediaContract returns null")
    void shouldHandleNullReturnFromMediaContractGracefully() {
        ScreenCatalogImageDeliveryAdapter adapter = new ScreenCatalogImageDeliveryAdapter(mediaContract, true);
        List<UUID> assetIds = List.of(ASSET_ID_1);

        when(mediaContract.resolvePublicImageDeliveryUrls(assetIds, 300, true)).thenReturn(null);

        Map<UUID, String> result = adapter.resolvePublicImageUrls(assetIds, 300, true);

        assertThat(result).isNotNull().isEmpty();
        verify(mediaContract).resolvePublicImageDeliveryUrls(assetIds, 300, true);
    }
}
