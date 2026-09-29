package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
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

    public static final String WIKI_ARTICLE_COVER_TAG_PREFIX = "wiki.article.cover";
    public static final String IDENTITY_AVATAR_TAG_PREFIX = "identity.avatar";

    /**
     * Resolves the target storage provider ID for a new upload or version write.
     *
     * @param mediaType the MediaType of the asset (must not be null)
     * @param clientTag optional client tag for domain disambiguation
     * @return the destination StorageProviderId
     * @throws StorageException if the MediaType is not supported for write routing
     */
    public StorageProviderId resolveWriteProvider(MediaType mediaType, String clientTag) {
        Objects.requireNonNull(mediaType, "MediaType cannot be null for write routing.");

        return switch (mediaType) {
            case IMAGE -> CLOUDINARY_PROVIDER;
            case AUDIO -> R2_PROVIDER;
            case VIDEO, DOCUMENT -> throw new StorageException(
                    "Unsupported MediaType for storage write routing: " + mediaType
            );
        };
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

        if (assetId != null) {
            return StorageKey.of(folder + "/" + assetId + "_v" + versionNumber + "_" + uniqueNonce);
        }
        return StorageKey.of(folder + "/" + uniqueNonce);
    }

    private String resolveFolder(MediaType mediaType, String clientTag) {
        if (mediaType == MediaType.IMAGE) {
            if (clientTag != null) {
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
