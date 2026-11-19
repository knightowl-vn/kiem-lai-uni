package com.universe.wiki.infrastructure.media;

import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WikiPublicCoverImageDeliveryAdapter Spring Wiring Tests")
class WikiPublicCoverImageDeliveryAdapterWiringTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIRECT_URL = "https://res.cloudinary.com/test/w300.webp";

    private final MediaContract mockMediaContract = mock(MediaContract.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WikiPublicCoverImageDeliveryAdapter.class)
            .withBean(MediaContract.class, () -> mockMediaContract);

    @Test
    @DisplayName("Default configuration: adapter bean is created and direct delivery is enabled")
    void defaultConfigurationInstantiatesAdapterWithDirectDeliveryEnabled() {
        when(mockMediaContract.resolvePublicImageDeliveryUrls(List.of(ASSET_ID), 300, true))
                .thenReturn(Map.of(ASSET_ID, DIRECT_URL));
        when(mockMediaContract.resolvePublicImageDeliveryUrl(ASSET_ID, 300, true))
                .thenReturn(Optional.of(DIRECT_URL));

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WikiPublicCoverImageDeliveryAdapter.class);
            assertThat(context).hasSingleBean(WikiPublicCoverImageDeliveryPort.class);

            WikiPublicCoverImageDeliveryPort port = context.getBean(WikiPublicCoverImageDeliveryPort.class);

            Map<UUID, String> batchResult = port.resolvePublicCoverUrls(List.of(ASSET_ID), 300, true);
            assertThat(batchResult).containsEntry(ASSET_ID, DIRECT_URL);
            verify(mockMediaContract).resolvePublicImageDeliveryUrls(List.of(ASSET_ID), 300, true);

            Optional<String> singleResult = port.resolvePublicCoverUrl(ASSET_ID, 300, true);
            assertThat(singleResult).contains(DIRECT_URL);
            verify(mockMediaContract).resolvePublicImageDeliveryUrl(ASSET_ID, 300, true);
        });
    }

    @Test
    @DisplayName("Kill-switch configuration: wiki.public-cover.direct-delivery-enabled=false disables direct delivery without calling MediaContract")
    void disabledPropertyDisablesDirectDeliveryWithoutCallingMediaContract() {
        contextRunner
                .withPropertyValues("wiki.public-cover.direct-delivery-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WikiPublicCoverImageDeliveryAdapter.class);

                    WikiPublicCoverImageDeliveryPort port = context.getBean(WikiPublicCoverImageDeliveryPort.class);

                    Map<UUID, String> batchResult = port.resolvePublicCoverUrls(List.of(ASSET_ID), 300, true);
                    assertThat(batchResult).isEmpty();
                    verify(mockMediaContract, never()).resolvePublicImageDeliveryUrls(any(), anyInt(), anyBoolean());

                    Optional<String> singleResult = port.resolvePublicCoverUrl(ASSET_ID, 300, true);
                    assertThat(singleResult).isEmpty();
                    verify(mockMediaContract, never()).resolvePublicImageDeliveryUrl(any(), anyInt(), anyBoolean());
                });
    }
}
