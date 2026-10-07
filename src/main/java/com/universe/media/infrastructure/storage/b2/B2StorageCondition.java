package com.universe.media.infrastructure.storage.b2;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring condition to activate Backblaze B2 storage infrastructure only when all required
 * B2 properties are configured and non-blank.
 */
public class B2StorageCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String bucket = env.getProperty("media.storage.b2.bucket");
        String endpoint = env.getProperty("media.storage.b2.endpoint");
        String accessKeyId = env.getProperty("media.storage.b2.access-key-id");
        String secretAccessKey = env.getProperty("media.storage.b2.secret-access-key");
        String region = env.getProperty("media.storage.b2.region");

        return isNonBlank(bucket)
                && isNonBlank(endpoint)
                && isNonBlank(accessKeyId)
                && isNonBlank(secretAccessKey)
                && isNonBlank(region);
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
