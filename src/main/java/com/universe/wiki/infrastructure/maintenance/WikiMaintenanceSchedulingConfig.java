package com.universe.wiki.infrastructure.maintenance;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.context.annotation
        .Configuration;

import org.springframework.scheduling.annotation
        .EnableScheduling;


@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "wiki.image-cleanup.schedule.enabled",
        havingValue = "true"
)
public class WikiMaintenanceSchedulingConfig {
}