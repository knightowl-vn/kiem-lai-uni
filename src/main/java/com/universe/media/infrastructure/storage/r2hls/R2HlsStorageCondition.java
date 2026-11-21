package com.universe.media.infrastructure.storage.r2hls;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring condition to activate R2 HLS storage infrastructure only when explicitly enabled
 * via {@code media.hls.storage.r2.enabled=true}.
 * <p>
 * Dormant by default. Incomplete configuration when enabled will fail closed during bean
 * instantiation in {@link R2HlsStorageProperties}.
 */
public class R2HlsStorageCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String enabled = env.getProperty("media.hls.storage.r2.enabled");
        return "true".equalsIgnoreCase(enabled);
    }
}
