package com.universe.screen.domain;

/**
 * Reusable validation and normalization rules for ScreenTitle release metadata.
 *
 * <p>Enforces V95 storage limits, release year range, and airing status compatibility.
 */
public final class ScreenMetadataValidator {

    public static final int MAX_COUNTRY_LENGTH = 100;
    public static final int MAX_ORIGINAL_AUTHOR_LENGTH = 255;
    public static final int MAX_DIRECTOR_LENGTH = 255;
    public static final int MAX_PRODUCTION_STUDIO_LENGTH = 255;
    public static final int MIN_RELEASE_YEAR = 1900;
    public static final int MAX_RELEASE_YEAR = 2100;

    private ScreenMetadataValidator() {
    }

    /**
     * Normalizes text by trimming whitespace, converting empty or blank strings to {@code null}.
     */
    public static String normalizeOptionalText(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return candidate.trim();
    }

    /**
     * Validates that an airing status is compatible with the given structure.
     */
    public static ScreenAiringStatus validateAiringStatus(ScreenStructure structure, ScreenAiringStatus candidate) {
        if (candidate == null) {
            return null;
        }
        if (structure == null) {
            throw new IllegalArgumentException("Cấu trúc tác phẩm không được để trống khi kiểm tra trạng thái phát sóng.");
        }
        if (!candidate.isCompatibleWith(structure)) {
            throw new IllegalArgumentException(
                    "Trạng thái phát sóng " + candidate + " không tương thích với cấu trúc tác phẩm " + structure
            );
        }
        return candidate;
    }

    /**
     * Validates that the release year is within the range [1900, 2100].
     */
    public static Integer validateReleaseYear(Integer candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate < MIN_RELEASE_YEAR || candidate > MAX_RELEASE_YEAR) {
            throw new IllegalArgumentException(
                    "Năm phát hành phải nằm trong khoảng từ " + MIN_RELEASE_YEAR + " đến " + MAX_RELEASE_YEAR + ": " + candidate
            );
        }
        return candidate;
    }

    /**
     * Validates that the country string does not exceed 100 characters.
     */
    public static String validateCountry(String candidate) {
        String normalized = normalizeOptionalText(candidate);
        if (normalized != null && normalized.length() > MAX_COUNTRY_LENGTH) {
            throw new IllegalArgumentException(
                    "Quốc gia không được vượt quá " + MAX_COUNTRY_LENGTH + " ký tự: " + normalized.length()
            );
        }
        return normalized;
    }

    /**
     * Validates that the original author string does not exceed 255 characters.
     */
    public static String validateOriginalAuthor(String candidate) {
        String normalized = normalizeOptionalText(candidate);
        if (normalized != null && normalized.length() > MAX_ORIGINAL_AUTHOR_LENGTH) {
            throw new IllegalArgumentException(
                    "Tác giả gốc (nguyên tác) không được vượt quá " + MAX_ORIGINAL_AUTHOR_LENGTH + " ký tự: " + normalized.length()
            );
        }
        return normalized;
    }

    /**
     * Validates that the director string does not exceed 255 characters.
     */
    public static String validateDirector(String candidate) {
        String normalized = normalizeOptionalText(candidate);
        if (normalized != null && normalized.length() > MAX_DIRECTOR_LENGTH) {
            throw new IllegalArgumentException(
                    "Đạo diễn không được vượt quá " + MAX_DIRECTOR_LENGTH + " ký tự: " + normalized.length()
            );
        }
        return normalized;
    }

    /**
     * Validates that the production studio string does not exceed 255 characters.
     */
    public static String validateProductionStudio(String candidate) {
        String normalized = normalizeOptionalText(candidate);
        if (normalized != null && normalized.length() > MAX_PRODUCTION_STUDIO_LENGTH) {
            throw new IllegalArgumentException(
                    "Đơn vị sản xuất không được vượt quá " + MAX_PRODUCTION_STUDIO_LENGTH + " ký tự: " + normalized.length()
            );
        }
        return normalized;
    }
}
