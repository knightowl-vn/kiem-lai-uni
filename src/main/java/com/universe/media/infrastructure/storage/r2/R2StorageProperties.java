package com.universe.media.infrastructure.storage.r2;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Validated configuration properties for Cloudflare R2 storage.
 * <p>
 * This bean is active only when {@code media.storage.provider=r2}.
 * When active, all required R2 configuration properties must be present and non-blank.
 */
@Component
@ConditionalOnProperty(
        name = "media.storage.provider",
        havingValue = "r2"
)
public class R2StorageProperties {

    private final String bucket;
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;

    public R2StorageProperties(
            @Value("${media.storage.r2.bucket:}") String bucket,
            @Value("${media.storage.r2.endpoint:}") String endpoint,
            @Value("${media.storage.r2.access-key-id:}") String accessKeyId,
            @Value("${media.storage.r2.secret-access-key:}") String secretAccessKey,
            @Value("${media.storage.r2.region:auto}") String region
    ) {
        this.bucket = requireNonBlank(bucket, "media.storage.r2.bucket");
        this.endpoint = validateEndpoint(endpoint);
        this.accessKeyId = requireNonBlank(accessKeyId, "media.storage.r2.access-key-id");
        this.secretAccessKey = requireNonBlank(secretAccessKey, "media.storage.r2.secret-access-key");
        this.region = (region == null || region.isBlank()) ? "auto" : region.trim();
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalStateException("Cloudflare R2 configuration [" + propertyName
                    + "] must not be blank when R2 storage provider is active.");
        }
        return value.trim();
    }

    private static String validateEndpoint(String endpoint) {
        String trimmed = requireNonBlank(endpoint, "media.storage.r2.endpoint");
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalStateException("Cloudflare R2 endpoint must be a valid absolute URI: " + trimmed);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cloudflare R2 endpoint must be a valid URI: " + trimmed, e);
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
        return "R2StorageProperties{" +
                "bucket='" + bucket + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", accessKeyId='[PROTECTED]'" +
                ", secretAccessKey='[PROTECTED]'" +
                ", region='" + region + '\'' +
                '}';
    }
}
