package com.universe.interaction.infrastructure.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring task scheduling for Interaction maintenance background jobs
 * when {@code interaction.report-retention.schedule.enabled=true}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "interaction.report-retention.schedule.enabled",
        havingValue = "true"
)
public class InteractionMaintenanceSchedulingConfig {
}
