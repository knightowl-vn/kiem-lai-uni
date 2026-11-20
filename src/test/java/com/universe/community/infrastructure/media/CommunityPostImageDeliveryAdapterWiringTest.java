package com.universe.community.infrastructure.media;

import com.universe.community.application.port.out.CommunityPostImageDeliveryPort;
import com.universe.media.contracts.interfaces.MediaContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CommunityPostImageDeliveryAdapter Spring Wiring Tests")
class CommunityPostImageDeliveryAdapterWiringTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIRECT_URL = "https://res.cloudinary.com/kiemlai/image/upload/post.jpg";

    private final MediaContract mockMediaContract = mock(MediaContract.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CommunityPostImageDeliveryAdapter.class)
            .withBean(MediaContract.class, () -> mockMediaContract);

    @Test
    @DisplayName("Default configuration enables direct delivery and delegates to MediaContract")
    void defaultConfigurationEnablesDirectDelivery() {
        when(mockMediaContract.resolvePublicImageDeliveryUrls(List.of(ASSET_ID), (Integer) null, true))
                .thenReturn(Map.of(ASSET_ID, DIRECT_URL));

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CommunityPostImageDeliveryAdapter.class);
            assertThat(context).hasSingleBean(CommunityPostImageDeliveryPort.class);

            CommunityPostImageDeliveryPort port = context.getBean(CommunityPostImageDeliveryPort.class);
            Map<UUID, String> result = port.resolvePublicImageUrls(List.of(ASSET_ID));

            assertThat(result).containsEntry(ASSET_ID, DIRECT_URL);
            verify(mockMediaContract).resolvePublicImageDeliveryUrls(List.of(ASSET_ID), (Integer) null, true);
        });
    }

    @Test
    @DisplayName("Kill-switch disables direct delivery without calling MediaContract")
    void killSwitchDisablesDirectDelivery() {
        contextRunner
                .withPropertyValues("community.post-image.direct-delivery-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CommunityPostImageDeliveryAdapter.class);

                    CommunityPostImageDeliveryPort port = context.getBean(CommunityPostImageDeliveryPort.class);
                    Map<UUID, String> result = port.resolvePublicImageUrls(List.of(ASSET_ID));

                    assertThat(result).isEmpty();
                    verify(mockMediaContract, never()).resolvePublicImageDeliveryUrls(any(), any(Integer.class), anyBoolean());
                    verify(mockMediaContract, never()).resolvePublicImageDeliveryUrls(any(), any(String.class), anyBoolean());
                });
    }
}
