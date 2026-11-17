package com.universe.media.application.image;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

    public ResolveMediaPublicImageDeliveryUrlUseCase(
            MediaAssetRepositoryPort mediaAssetRepositoryPort,
            MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort,
            MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort
    ) {
        this.mediaAssetRepositoryPort = Objects.requireNonNull(mediaAssetRepositoryPort, "MediaAssetRepositoryPort cannot be null.");
        this.mediaAssetVersionRepositoryPort = Objects.requireNonNull(mediaAssetVersionRepositoryPort, "MediaAssetVersionRepositoryPort cannot be null.");
        this.mediaImageVariantRepositoryPort = Objects.requireNonNull(mediaImageVariantRepositoryPort, "MediaImageVariantRepositoryPort cannot be null.");
    }

    public Optional<String> execute(UUID assetId, Integer targetWidth) {
        String variantKey = targetWidth != null ? "w" + targetWidth : null;
        return execute(assetId, variantKey);
    }

    public Optional<String> execute(UUID assetId, String variantKey) {
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
        String candidateUrl;
        if (variantKey != null && !variantKey.isBlank()) {
            String normalizedVariantKey = variantKey.trim().toLowerCase(Locale.ROOT);
            Optional<MediaImageVariant> variantOpt = mediaImageVariantRepositoryPort
                    .findByVersionIdAndVariantKey(currentVersion.getId(), normalizedVariantKey);
            if (variantOpt.isEmpty() || !variantOpt.get().hasPublicUrl()) {
                return Optional.empty();
            }
            candidateUrl = variantOpt.get().getPublicUrl();
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
