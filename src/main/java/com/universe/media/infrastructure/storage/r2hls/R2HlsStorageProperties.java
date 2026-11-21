package com.universe.media.infrastructure.storage.r2hls;

import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Validated configuration properties for Cloudflare R2 HLS Artifact Storage (MS-08 HLS-2B2B).
 * <p>
 * Dormant by default via {@link R2HlsStorageCondition}. When explicitly enabled, all required
 * credentials, bucket, and endpoint must be non-blank and syntactically valid; otherwise fails
 * closed with {@link IllegalStateException}.
 */
@Component
@Conditional(R2HlsStorageCondition.class)
public class R2HlsStorageProperties {

    public static final long DEFAULT_MAX_ARTIFACT_SIZE_BYTES = 64L * 1024L * 1024L; // 64 MiB
    public static final int DEFAULT_MAX_CONCURRENT_UPLOADS = 4;
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_TOTAL_ATTEMPT_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final int DEFAULT_MAX_VERIFICATION_COUNT = 5000;
    public static final int DEFAULT_PURGE_MAX_DURATION_SECONDS = 60;
    public static final int DEFAULT_PURGE_BATCH_SIZE = 1000;
    public static final int DEFAULT_PURGE_MAX_KEYS = 5000;

    private final String bucket;
    private final String endpoint;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;
    private final int maxConcurrentUploads;
    private final Duration requestTimeout;
    private final Duration totalAttemptTimeout;
    private final int maxRetries;
    private final long maxArtifactSizeBytes;
    private final int maxVerificationCount;
    private final Duration purgeMaxDuration;
    private final int purgeBatchSize;
    private final int purgeMaxKeys;

    private final boolean requireFullSegmentIntegrityVerification;

    public R2HlsStorageProperties(
            @Value("${media.storage.r2-hls.bucket:}") String bucket,
            @Value("${media.storage.r2-hls.endpoint:}") String endpoint,
            @Value("${media.storage.r2-hls.access-key-id:}") String accessKeyId,
            @Value("${media.storage.r2-hls.secret-access-key:}") String secretAccessKey,
            @Value("${media.storage.r2-hls.region:auto}") String region,
            @Value("${media.storage.r2-hls.max-concurrent-uploads:4}") int maxConcurrentUploads,
            @Value("${media.storage.r2-hls.request-timeout-seconds:30}") int requestTimeoutSeconds,
            @Value("${media.storage.r2-hls.total-attempt-timeout-seconds:120}") int totalAttemptTimeoutSeconds,
            @Value("${media.storage.r2-hls.max-retries:3}") int maxRetries,
            @Value("${media.storage.r2-hls.max-artifact-size-bytes:67108864}") long maxArtifactSizeBytes,
            @Value("${media.storage.r2-hls.max-verification-count:5000}") int maxVerificationCount,
            @Value("${media.storage.r2-hls.purge-max-duration-seconds:60}") int purgeMaxDurationSeconds,
            @Value("${media.storage.r2-hls.purge-batch-size:1000}") int purgeBatchSize,
            @Value("${media.storage.r2-hls.purge-max-keys:5000}") int purgeMaxKeys,
            @Value("${media.storage.r2-hls.require-full-segment-integrity-verification:false}") boolean requireFullSegmentIntegrityVerification
    ) {
        this.bucket = HlsPackageKeyValidator.validateBucketName(requireNonBlank(bucket, "media.storage.r2-hls.bucket"));
        this.endpoint = validateEndpoint(endpoint);
        this.accessKeyId = requireNonBlank(accessKeyId, "media.storage.r2-hls.access-key-id");
        this.secretAccessKey = requireNonBlank(secretAccessKey, "media.storage.r2-hls.secret-access-key");
        this.region = (region == null || region.isBlank()) ? "auto" : region.trim();
        this.requireFullSegmentIntegrityVerification = requireFullSegmentIntegrityVerification;

        if (maxConcurrentUploads < 1 || maxConcurrentUploads > 16) {
            throw new IllegalStateException("maxConcurrentUploads must be between 1 and 16, got: " + maxConcurrentUploads);
        }
        this.maxConcurrentUploads = maxConcurrentUploads;

        if (requestTimeoutSeconds < 1) {
            throw new IllegalStateException("requestTimeoutSeconds must be >= 1, got: " + requestTimeoutSeconds);
        }
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);

