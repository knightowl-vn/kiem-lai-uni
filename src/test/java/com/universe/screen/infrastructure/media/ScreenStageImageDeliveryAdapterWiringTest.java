package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.ports.ScreenStageImageDeliveryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScreenStageImageDeliveryAdapter Spring Wiring Tests")
class ScreenStageImageDeliveryAdapterWiringTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIRECT_URL = "https://res.cloudinary.com/test/w600.jpg";

    private final MediaContract mockMediaContract = mock(MediaContract.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ScreenStageImageDeliveryAdapter.class)
            .withBean(MediaContract.class, () -> mockMediaContract);

    @Test
    @DisplayName("Default configuration: adapter bean is created and direct delivery is enabled")
    void defaultConfigurationInstantiatesAdapterWithDirectDeliveryEnabled() {
        when(mockMediaContract.resolvePublicImageDeliveryUrl(ASSET_ID, 600))
                .thenReturn(Optional.of(DIRECT_URL));

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ScreenStageImageDeliveryAdapter.class);
            assertThat(context).hasSingleBean(ScreenStageImageDeliveryPort.class);

            ScreenStageImageDeliveryPort port = context.getBean(ScreenStageImageDeliveryPort.class);
            Optional<String> result = port.resolvePublicImageUrl(ASSET_ID, 600);

            assertThat(result).isPresent().contains(DIRECT_URL);
            verify(mockMediaContract).resolvePublicImageDeliveryUrl(ASSET_ID, 600);
        });
    }

    @Test
    @DisplayName("Kill-switch configuration: direct-delivery-enabled=false disables resolution without calling MediaContract")
    void disabledPropertyDisablesDirectDeliveryWithoutCallingMediaContract() {
        contextRunner
                .withPropertyValues("screen.stage-image.direct-delivery-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScreenStageImageDeliveryAdapter.class);

                    ScreenStageImageDeliveryPort port = context.getBean(ScreenStageImageDeliveryPort.class);
                    Optional<String> result = port.resolvePublicImageUrl(ASSET_ID, 600);

                    assertThat(result).isEmpty();
                    verify(mockMediaContract, never()).resolvePublicImageDeliveryUrl(ASSET_ID, 600);
                });
    }
}
