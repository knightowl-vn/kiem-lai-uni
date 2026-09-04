package com.universe.media.infrastructure.maintenance;

import com.universe.media.application.asset.PurgeExpiredDeletedMediaAssetsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MediaMaintenanceSchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MediaMaintenanceSchedulingConfig.class,
                    MediaDeletedAssetSweeperScheduler.class
            )
            .withBean(PurgeExpiredDeletedMediaAssetsUseCase.class, () -> mock(PurgeExpiredDeletedMediaAssetsUseCase.class));

    @Test
    @DisplayName("scheduler and scheduling config beans are absent by default when property is missing")
    void shouldBeDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MediaMaintenanceSchedulingConfig.class);
            assertThat(context).doesNotHaveBean(MediaDeletedAssetSweeperScheduler.class);
        });
    }

    @Test
    @DisplayName("scheduler and scheduling config beans are absent when property is false")
    void shouldBeDisabledWhenPropertyIsFalse() {
        contextRunner
                .withPropertyValues("media.deleted-asset-cleanup.schedule.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MediaMaintenanceSchedulingConfig.class);
                    assertThat(context).doesNotHaveBean(MediaDeletedAssetSweeperScheduler.class);
                });
    }

    @Test
    @DisplayName("scheduler and scheduling config beans are registered when property is true")
    void shouldBeEnabledWhenPropertyIsTrue() {
        contextRunner
                .withPropertyValues("media.deleted-asset-cleanup.schedule.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MediaMaintenanceSchedulingConfig.class);
                    assertThat(context).hasSingleBean(MediaDeletedAssetSweeperScheduler.class);
                });
    }
}
