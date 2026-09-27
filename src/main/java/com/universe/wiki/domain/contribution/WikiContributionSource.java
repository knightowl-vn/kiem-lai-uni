package com.universe.wiki.domain.contribution;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity đại diện cho một nguồn tham khảo / bằng chứng đi kèm đóng góp bài viết Wiki.
 *
 * Mỗi đóng góp có thể đính kèm tối đa 5 nguồn tham khảo (thứ tự 0..4).
 * Nguồn tham khảo là bản ghi bất biến (immutable evidence reference).
 */
public class WikiContributionSource {

    public static final int MIN_SOURCE_ORDER = 0;
    public static final int MAX_SOURCE_ORDER = 4;
    public static final int MAX_URL_LENGTH = 2000;

    private final UUID id;
    private final UUID contributionId;
    private final int sourceOrder;
    private final WikiContributionSourceType sourceType;
    private final String url;
    private final Instant createdAt;

    private WikiContributionSource(
            UUID id,
            UUID contributionId,
            int sourceOrder,
            WikiContributionSourceType sourceType,
            String url,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "ID nguồn tham khảo không được để trống.");
        this.contributionId = Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");

        if (sourceOrder < MIN_SOURCE_ORDER || sourceOrder > MAX_SOURCE_ORDER) {
            throw new IllegalArgumentException(
                    String.format("Thứ tự nguồn tham khảo phải từ %d đến %d.", MIN_SOURCE_ORDER, MAX_SOURCE_ORDER)
            );
        }
        this.sourceOrder = sourceOrder;
        this.sourceType = Objects.requireNonNull(sourceType, "Loại nguồn tham khảo không được để trống.");

        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL nguồn tham khảo không được để trống.");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Độ dài URL nguồn tham khảo không được vượt quá %d ký tự.", MAX_URL_LENGTH)
            );
        }
        this.url = trimmed;
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
    }

    /**
     * Tạo mới một nguồn tham khảo từ dữ liệu người dùng cung cấp.
     * Tự động kiểm tra cú pháp, ngăn chặn tấn công và phân loại nguồn thành INTERNAL hoặc EXTERNAL.
     */
    public static WikiContributionSource create(
            UUID id,
            UUID contributionId,
            int sourceOrder,
            String rawUrl,
            Instant now
    ) {
        Objects.requireNonNull(id, "ID nguồn tham khảo không được để trống.");
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(now, "Thời gian tạo không được để trống.");

        if (sourceOrder < MIN_SOURCE_ORDER || sourceOrder > MAX_SOURCE_ORDER) {
            throw new IllegalArgumentException(
                    String.format("Thứ tự nguồn tham khảo phải từ %d đến %d.", MIN_SOURCE_ORDER, MAX_SOURCE_ORDER)
            );
        }

        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("URL nguồn tham khảo không được để trống.");
        }

        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Độ dài URL nguồn tham khảo không được vượt quá %d ký tự.", MAX_URL_LENGTH)
            );
        }

        if (trimmed.contains("\\")) {
            throw new IllegalArgumentException("URL chứa ký tự không hợp lệ.");
        }

        if (trimmed.startsWith("//")) {
            throw new IllegalArgumentException("URL không được sử dụng cú pháp scheme-relative.");
        }

        UrlClassification classification = classifyAndValidateUrl(trimmed);

        return new WikiContributionSource(
                id,
                contributionId,
                sourceOrder,
                classification.sourceType(),
                classification.validatedUrl(),
                now
        );
    }

    /**
     * Khôi phục nguồn tham khảo từ tầng persistence.
     */
    public static WikiContributionSource reconstitute(
            UUID id,
            UUID contributionId,
            int sourceOrder,
            WikiContributionSourceType sourceType,
            String url,
            Instant createdAt
    ) {
        return new WikiContributionSource(
                id,
                contributionId,
                sourceOrder,
                sourceType,
                url,
                createdAt
        );
    }

    /**
     * Khôi phục nguồn tham khảo từ tầng persistence (bí danh của reconstitute).
     */
    public static WikiContributionSource rehydrate(
            UUID id,
            UUID contributionId,
            int sourceOrder,
            WikiContributionSourceType sourceType,
            String url,
            Instant createdAt
    ) {
        return reconstitute(
                id,
                contributionId,
                sourceOrder,
                sourceType,
                url,
                createdAt
        );
    }

    private static UrlClassification classifyAndValidateUrl(String trimmedUrl) {
        int colonIdx = trimmedUrl.indexOf(':');
        int slashIdx = trimmedUrl.indexOf('/');
        if (colonIdx > 0 && (slashIdx == -1 || colonIdx < slashIdx)) {
            String potentialScheme = trimmedUrl.substring(0, colonIdx);
            if (!"http".equalsIgnoreCase(potentialScheme) && !"https".equalsIgnoreCase(potentialScheme)) {
                throw new IllegalArgumentException("Giao thức URL không được hỗ trợ: " + potentialScheme);
            }
        }

        URI uri;
        try {
            uri = new URI(trimmedUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cú pháp URL không hợp lệ: " + trimmedUrl, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            // Đường dẫn nội bộ phải bắt đầu bằng '/'
            if (!trimmedUrl.startsWith("/")) {
                throw new IllegalArgumentException("URL nội bộ phải bắt đầu bằng đường dẫn tương đối gốc (bắt đầu bằng '/').");
            }
            if (uri.getHost() != null || uri.getAuthority() != null) {
                throw new IllegalArgumentException("URL nội bộ không được chứa thông tin máy chủ (host/authority).");
            }

            String decodedPath = uri.getPath();
            if (decodedPath == null || !decodedPath.startsWith("/")) {
                throw new IllegalArgumentException("Đường dẫn URL nội bộ không hợp lệ.");
            }

            // Chuẩn hóa đường dẫn nhằm ngăn chặn tấn công path traversal
            URI normalizedUri = URI.create(decodedPath).normalize();
            String normalizedPath = normalizedUri.getPath();

            if (!isAllowedInternalPath(normalizedPath)) {
                throw new IllegalArgumentException("Đường dẫn nội bộ không thuộc phạm vi được hỗ trợ (/wiki hoặc /novel): " + trimmedUrl);
            }

            StringBuilder canonical = new StringBuilder(normalizedPath);
            if (uri.getRawQuery() != null) {
                canonical.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                canonical.append('#').append(uri.getRawFragment());
            }

            String finalUrl = canonical.toString();
            if (finalUrl.length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException(
                        String.format("Độ dài URL nguồn tham khảo không được vượt quá %d ký tự.", MAX_URL_LENGTH)
                );
            }
            return new UrlClassification(WikiContributionSourceType.INTERNAL, finalUrl);
        }

        // URL tuyệt đối với scheme
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Giao thức URL không được hỗ trợ: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL tuyệt đối phải chứa hostname hợp lệ.");
        }

        return new UrlClassification(WikiContributionSourceType.EXTERNAL, trimmedUrl);
    }

    private static boolean isAllowedInternalPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/wiki") || path.startsWith("/wiki/")
                || path.equals("/novel") || path.startsWith("/novel/");
    }

    public UUID getId() {
        return id;
    }

    public UUID getContributionId() {
        return contributionId;
    }

    public int getSourceOrder() {
        return sourceOrder;
    }

    public WikiContributionSourceType getSourceType() {
        return sourceType;
    }

    public String getUrl() {
        return url;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WikiContributionSource that = (WikiContributionSource) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WikiContributionSource{" +
                "id=" + id +
                ", contributionId=" + contributionId +
                ", sourceOrder=" + sourceOrder +
                ", sourceType=" + sourceType +
                ", url='" + url + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }

    private record UrlClassification(WikiContributionSourceType sourceType, String validatedUrl) {
    }
}
