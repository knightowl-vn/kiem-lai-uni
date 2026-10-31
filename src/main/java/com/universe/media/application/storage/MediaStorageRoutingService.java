package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageProviderId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Application service for resolving storage write destination providers and namespace keys based on domain policy.
 */
@Service
public class MediaStorageRoutingService {

    public static final StorageProviderId CLOUDINARY_PROVIDER = StorageProviderId.of("cloudinary");
    public static final StorageProviderId R2_PROVIDER = StorageProviderId.of("r2");
    public static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    public static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");

    public static final String WIKI_ARTICLE_COVER_TAG_PREFIX = "wiki.article.cover";
    public static final String IDENTITY_AVATAR_TAG_PREFIX = "identity.avatar";
    public static final String SCREEN_POSTER_TAG_PREFIX = "screen.poster";
    public static final String SCREEN_BACKDROP_TAG_PREFIX = "screen.backdrop";
    public static final String SCREEN_TAG_PREFIX = "screen.";

    private final String b2Bucket;
    private final String r2Bucket;
    private final String r2MovieBucket;
    private final boolean movieR2Enabled;

    @Autowired
    public MediaStorageRoutingService(
            @Value("${media.storage.b2.bucket:}") String b2Bucket,
            @Value("${media.storage.r2.bucket:}") String r2Bucket,
            @Value("${media.storage.r2-movie.bucket:}") String r2MovieBucket,
            @Value("${media.storage.routing.movie-r2-enabled:false}") boolean movieR2Enabled
    ) {
        this.b2Bucket = (b2Bucket != null && !b2Bucket.isBlank()) ? b2Bucket.trim() : null;
        this.r2Bucket = (r2Bucket != null && !r2Bucket.isBlank()) ? r2Bucket.trim() : null;
        this.r2MovieBucket = (r2MovieBucket != null && !r2MovieBucket.isBlank()) ? r2MovieBucket.trim() : null;
        this.movieR2Enabled = movieR2Enabled;
    }

    public MediaStorageRoutingService(String b2Bucket, String r2Bucket) {
        this(b2Bucket, r2Bucket, null, false);
    }

    /**
     * Resolves the canonical bucket name for the specified storage provider.
     * Providers requiring explicit buckets (e.g. b2) return their configured bucket name.
     * Bucketless providers return {@link StorageKeyAllocation#CANONICAL_BUCKETLESS_SENTINEL}.
     *
     * <p>Fails closed if the provider requires an authentic bucket that has not been configured.
     * Never returns the bucketless sentinel for B2.
     *
     * @param providerId the storage provider ID (must not be null)
     * @return the authoritative bucket name or canonical bucketless sentinel
     * @throws IllegalStateException if the provider requires an authentic bucket that is missing or sentinel
     */
    public String resolveBucketName(StorageProviderId providerId) {
        Objects.requireNonNull(providerId, "StorageProviderId cannot be null.");
        if (B2_PROVIDER.equals(providerId)) {
            if (this.b2Bucket == null || this.b2Bucket.isBlank()
                    || StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(this.b2Bucket)) {
                throw new IllegalStateException(
                        "Storage provider 'b2' requires an authentic configured bucket ('media.storage.b2.bucket'), but none was configured."
                );
            }
            return this.b2Bucket;
        }
        if (R2_MOVIE_PROVIDER.equals(providerId)) {
            if (this.r2MovieBucket == null || this.r2MovieBucket.isBlank()
                    || StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(this.r2MovieBucket)) {
                throw new IllegalStateException(
                        "Storage provider 'r2-movie' requires an authentic configured bucket ('media.storage.r2-movie.bucket'), but none was configured."
                );
            }
            return this.r2MovieBucket;
        }
        if (R2_PROVIDER.equals(providerId)) {
            if (this.r2Bucket != null && !this.r2Bucket.isBlank()
                    && !StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(this.r2Bucket)) {
                return this.r2Bucket;
            }
            return StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL;
        }
        return StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL;
    }

    /**
     * Resolves the target storage provider ID for a new upload or version write with authoritative consumer context.
     *
     * @param mediaType       the MediaType of the asset (must not be null)
     * @param consumerContext optional consumer context (e.g. "screen")
     * @param clientTag       optional client tag for domain disambiguation (e.g. "screen-video", "screen-replace:<UUID>")
     * @return the destination StorageProviderId
     * @throws StorageException if the MediaType is not supported for write routing
     */
    public StorageProviderId resolveWriteProvider(MediaType mediaType, String consumerContext, String clientTag) {
        Objects.requireNonNull(mediaType, "MediaType cannot be null for write routing.");

        return switch (mediaType) {
            case IMAGE -> CLOUDINARY_PROVIDER;
            case AUDIO -> R2_PROVIDER;
            case VIDEO -> (movieR2Enabled && isScreenVideo(consumerContext, clientTag)) ? R2_MOVIE_PROVIDER : B2_PROVIDER;
            case DOCUMENT -> throw new StorageException(
                    "Unsupported MediaType for storage write routing: " + mediaType
            );
        };
    }

