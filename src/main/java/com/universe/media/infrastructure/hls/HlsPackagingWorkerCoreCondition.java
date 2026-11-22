package com.universe.media.infrastructure.hls;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring condition requiring BOTH {@code media.hls.packaging.enabled=true} AND
 * {@code media.hls.storage.r2.enabled=true} before assembling {@code ProcessClaimedHlsPackagingLeaseUseCase}.
 * Partial feature-flag activation never exposes an incomplete pipeline.
 */
public class HlsPackagingWorkerCoreCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        return "true".equalsIgnoreCase(env.getProperty("media.hls.packaging.enabled"))
                && "true".equalsIgnoreCase(env.getProperty("media.hls.storage.r2.enabled"));
    }
}
