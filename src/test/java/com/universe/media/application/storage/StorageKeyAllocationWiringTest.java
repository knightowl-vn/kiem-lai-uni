package com.universe.media.application.storage;

import com.universe.media.application.ports.StorageKeyAllocationRepositoryPort;
import com.universe.media.infrastructure.persistence.SpringDataStorageKeyAllocationJpaRepository;
import com.universe.media.infrastructure.persistence.StorageKeyAllocationPersistenceAdapter;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("StorageKeyAllocation Spring Context & Wiring Test")
class StorageKeyAllocationWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    StorageKeyAllocationService.class,
                    StorageKeyAllocationPersistenceAdapter.class
            )
            .withBean(SpringDataStorageKeyAllocationJpaRepository.class, () -> mock(SpringDataStorageKeyAllocationJpaRepository.class))
            .withBean(ClockPort.class, () -> Instant::now);

    @Test
    @DisplayName("Successfully registers and wires StorageKeyAllocationService, Adapter, and Repository port")
    void shouldWireStorageKeyAllocationBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StorageKeyAllocationService.class);
            assertThat(context).hasSingleBean(StorageKeyAllocationPersistenceAdapter.class);
            assertThat(context).hasSingleBean(StorageKeyAllocationRepositoryPort.class);
            assertThat(context.getBean(StorageKeyAllocationRepositoryPort.class))
                    .isInstanceOf(StorageKeyAllocationPersistenceAdapter.class);
        });
    }
}
