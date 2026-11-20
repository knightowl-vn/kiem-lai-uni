package com.universe.community.infrastructure.media;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityPostImageDeliveryAdapter Unit Tests")
class CommunityPostImageDeliveryAdapterTest {

    private static final UUID ASSET_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ASSET_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DIRECT_URL_1 = "https://res.cloudinary.com/kiemlai/image/upload/post1.jpg";
    private static final String DIRECT_URL_2 = "https://res.cloudinary.com/kiemlai/image/upload/post2.webp";

    @Mock
    private MediaContract mediaContract;

    @Test
    @DisplayName("Throws NullPointerException when MediaContract is null")
    void shouldThrowWhenMediaContractIsNull() {
        assertThatThrownBy(() -> new CommunityPostImageDeliveryAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("MediaContract cannot be null.");
    }

    @Test
    @DisplayName("Delegates batch source-fallback resolution to MediaContract when enabled")
    void shouldDelegateBatchResolutionToMediaContractWhenEnabled() {
        CommunityPostImageDeliveryAdapter adapter = new CommunityPostImageDeliveryAdapter(mediaContract, true);
        List<UUID> assetIds = List.of(ASSET_ID_1, ASSET_ID_2);
        Map<UUID, String> expected = Map.of(ASSET_ID_1, DIRECT_URL_1, ASSET_ID_2, DIRECT_URL_2);

        when(mediaContract.resolvePublicImageDeliveryUrls(assetIds, (Integer) null, true)).thenReturn(expected);

        Map<UUID, String> result = adapter.resolvePublicImageUrls(assetIds);

        assertThat(result).isEqualTo(expected);
        verify(mediaContract).resolvePublicImageDeliveryUrls(assetIds, (Integer) null, true);
    }

    @Test
    @DisplayName("Returns empty map and skips MediaContract when assetIds is null or empty")
    void shouldSkipMediaContractWhenAssetIdsNullOrEmpty() {
        CommunityPostImageDeliveryAdapter adapter = new CommunityPostImageDeliveryAdapter(mediaContract, true);

        assertThat(adapter.resolvePublicImageUrls(null)).isEmpty();
        assertThat(adapter.resolvePublicImageUrls(List.of())).isEmpty();

        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(any(), any(Integer.class), anyBoolean());
        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(any(), any(String.class), anyBoolean());
    }

    @Test
    @DisplayName("Returns empty map and skips MediaContract when kill-switch is disabled")
    void shouldSkipMediaContractWhenDisabled() {
        CommunityPostImageDeliveryAdapter adapter = new CommunityPostImageDeliveryAdapter(mediaContract, false);

        Map<UUID, String> result = adapter.resolvePublicImageUrls(List.of(ASSET_ID_1));

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(any(), any(Integer.class), anyBoolean());
        verify(mediaContract, never()).resolvePublicImageDeliveryUrls(any(), any(String.class), anyBoolean());
    }

    @Test
    @DisplayName("Returns empty map when MediaContract returns null")
    void shouldHandleNullReturnGracefully() {
        CommunityPostImageDeliveryAdapter adapter = new CommunityPostImageDeliveryAdapter(mediaContract, true);
        List<UUID> assetIds = List.of(ASSET_ID_1);

        when(mediaContract.resolvePublicImageDeliveryUrls(assetIds, (Integer) null, true)).thenReturn(null);

        assertThat(adapter.resolvePublicImageUrls(assetIds)).isNotNull().isEmpty();
    }
}
