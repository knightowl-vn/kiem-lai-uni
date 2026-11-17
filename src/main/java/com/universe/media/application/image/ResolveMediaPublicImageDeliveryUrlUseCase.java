package com.universe.media.application.image;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative use case for resolving approved direct Cloudinary delivery URLs
 * for eligible public Media images (pilot).
 *
 * <p>Enforces:
 * <ul>
 *   <li>Asset must exist, be ACTIVE, PUBLIC, and of mediaType IMAGE;</li>
 *   <li>Must resolve against the exact current version number;</li>
 *   <li>Storage provider provenance must be Cloudinary;</li>
 *   <li>When a variant is requested, the variant must exist under the exact current version and have an approved public URL;</li>
 *   <li>When no variant is requested (source image), the current version must have an approved public URL;</li>
 *   <li>Public delivery URLs must use HTTPS and target approved Cloudinary domains;</li>
 *   <li>Fails closed (returns Optional.empty()) on any ineligibility, mismatch, or unexpected input without leaking internals.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class ResolveMediaPublicImageDeliveryUrlUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResolveMediaPublicImageDeliveryUrlUseCase.class);

    public static final StorageProviderId CLOUDINARY_PROVIDER_ID = StorageProviderId.of("cloudinary");
    private static final String CLOUDINARY_HOST_SUFFIX = ".cloudinary.com";
    private static final String CLOUDINARY_CANONICAL_HOST = "res.cloudinary.com";

    private final MediaAssetRepositoryPort mediaAssetRepositoryPort;
    private final MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;
    private final MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;
    private final MediaPublicImageBatchDeliveryQueryPort mediaPublicImageBatchDeliveryQueryPort;

    @Autowired
    public ResolveMediaPublicImageDeliveryUrlUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort,
            MediaPublicImageBatchDeliveryQueryPort mediaPublicImageBatchDeliveryQueryPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null.");
        this.mediaImageVariantRepositoryPort = Objects.requireNonNull(mediaImageVariantRepositoryPort, "MediaImageVariantRepositoryPort cannot be null.");
        this.mediaPublicImageBatchDeliveryQueryPort = mediaPublicImageBatchDeliveryQueryPort;
    }

    public ResolveMediaPublicImageDeliveryUrlUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort
    ) {
        this(mediaAssetRepositoryPort, mediaAssetVersionRepositoryPort, mediaImageVariantRepositoryPort, null);
    }

    public Map<UUID, String> executeBatch(Collection<UUID> assetIds, Integer targetWidth) {
        return executeBatch(assetIds, targetWidth, false);
    }

    public Map<UUID, String> executeBatch(Collection<UUID> assetIds, String variantKey) {
        return executeBatch(assetIds, variantKey, false);
    }

    public Map<UUID, String> executeBatch(Collection<UUID> assetIds, Integer targetWidth, boolean fallbackToSource) {
        String variantKey = targetWidth != null ? "w" + targetWidth : null;
        return executeBatch(assetIds, variantKey, fallbackToSource);
    }

    public Map<UUID, String> executeBatch(Collection<UUID> assetIds, String variantKey, boolean fallbackToSource) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<UUID> distinctIds = assetIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (distinctIds.isEmpty()) {
            return Collections.emptyMap();
        }

        if (mediaPublicImageBatchDeliveryQueryPort == null) {
            throw new IllegalStateException("MediaPublicImageBatchDeliveryQueryPort is not configured or available.");
        }

        String normalizedVariantKey = (variantKey != null && !variantKey.isBlank())
                ? variantKey.trim().toLowerCase(Locale.ROOT)
                : null;

        List<MediaPublicImageBatchDeliveryQueryPort.MediaPublicImageBatchDeliveryRow> rows =
                mediaPublicImageBatchDeliveryQueryPort.findEligibleBatchDeliveryRows(distinctIds, normalizedVariantKey);

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<UUID, String> resultMap = new LinkedHashMap<>();

        for (MediaPublicImageBatchDeliveryQueryPort.MediaPublicImageBatchDeliveryRow row : rows) {
            if (row == null || row.assetId() == null) {
                continue;
            }
            if (!distinctIds.contains(row.assetId())) {
                continue;
            }
            if (row.mediaType() != MediaType.IMAGE
                    || row.status() != MediaAssetStatus.ACTIVE
                    || row.visibility() != MediaVisibility.PUBLIC
                    || row.currentVersionNumber() != row.versionNumber()
                    || !CLOUDINARY_PROVIDER_ID.equals(row.storageProviderId())) {
                continue;
            }

            String candidateUrl = null;
            if (normalizedVariantKey != null) {
                boolean hasVariantPublicUrl = row.isVariantPresent()
                        && row.variantPublicUrl() != null
                        && !row.variantPublicUrl().isBlank();
                if (hasVariantPublicUrl) {
                    candidateUrl = row.variantPublicUrl();
                } else if (fallbackToSource) {
                    candidateUrl = row.versionPublicUrl();
                } else {
                    continue;
                }
            } else {
                candidateUrl = row.versionPublicUrl();
            }

            if (candidateUrl == null || candidateUrl.isBlank()) {
                continue;
            }

            Optional<String> validatedUrl = validateCloudinaryPublicUrl(candidateUrl);
            validatedUrl.ifPresent(url -> resultMap.put(row.assetId(), url));
        }

        return Collections.unmodifiableMap(resultMap);
    }

    public Optional<String> execute(UUID assetId, Integer targetWidth) {
        return execute(assetId, targetWidth, false);
    }

    public Optional<String> execute(UUID assetId, String variantKey) {
        return execute(assetId, variantKey, false);
    }

    public Optional<String> execute(UUID assetId, Integer targetWidth, boolean fallbackToSource) {
        String variantKey = targetWidth != null ? "w" + targetWidth : null;
        return execute(assetId, variantKey, fallbackToSource);
    }

    public Optional<String> execute(UUID assetId, String variantKey, boolean fallbackToSource) {
        if (assetId == null) {
            return Optional.empty();
        }

        // 1. Authoritative MediaAsset gating: must be IMAGE, ACTIVE, PUBLIC
        Optional<MediaAsset> assetOpt = mediaAssetRepositoryPort.findById(assetId);
        if (assetOpt.isEmpty()) {
            return Optional.empty();
        }
        MediaAsset asset = assetOpt.get();
        if (asset.getMediaType() != MediaType.IMAGE
                || asset.getStatus() != MediaAssetStatus.ACTIVE
                || asset.getVisibility() != MediaVisibility.PUBLIC) {
            return Optional.empty();
        }

        // 2. Exact current version resolution
        int currentVersionNumber = asset.getCurrentVersionNumber();
        Optional<MediaAssetVersion> versionOpt = mediaAssetVersionRepositoryPort
                .findByAssetIdAndVersionNumber(assetId, currentVersionNumber);
        if (versionOpt.isEmpty()) {
            return Optional.empty();
        }
        MediaAssetVersion currentVersion = versionOpt.get();

        // 3. Storage provider provenance validation
        StorageLocation location = currentVersion.getStorageLocation();
        if (location == null || !CLOUDINARY_PROVIDER_ID.equals(location.providerId())) {
            return Optional.empty();
        }

        // 4. Resolve candidate public URL
        String candidateUrl = null;
        if (variantKey != null && !variantKey.isBlank()) {
            String normalizedVariantKey = variantKey.trim().toLowerCase(Locale.ROOT);
            Optional<MediaImageVariant> variantOpt = mediaImageVariantRepositoryPort
                    .findByVersionIdAndVariantKey(currentVersion.getId(), normalizedVariantKey);
            if (variantOpt.isPresent() && variantOpt.get().hasPublicUrl()) {
                candidateUrl = variantOpt.get().getPublicUrl();
            } else if (fallbackToSource) {
                candidateUrl = currentVersion.getPublicUrl();
            } else {
                return Optional.empty();
            }
        } else {
            candidateUrl = currentVersion.getPublicUrl();
        }

        if (candidateUrl == null || candidateUrl.isBlank()) {
            return Optional.empty();
        }

        // 5. Strict security and Cloudinary domain validation
        return validateCloudinaryPublicUrl(candidateUrl);
    }

    private Optional<String> validateCloudinaryPublicUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                log.warn("Rejected direct image URL: not an absolute HTTPS URI");
                return Optional.empty();
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("Rejected direct image URL: missing host");
                return Optional.empty();
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!normalizedHost.equals(CLOUDINARY_CANONICAL_HOST) && !normalizedHost.endsWith(CLOUDINARY_HOST_SUFFIX)) {
                log.warn("Rejected direct image URL: host {} does not match approved Cloudinary domain", host);
                return Optional.empty();
            }
            return Optional.of(uri.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Rejected direct image URL: invalid URI syntax");
            return Optional.empty();
        }
    }
}
