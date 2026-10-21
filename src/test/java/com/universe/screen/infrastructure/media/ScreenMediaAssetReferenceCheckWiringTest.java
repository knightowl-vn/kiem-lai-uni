package com.universe.screen.infrastructure.media;

import com.universe.media.application.asset.ArchiveMediaAssetUseCase;
import com.universe.media.application.asset.DeleteMediaAssetUseCase;
import com.universe.media.application.asset.PurgeDeletedMediaAssetUseCase;
import com.universe.media.application.asset.PurgeMediaAssetMetadataService;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ScreenMediaAssetReferenceCheckAdapter Spring Context & Wiring Test")
class ScreenMediaAssetReferenceCheckWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    ScreenMediaAssetReferenceCheckAdapter.class,
                    ArchiveMediaAssetUseCase.class,
                    DeleteMediaAssetUseCase.class,
                    PurgeDeletedMediaAssetUseCase.class
            )
            .withBean(ScreenEntryRepositoryPort.class, () -> mock(ScreenEntryRepositoryPort.class))
            .withBean(MediaAssetRepositoryPort.class, () -> mock(MediaAssetRepositoryPort.class))
            .withBean(MediaAssetVersionRepositoryPort.class, () -> mock(MediaAssetVersionRepositoryPort.class))
            .withBean(MediaImageVariantRepositoryPort.class, () -> mock(MediaImageVariantRepositoryPort.class))
            .withBean(StorageProviderResolverPort.class, () -> mock(StorageProviderResolverPort.class))
            .withBean(PurgeMediaAssetMetadataService.class, () -> mock(PurgeMediaAssetMetadataService.class))
            .withBean(com.universe.media.application.storage.StorageKeyAllocationService.class, () -> mock(com.universe.media.application.storage.StorageKeyAllocationService.class))
            .withBean(com.universe.media.application.storage.MediaStorageCutoverBarrier.class, () -> mock(com.universe.media.application.storage.MediaStorageCutoverBarrier.class))
            .withBean(ClockPort.class, () -> Instant::now);

    @Test
    @DisplayName("Spring DI registers ScreenMediaAssetReferenceCheckAdapter and injects it into Media lifecycle use cases")
    void shouldRegisterAndWireScreenReferenceChecker() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ScreenMediaAssetReferenceCheckAdapter.class);
            assertThat(context).hasSingleBean(MediaAssetReferenceCheckPort.class);

            MediaAssetReferenceCheckPort checkPort = context.getBean(MediaAssetReferenceCheckPort.class);
            assertThat(checkPort).isInstanceOf(ScreenMediaAssetReferenceCheckAdapter.class);
            assertThat(checkPort.supports(MediaTypeDTO.VIDEO)).isTrue();
            assertThat(checkPort.supports(MediaTypeDTO.IMAGE)).isFalse();

            assertThat(context).hasSingleBean(ArchiveMediaAssetUseCase.class);
            assertThat(context).hasSingleBean(DeleteMediaAssetUseCase.class);
            assertThat(context).hasSingleBean(PurgeDeletedMediaAssetUseCase.class);
        });
    }
}
