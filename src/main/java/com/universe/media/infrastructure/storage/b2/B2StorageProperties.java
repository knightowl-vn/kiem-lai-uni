package com.universe.media.infrastructure.storage.b2;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Validated configuration properties for Backblaze B2 storage.
 * <p>
 * This bean is active only when required B2 configuration properties are configured and non-blank.
 * When active, all required B2 configuration properties must be present and non-blank.
 */
@Component
@Conditional(B2StorageCondition.class)
public class B2StorageProperties {

    private final String bucket;
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;

    public B2StorageProperties(
            @Value("${media.storage.b2.bucket:}") String bucket,
            @Value("${media.storage.b2.endpoint:}") String endpoint,
            @Value("${media.storage.b2.access-key-id:}") String accessKeyId,
            @Value("${media.storage.b2.secret-access-key:}") String secretAccessKey,
            @Value("${media.storage.b2.region:}") String region
    ) {
        this.bucket = requireNonBlank(bucket, "media.storage.b2.bucket");
        this.endpoint = validateEndpoint(endpoint);
        this.accessKeyId = requireNonBlank(accessKeyId, "media.storage.b2.access-key-id");
        this.secretAccessKey = requireNonBlank(secretAccessKey, "media.storage.b2.secret-access-key");
        this.region = requireNonBlank(region, "media.storage.b2.region");
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalStateException("Backblaze B2 configuration [" + propertyName
                    + "] must not be blank when B2 storage provider is active.");
        }
        return value.trim();
    }

    private static String validateEndpoint(String endpoint) {
        String trimmed = requireNonBlank(endpoint, "media.storage.b2.endpoint");
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalStateException("Backblaze B2 endpoint must be a valid absolute URI: " + trimmed);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Backblaze B2 endpoint must be a valid URI: " + trimmed, e);
        }
        return trimmed;
    }

    public String bucket() {
        return bucket;
    }

    public String endpoint() {
        return endpoint;
    }

    public String accessKeyId() {
        return accessKeyId;
    }

    public String secretAccessKey() {
        return secretAccessKey;
    }

    public String region() {
        return region;
    }

    @Override
    public String toString() {
        return "B2StorageProperties{" +
                "bucket='" + bucket + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", accessKeyId='[PROTECTED]'" +
                ", secretAccessKey='[PROTECTED]'" +
                ", region='" + region + '\'' +
                '}';
    }
}
