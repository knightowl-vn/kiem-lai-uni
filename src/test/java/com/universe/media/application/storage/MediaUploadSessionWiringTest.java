package com.universe.media.application.storage;

import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.infrastructure.persistence.MediaUploadSessionPersistenceAdapter;
import com.universe.media.infrastructure.persistence.SpringDataMediaUploadSessionJpaRepository;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("MediaUploadSession Spring Context & Wiring Test")
class MediaUploadSessionWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MediaUploadSessionService.class,
                    MediaUploadSessionPersistenceAdapter.class
            )
            .withBean(SpringDataMediaUploadSessionJpaRepository.class, () -> mock(SpringDataMediaUploadSessionJpaRepository.class))
            .withBean(StorageKeyAllocationService.class, () -> mock(StorageKeyAllocationService.class))
            .withBean(MediaStorageRoutingService.class, () -> mock(MediaStorageRoutingService.class))
            .withBean(ClockPort.class, () -> Instant::now)
            .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    @DisplayName("Successfully registers and wires MediaUploadSessionService, Adapter, and Repository port")
    void shouldWireMediaUploadSessionBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MediaUploadSessionService.class);
            assertThat(context).hasSingleBean(MediaUploadSessionPersistenceAdapter.class);
            assertThat(context).hasSingleBean(MediaUploadSessionRepositoryPort.class);
            assertThat(context.getBean(MediaUploadSessionRepositoryPort.class))
                    .isInstanceOf(MediaUploadSessionPersistenceAdapter.class);
        });
    }
}