        if (totalAttemptTimeoutSeconds < requestTimeoutSeconds) {
            throw new IllegalStateException("totalAttemptTimeoutSeconds must be >= requestTimeoutSeconds, got: " + totalAttemptTimeoutSeconds);
        }
        this.totalAttemptTimeout = Duration.ofSeconds(totalAttemptTimeoutSeconds);

        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalStateException("maxRetries must be between 0 and 10, got: " + maxRetries);
        }
        this.maxRetries = maxRetries;

        if (maxArtifactSizeBytes < 1024L || maxArtifactSizeBytes > 256L * 1024L * 1024L) {
            throw new IllegalStateException("maxArtifactSizeBytes must be between 1KB and 256MB, got: " + maxArtifactSizeBytes);
        }
        this.maxArtifactSizeBytes = maxArtifactSizeBytes;

        if (maxVerificationCount < 4 || maxVerificationCount > 50000) {
            throw new IllegalStateException("maxVerificationCount must be between 4 and 50000, got: " + maxVerificationCount);
        }
        this.maxVerificationCount = maxVerificationCount;

        if (purgeMaxDurationSeconds < 1) {
            throw new IllegalStateException("purgeMaxDurationSeconds must be >= 1, got: " + purgeMaxDurationSeconds);
        }
        this.purgeMaxDuration = Duration.ofSeconds(purgeMaxDurationSeconds);

        if (purgeBatchSize < 1 || purgeBatchSize > 1000) {
            throw new IllegalStateException("purgeBatchSize must be between 1 and 1000, got: " + purgeBatchSize);
        }
        this.purgeBatchSize = purgeBatchSize;

        if (purgeMaxKeys < 1) {
            throw new IllegalStateException("purgeMaxKeys must be >= 1, got: " + purgeMaxKeys);
        }
        this.purgeMaxKeys = purgeMaxKeys;
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalStateException("Cloudflare R2 HLS configuration [" + propertyName
                    + "] must not be blank when R2 HLS storage provider is active.");
        }
        return value.trim();
    }

    private static String validateEndpoint(String endpoint) {
        String trimmed = requireNonBlank(endpoint, "media.storage.r2-hls.endpoint");
        try {
            URI uri = URI.create(trimmed);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw new IllegalStateException("Cloudflare R2 HLS endpoint must be an absolute URI: " + trimmed);
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme)) {
                throw new IllegalStateException("Cloudflare R2 HLS endpoint must use HTTPS scheme, got: " + scheme);
            }
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                throw new IllegalStateException("Cloudflare R2 HLS endpoint must not contain user-info credentials");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("Cloudflare R2 HLS endpoint must contain a valid host: " + trimmed);
            }
            if (host.contains("/") || host.contains("\\") || host.contains("..")) {
                throw new IllegalStateException("Cloudflare R2 HLS endpoint host contains invalid characters: " + host);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cloudflare R2 HLS endpoint must be a valid URI: " + trimmed, e);
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

    public int maxConcurrentUploads() {
        return maxConcurrentUploads;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration totalAttemptTimeout() {
        return totalAttemptTimeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long maxArtifactSizeBytes() {
        return maxArtifactSizeBytes;
    }

    public int maxVerificationCount() {
        return maxVerificationCount;
    }

    public Duration purgeMaxDuration() {
        return purgeMaxDuration;
    }

    public int purgeBatchSize() {
        return purgeBatchSize;
    }

    public int purgeMaxKeys() {
        return purgeMaxKeys;
    }

    public boolean requireFullSegmentIntegrityVerification() {
        return requireFullSegmentIntegrityVerification;
    }

    @Override
    public String toString() {
        return "R2HlsStorageProperties{" +
                "bucket='" + bucket + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", accessKeyId='[PROTECTED]'" +
                ", secretAccessKey='[PROTECTED]'" +
                ", region='" + region + '\'' +
                ", maxConcurrentUploads=" + maxConcurrentUploads +
                ", requestTimeout=" + requestTimeout +
                ", totalAttemptTimeout=" + totalAttemptTimeout +
                ", maxRetries=" + maxRetries +
                ", maxArtifactSizeBytes=" + maxArtifactSizeBytes +
                ", maxVerificationCount=" + maxVerificationCount +
                ", purgeMaxDuration=" + purgeMaxDuration +
                ", purgeBatchSize=" + purgeBatchSize +
                ", purgeMaxKeys=" + purgeMaxKeys +
                ", requireFullSegmentIntegrityVerification=" + requireFullSegmentIntegrityVerification +
                '}';
    }
}
