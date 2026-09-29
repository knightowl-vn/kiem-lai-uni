package com.universe.media.infrastructure.storage.cloudinary;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring condition to activate Media Cloudinary storage adapter only when all required
 * Cloudinary credentials are configured and non-blank.
 */
public class CloudinaryStorageCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String cloudName = getFirstNonBlank(env, "cloudinary.cloud-name", "cloudinary.cloud_name", "CLOUDINARY_CLOUD_NAME");
        String apiKey = getFirstNonBlank(env, "cloudinary.api-key", "cloudinary.api_key", "CLOUDINARY_API_KEY");
        String apiSecret = getFirstNonBlank(env, "cloudinary.api-secret", "cloudinary.api_secret", "CLOUDINARY_API_SECRET");

        return cloudName != null && apiKey != null && apiSecret != null;
    }

    private static String getFirstNonBlank(Environment env, String... propertyNames) {
        for (String propertyName : propertyNames) {
            String value = env.getProperty(propertyName);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
