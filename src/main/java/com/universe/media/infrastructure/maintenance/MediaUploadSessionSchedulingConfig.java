package com.universe.media.infrastructure.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling configuration for media upload session cleanup (MS-08E2-L3-2).
 * <p>
 * Only enables scheduling when {@code media.upload-session-cleanup.schedule.enabled=true}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "media.upload-session-cleanup.schedule.enabled",
        havingValue = "true"
)
public class MediaUploadSessionSchedulingConfig {
}
