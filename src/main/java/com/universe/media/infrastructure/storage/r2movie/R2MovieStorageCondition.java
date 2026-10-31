package com.universe.media.infrastructure.storage.r2movie;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring condition to activate Movie R2 storage infrastructure only when all required
 * Movie R2 properties are configured and non-blank.
 */
public class R2MovieStorageCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String bucket = env.getProperty("media.storage.r2-movie.bucket");
        String endpoint = env.getProperty("media.storage.r2-movie.endpoint");
        String accessKeyId = env.getProperty("media.storage.r2-movie.access-key-id");
        String secretAccessKey = env.getProperty("media.storage.r2-movie.secret-access-key");

        return isNonBlank(bucket)
                && isNonBlank(endpoint)
                && isNonBlank(accessKeyId)
                && isNonBlank(secretAccessKey);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
