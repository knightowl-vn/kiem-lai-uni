package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.ports.ScreenCatalogImageDeliveryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScreenCatalogImageDeliveryAdapter Spring Wiring Tests")
class ScreenCatalogImageDeliveryAdapterWiringTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIRECT_URL = "https://res.cloudinary.com/test/w300.jpg";

    private final MediaContract mockMediaContract = mock(MediaContract.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ScreenCatalogImageDeliveryAdapter.class)
            .withBean(MediaContract.class, () -> mockMediaContract);

    @Test
    @DisplayName("Default configuration: adapter bean is created and direct delivery is enabled")
    void defaultConfigurationInstantiatesAdapterWithDirectDeliveryEnabled() {
        when(mockMediaContract.resolvePublicImageDeliveryUrls(List.of(ASSET_ID), 300, true))
                .thenReturn(Map.of(ASSET_ID, DIRECT_URL));

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ScreenCatalogImageDeliveryAdapter.class);
            assertThat(context).hasSingleBean(ScreenCatalogImageDeliveryPort.class);

            ScreenCatalogImageDeliveryPort port = context.getBean(ScreenCatalogImageDeliveryPort.class);
            Map<UUID, String> result = port.resolvePublicImageUrls(List.of(ASSET_ID), 300, true);

            assertThat(result).containsEntry(ASSET_ID, DIRECT_URL);
            verify(mockMediaContract).resolvePublicImageDeliveryUrls(List.of(ASSET_ID), 300, true);
        });
    }

    @Test
    @DisplayName("Kill-switch configuration: direct-delivery-enabled=false disables batch resolution without calling MediaContract")
    void disabledPropertyDisablesDirectDeliveryWithoutCallingMediaContract() {
        contextRunner
                .withPropertyValues("screen.catalog-image.direct-delivery-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScreenCatalogImageDeliveryAdapter.class);

                    ScreenCatalogImageDeliveryPort port = context.getBean(ScreenCatalogImageDeliveryPort.class);
                    Map<UUID, String> result = port.resolvePublicImageUrls(List.of(ASSET_ID), 300, true);

                    assertThat(result).isEmpty();
                    verify(mockMediaContract, never()).resolvePublicImageDeliveryUrls(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(Integer.class),
                            org.mockito.ArgumentMatchers.anyBoolean()
                    );
                });
    }
}
