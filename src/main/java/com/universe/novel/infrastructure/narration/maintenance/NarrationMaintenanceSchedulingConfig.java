package com.universe.novel.infrastructure.narration.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "novel.narration.media-cleanup.schedule.enabled",
        havingValue = "true"
)
public class NarrationMaintenanceSchedulingConfig {
}