    /**
     * Resolves the target storage provider ID for a new upload or version write.
     *
     * @param mediaType the MediaType of the asset (must not be null)
     * @param clientTag optional client tag for domain disambiguation
     * @return the destination StorageProviderId
     * @throws StorageException if the MediaType is not supported for write routing
     */
    public StorageProviderId resolveWriteProvider(MediaType mediaType, String clientTag) {
        return resolveWriteProvider(mediaType, null, clientTag);
    }

    /**
     * Authoritative validator for Screen client tags.
     * Recognizes strictly:
     * <ul>
     *     <li>{@code "screen-video"}</li>
     *     <li>{@code "screen-replace:<valid-canonical-UUID>"}</li>
     * </ul>
     * Rejects null, blank, arbitrary substrings, or malformed UUIDs.
     */
    public static boolean isValidScreenClientTag(String clientTag) {
        if (clientTag == null || clientTag.isBlank()) {
            return false;
        }
        if ("screen-video".equals(clientTag)) {
            return true;
        }
        if (clientTag.startsWith("screen-replace:")) {
            String rawUuid = clientTag.substring("screen-replace:".length()).trim();
            if (rawUuid.length() != 36) {
                return false;
            }
            try {
                UUID parsed = UUID.fromString(rawUuid);
                return parsed.toString().equalsIgnoreCase(rawUuid);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Checks if the consumer context represents an authorized Screen consumer.
     */
    public static boolean isScreenConsumer(String consumerContext) {
        return consumerContext != null && "screen".equalsIgnoreCase(consumerContext.trim());
    }

    /**
     * Evaluates whether an operation is an authorized Screen video write.
     * Strictly requires BOTH:
     * 1. Authoritative consumer context must be the authorized Screen consumer ("screen").
     *    A null, blank, or non-Screen consumer is strictly rejected.
     * 2. clientTag must be an approved Screen tag ("screen-video" or "screen-replace:<valid-canonical-UUID>").
     */
    private boolean isScreenVideo(String consumerContext, String clientTag) {
        if (!isScreenConsumer(consumerContext)) {
            return false;
        }
        return isValidScreenClientTag(clientTag);
    }

    /**
     * Generates a globally unique, domain-appropriate StorageKey for an asset write (initial or version replacement).
     *
     * @param mediaType     the MediaType of the asset (must not be null)
     * @param clientTag     optional client tag
     * @param assetId       asset UUID if already known, or null for brand new initial uploads
     * @param versionNumber the target version number (e.g. 1 for initial, N+1 for replacement)
     * @return a unique canonical StorageKey
     */
    public StorageKey generateStorageKey(
            MediaType mediaType,
            String clientTag,
            UUID assetId,
            int versionNumber
    ) {
        Objects.requireNonNull(mediaType, "MediaType cannot be null.");

        String folder = resolveFolder(mediaType, clientTag);
        UUID uniqueNonce = UUID.randomUUID();

        String slugPrefix = extractSlugPrefix(clientTag);
        String filePrefix = slugPrefix != null ? slugPrefix + "-" : "";

        if (assetId != null) {
            return StorageKey.of(folder + "/" + filePrefix + assetId + "_v" + versionNumber + "_" + uniqueNonce);
        }
        return StorageKey.of(folder + "/" + filePrefix + uniqueNonce);
    }

    private static String extractSlugPrefix(String clientTag) {
        if (clientTag == null || !clientTag.startsWith(SCREEN_TAG_PREFIX) || !clientTag.contains(":")) {
            return null;
        }
        int colonIdx = clientTag.indexOf(':');
        String rawSlug = clientTag.substring(colonIdx + 1).trim().toLowerCase(java.util.Locale.ROOT);
        String sanitized = rawSlug.replaceAll("[^a-z0-9-]", "-").replaceAll("(^-+)|(-+$)", "");
        if (sanitized.isBlank()) {
            return null;
        }
        return (sanitized.length() > 40) ? sanitized.substring(0, 40).replaceAll("-+$", "") : sanitized;
    }

    private String resolveFolder(MediaType mediaType, String clientTag) {
        if (mediaType == MediaType.IMAGE) {
            if (clientTag != null) {
                if (clientTag.startsWith(SCREEN_POSTER_TAG_PREFIX)) {
                    return "kiemlai/screen/posters";
                }
                if (clientTag.startsWith(SCREEN_BACKDROP_TAG_PREFIX)) {
                    return "kiemlai/screen/backdrops";
                }
                if (clientTag.startsWith(SCREEN_TAG_PREFIX)) {
                    return "kiemlai/screen";
                }
                if (clientTag.startsWith(WIKI_ARTICLE_COVER_TAG_PREFIX)) {
                    return "kiemlai/wiki/covers";
                }
                if (clientTag.startsWith(IDENTITY_AVATAR_TAG_PREFIX)) {
                    return "kiemlai/avatars";
                }
                if (clientTag.startsWith("wiki.")) {
                    return "kiemlai/wiki";
                }
                if (clientTag.startsWith("novel.")) {
                    return "kiemlai/novel";
                }
            }
            return "kiemlai/images";
        }

        if (mediaType == MediaType.AUDIO) {
            if (clientTag != null && clientTag.startsWith("novel.")) {
                return "objects/novel";
            }
            return "objects";
        }

        return "objects";
    }
}
