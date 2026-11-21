package com.universe.media.application.ports.storage;

import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageKeyAllocation;
import com.universe.media.domain.StorageProviderId;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoritative validator for dedicated HLS package prefixes, immutable identity bindings,
 * trusted provider/bucket destinations, safe relative artifact paths, and narrow purge scopes.
 */
public final class HlsPackageKeyValidator {

    public static final StorageProviderId REQUIRED_PROVIDER_ID = StorageProviderId.of("r2-hls");
    public static final String PREFIX_ROOT = "objects/hls/";
    public static final Set<String> APPROVED_VIDEO_TIERS = Set.of("720p", "1080p", "2160p", "source");
    public static final int MAX_STORAGE_KEY_LENGTH = 500;

    public static final MimeType MIME_HLS_PLAYLIST = MimeType.of("application/vnd.apple.mpegurl");
    public static final MimeType MIME_FMP4_INIT = MimeType.of("video/mp4");
    public static final MimeType MIME_FMP4_SEGMENT = MimeType.of("video/iso.segment");

    private static final String CANONICAL_LOWER_UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern CANONICAL_PREFIX_PATTERN = Pattern.compile(
            "^objects/hls/(" + CANONICAL_LOWER_UUID_REGEX + ")/v([1-9][0-9]*)/(" + CANONICAL_LOWER_UUID_REGEX + ")/$"
    );
    private static final Pattern SAFE_INIT_NAME_PATTERN = Pattern.compile("^init[a-zA-Z0-9_-]*\\.mp4$");
    private static final Pattern SAFE_SEGMENT_NAME_PATTERN = Pattern.compile("^seg_[0-9]+\\.m4s$");

    public record ParsedCanonicalPrefix(
            UUID assetId,
            int sourceVersionNumber,
            UUID packageUuid,
            StorageKey basePrefix,
            StorageKey masterManifestKey
    ) {
    }

    public record ValidatedArtifactClassification(
            String relativePath,
            HlsArtifactType artifactType,
            MimeType contentType,
            HlsUploadStage uploadStage
    ) {
    }

    private HlsPackageKeyValidator() {
    }

    public static void validateRequiredProvider(StorageProviderId providerId) {
        Objects.requireNonNull(providerId, "StorageProviderId cannot be null.");
        if (!REQUIRED_PROVIDER_ID.equals(providerId)) {
            throw new IllegalArgumentException(
                    "HLS artifact operations require provider '" + REQUIRED_PROVIDER_ID.value()
                            + "', rejected: '" + providerId.value() + "'."
            );
        }
    }

