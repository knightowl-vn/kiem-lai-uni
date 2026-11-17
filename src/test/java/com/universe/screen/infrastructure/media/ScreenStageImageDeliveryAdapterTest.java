package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenStageImageDeliveryAdapter Tests")
class ScreenStageImageDeliveryAdapterTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIRECT_URL = "https://res.cloudinary.com/test/w600.jpg";

    @Mock
    private MediaContract mediaContract;

    @Test
    @DisplayName("Delegates to MediaContract when direct delivery is enabled")
    void shouldDelegateToMediaContractWhenEnabled() {
        ScreenStageImageDeliveryAdapter adapter = new ScreenStageImageDeliveryAdapter(mediaContract, true);
        when(mediaContract.resolvePublicImageDeliveryUrl(ASSET_ID, 600)).thenReturn(Optional.of(DIRECT_URL));

        Optional<String> result = adapter.resolvePublicImageUrl(ASSET_ID, 600);

        assertThat(result).isPresent().contains(DIRECT_URL);
        verify(mediaContract).resolvePublicImageDeliveryUrl(ASSET_ID, 600);
    }

    @Test
    @DisplayName("Delegates to MediaContract with fallbackToSource when direct delivery is enabled")
    void shouldDelegateToMediaContractWithFallbackToSourceWhenEnabled() {
        ScreenStageImageDeliveryAdapter adapter = new ScreenStageImageDeliveryAdapter(mediaContract, true);
        when(mediaContract.resolvePublicImageDeliveryUrl(ASSET_ID, 1200, true)).thenReturn(Optional.of(DIRECT_URL));

        Optional<String> result = adapter.resolvePublicImageUrl(ASSET_ID, 1200, true);

        assertThat(result).isPresent().contains(DIRECT_URL);
        verify(mediaContract).resolvePublicImageDeliveryUrl(ASSET_ID, 1200, true);
    }

    @Test
    @DisplayName("Returns empty and skips MediaContract call when asset ID is null")
    void shouldReturnEmptyWhenAssetIdIsNull() {
        ScreenStageImageDeliveryAdapter adapter = new ScreenStageImageDeliveryAdapter(mediaContract, true);

        Optional<String> result = adapter.resolvePublicImageUrl(null, 600);

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrl(null, 600);
    }

    @Test
    @DisplayName("Returns empty and skips MediaContract call when direct delivery kill-switch is disabled")
    void shouldReturnEmptyWhenDisabled() {
        ScreenStageImageDeliveryAdapter adapter = new ScreenStageImageDeliveryAdapter(mediaContract, false);

        Optional<String> result = adapter.resolvePublicImageUrl(ASSET_ID, 600);

        assertThat(result).isEmpty();
        verify(mediaContract, never()).resolvePublicImageDeliveryUrl(ASSET_ID, 600);
    }
}
