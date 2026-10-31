package com.universe.media.infrastructure.storage.r2movie;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Validated configuration properties for Cloudflare R2 Movie storage (MS-08 R2-01).
 * <p>
 * This bean is active only when required Movie R2 configuration properties are configured and non-blank.
 * When active, all required Movie R2 configuration properties must be present and non-blank.
 */
@Component
@Conditional(R2MovieStorageCondition.class)
public class R2MovieStorageProperties {

    private final String bucket;
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;

    public R2MovieStorageProperties(
            @Value("${media.storage.r2-movie.bucket:}") String bucket,
            @Value("${media.storage.r2-movie.endpoint:}") String endpoint,
            @Value("${media.storage.r2-movie.access-key-id:}") String accessKeyId,
            @Value("${media.storage.r2-movie.secret-access-key:}") String secretAccessKey,
            @Value("${media.storage.r2-movie.region:auto}") String region
    ) {
        this.bucket = requireNonBlank(bucket, "media.storage.r2-movie.bucket");
        this.endpoint = validateEndpoint(endpoint);
        this.accessKeyId = requireNonBlank(accessKeyId, "media.storage.r2-movie.access-key-id");
        this.secretAccessKey = requireNonBlank(secretAccessKey, "media.storage.r2-movie.secret-access-key");
        this.region = (region == null || region.isBlank()) ? "auto" : region.trim();
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalStateException("Cloudflare R2 Movie configuration [" + propertyName
                    + "] must not be blank when Movie R2 storage provider is active.");
        }
        return value.trim();
    }

    private static String validateEndpoint(String endpoint) {
        String trimmed = requireNonBlank(endpoint, "media.storage.r2-movie.endpoint");
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalStateException("Cloudflare R2 Movie endpoint must be a valid absolute URI: " + trimmed);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cloudflare R2 Movie endpoint must be a valid URI: " + trimmed, e);
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
        return "R2MovieStorageProperties{" +
                "bucket='" + bucket + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", accessKeyId='[PROTECTED]'" +
                ", secretAccessKey='[PROTECTED]'" +
                ", region='" + region + '\'' +
                '}';
    }
}