    public static String validateBucketName(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("HLS storage bucket name cannot be blank.");
        }
        String trimmed = bucketName.trim();
        if (StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL.equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException(
                    "HLS storage bucket name cannot use bucketless sentinel '" + StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL + "'."
            );
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || hasControlOrWhitespace(trimmed)) {
            throw new IllegalArgumentException("Invalid HLS storage bucket name: " + trimmed);
        }
        return trimmed;
    }

    public static String validateTrustedDestination(
            StorageProviderId providerId,
            String suppliedBucketName,
            String configuredTrustedBucket
    ) {
        validateRequiredProvider(providerId);
        String trusted = validateBucketName(configuredTrustedBucket);
        String supplied = validateBucketName(suppliedBucketName);
        if (!trusted.equals(supplied)) {
            throw new IllegalArgumentException(
                    "HLS bucket mismatch: supplied='" + supplied + "', expected configured bucket='" + trusted + "'."
            );
        }
        return trusted;
    }

    public static StorageKey buildCanonicalPrefix(UUID assetId, int sourceVersionNumber, UUID packageUuid) {
        Objects.requireNonNull(assetId, "assetId cannot be null.");
        if (sourceVersionNumber < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1, got: " + sourceVersionNumber);
        }
        Objects.requireNonNull(packageUuid, "packageUuid cannot be null.");
        String canonical = PREFIX_ROOT
                + assetId.toString().toLowerCase(java.util.Locale.ROOT)
                + "/v" + sourceVersionNumber
                + "/" + packageUuid.toString().toLowerCase(java.util.Locale.ROOT) + "/";
        return StorageKey.of(canonical);
    }

    public static ParsedCanonicalPrefix parseAndValidateCanonicalPrefix(StorageKey basePrefix) {
        Objects.requireNonNull(basePrefix, "basePrefix cannot be null.");
        String raw = basePrefix.value();
        rejectUnsafeCharacters(raw, "HLS basePrefix");
        if (raw.startsWith("/") || !raw.endsWith("/")) {
            throw new IllegalArgumentException(
                    "HLS basePrefix must not start with '/' and must end with '/': " + raw
            );
        }
        Matcher matcher = CANONICAL_PREFIX_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "HLS basePrefix must match 'objects/hls/{assetUuid}/v{version>=1}/{packageUuid}/', got: " + raw
            );
        }
        UUID assetId = UUID.fromString(matcher.group(1));
        int version;
        try {
            version = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid sourceVersionNumber in HLS basePrefix: " + raw, e);
        }
        if (version < 1) {
            throw new IllegalArgumentException("sourceVersionNumber must be >= 1 in HLS basePrefix: " + raw);
        }
        UUID packageUuid = UUID.fromString(matcher.group(3));
        StorageKey masterKey = buildAndValidateObjectKey(basePrefix, "master.m3u8");
        return new ParsedCanonicalPrefix(assetId, version, packageUuid, basePrefix, masterKey);
    }

    public static ParsedCanonicalPrefix validatePrefixMatchesIdentity(
            UUID assetId,
            int sourceVersionNumber,
            UUID packageUuid,
            StorageKey suppliedBasePrefix
    ) {
        ParsedCanonicalPrefix parsed = parseAndValidateCanonicalPrefix(suppliedBasePrefix);
        StorageKey expected = buildCanonicalPrefix(assetId, sourceVersionNumber, packageUuid);
        if (!expected.equals(parsed.basePrefix())) {
            throw new IllegalArgumentException(
                    "HLS basePrefix does not match package identity: expected='" + expected.value()
                            + "', supplied='" + suppliedBasePrefix.value() + "'."
            );
        }
        return parsed;
    }

    public static ParsedCanonicalPrefix validatePurgeScope(
            StorageProviderId providerId,
            String bucketName,
            StorageKey validatedBasePrefix
    ) {
        validateRequiredProvider(providerId);
        validateBucketName(bucketName);
        return parseAndValidateCanonicalPrefix(validatedBasePrefix);
    }

    public static ParsedCanonicalPrefix validatePurgeScope(
            StorageProviderId providerId,
            String suppliedBucketName,
            String configuredTrustedBucket,
            StorageKey validatedBasePrefix
    ) {
        validateTrustedDestination(providerId, suppliedBucketName, configuredTrustedBucket);
        return parseAndValidateCanonicalPrefix(validatedBasePrefix);
    }

    public static ValidatedArtifactClassification validateAndClassifyRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new IllegalArgumentException("HLS relative artifact path cannot be null or empty.");
        }
        rejectUnsafeCharacters(relativePath, "HLS relative artifact path");
        if (relativePath.startsWith("/") || relativePath.endsWith("/") || relativePath.contains("//")) {
            throw new IllegalArgumentException("Malformed HLS relative artifact path: " + relativePath);
        }

        if ("master.m3u8".equals(relativePath)) {
            return new ValidatedArtifactClassification(
                    relativePath,
                    HlsArtifactType.MASTER_MANIFEST,
                    MIME_HLS_PLAYLIST,
                    HlsUploadStage.STAGE_3_MASTER_MANIFEST
            );
        }

        String[] parts = relativePath.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part) || part.startsWith(".")) {
                throw new IllegalArgumentException("Unsafe or hidden path segment in HLS artifact path: " + relativePath);
            }
        }

        if (parts.length != 3) {
            throw new IllegalArgumentException("Unsupported HLS artifact path layout: " + relativePath);
        }

        String rootFolder = parts[0];
        String renditionDir = parts[1];
        String fileName = parts[2];

        if ("video".equals(rootFolder)) {
            if (!APPROVED_VIDEO_TIERS.contains(renditionDir)) {
                throw new IllegalArgumentException("Unapproved HLS video tier '" + renditionDir + "' in: " + relativePath);
            }
            return classifyMediaRenditionFile(relativePath, fileName);
        }
        if ("audio".equals(rootFolder)) {
            if (!"main".equals(renditionDir)) {
                throw new IllegalArgumentException("Only single shared 'audio/main' rendition is allowed, got: " + relativePath);
            }
            return classifyMediaRenditionFile(relativePath, fileName);
        }

        throw new IllegalArgumentException("Unsupported HLS root directory in path: " + relativePath);
    }

    public static StorageKey buildAndValidateObjectKey(StorageKey validatedBasePrefix, String relativePath) {
        Objects.requireNonNull(validatedBasePrefix, "validatedBasePrefix cannot be null.");
        String prefix = validatedBasePrefix.value();
        rejectUnsafeCharacters(prefix, "HLS basePrefix");
        if (!CANONICAL_PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid canonical HLS basePrefix: " + prefix);
        }
        ValidatedArtifactClassification classified = validateAndClassifyRelativePath(relativePath);
        String fullKey = prefix + classified.relativePath();
        if (fullKey.length() > MAX_STORAGE_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Combined HLS StorageKey exceeds max length (" + MAX_STORAGE_KEY_LENGTH + "): " + fullKey.length()
            );
        }
        return StorageKey.of(fullKey);
    }

    /**
     * Recomputes the exact canonical object key from {@code validatedBasePrefix} and {@code relativePath}
     * and enforces exact equality with {@code suppliedObjectKey}, rejecting extra segments or shadow paths.
     */
    public static StorageKey validateExactObjectKey(
            StorageKey validatedBasePrefix,
            String relativePath,
            StorageKey suppliedObjectKey
    ) {
        Objects.requireNonNull(suppliedObjectKey, "suppliedObjectKey cannot be null.");
        StorageKey expected = buildAndValidateObjectKey(validatedBasePrefix, relativePath);
        if (!expected.equals(suppliedObjectKey)) {
            throw new IllegalArgumentException(
                    "HLS objectKey does not match canonical prefix + relativePath: expected='"
                            + expected.value() + "', supplied='" + suppliedObjectKey.value() + "'."
            );
        }
        return expected;
    }

    /**
     * Validates a standalone descriptor key against {@code relativePath} by deriving its exact
     * leading package prefix and verifying it matches the strict canonical prefix pattern.
     */
    public static ParsedCanonicalPrefix validateStandaloneObjectKey(StorageKey objectKey, String relativePath) {
        Objects.requireNonNull(objectKey, "objectKey cannot be null.");
        ValidatedArtifactClassification classified = validateAndClassifyRelativePath(relativePath);
        String rawKey = objectKey.value();
        String expectedTail = "/" + classified.relativePath();
        if (!rawKey.endsWith(expectedTail)) {
            throw new IllegalArgumentException(
                    "HLS objectKey '" + rawKey + "' does not end with '" + expectedTail + "'."
            );
        }
        String candidatePrefix = rawKey.substring(0, rawKey.length() - classified.relativePath().length());
        if (candidatePrefix.isEmpty()) {
            throw new IllegalArgumentException("HLS objectKey missing canonical package prefix: " + rawKey);
        }
        ParsedCanonicalPrefix parsed = parseAndValidateCanonicalPrefix(StorageKey.of(candidatePrefix));
        validateExactObjectKey(parsed.basePrefix(), classified.relativePath(), objectKey);
        return parsed;
    }

    private static ValidatedArtifactClassification classifyMediaRenditionFile(String relativePath, String fileName) {
        if ("playlist.m3u8".equals(fileName)) {
            return new ValidatedArtifactClassification(
                    relativePath,
                    HlsArtifactType.MEDIA_PLAYLIST,
                    MIME_HLS_PLAYLIST,
                    HlsUploadStage.STAGE_2_MEDIA_PLAYLISTS
            );
        }
        if (SAFE_INIT_NAME_PATTERN.matcher(fileName).matches()) {
            return new ValidatedArtifactClassification(
                    relativePath,
                    HlsArtifactType.INIT_SEGMENT,
                    MIME_FMP4_INIT,
                    HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS
            );
        }
        if (SAFE_SEGMENT_NAME_PATTERN.matcher(fileName).matches()) {
            return new ValidatedArtifactClassification(
                    relativePath,
                    HlsArtifactType.MEDIA_SEGMENT,
                    MIME_FMP4_SEGMENT,
                    HlsUploadStage.STAGE_1_INIT_AND_SEGMENTS
            );
        }
        throw new IllegalArgumentException("Unsupported HLS artifact filename in path: " + relativePath);
    }

    private static void rejectUnsafeCharacters(String value, String label) {
        if (hasControlOrWhitespace(value)) {
            throw new IllegalArgumentException(label + " must not contain control or whitespace characters: " + value);
        }
        if (value.contains("\\")
                || value.contains("..")
                || value.contains("?")
                || value.contains("#")
                || value.contains(":")
                || value.contains("%")) {
            throw new IllegalArgumentException(label + " contains forbidden path/URI characters: " + value);
        }
    }

    private static boolean hasControlOrWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }
}
