package com.universe.wiki.domain.contribution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root đại diện cho một đóng góp ý kiến / chỉnh sửa nội dung bài viết Wiki từ độc giả.
 *
 * Quản lý:
 * - Định danh đóng góp (id);
 * - Định danh và ảnh chụp (snapshot) bài viết tại thời điểm đóng góp (articleId, articleType, title, slug, contentVersion);
 * - Định danh người dùng gửi đóng góp (submittedByUserId);
 * - Ngữ cảnh và phân loại đóng góp (contextType, contributionType);
 * - Nội dung đóng góp (message);
 * - Neo đoạn văn bản trích dẫn khi contextType là TEXT_SELECTION (selectedText, selectedPrefix, selectedSuffix, selectedHeadingAnchor);
 * - Trạng thái kiểm duyệt (status);
 * - Phiên bản phục vụ optimistic locking (version);
 * - Dấu thời gian tạo và cập nhật (createdAt, updatedAt).
 *
 * Aggregate độc lập, không tham chiếu khóa ngoại ORM sang bài viết hay người dùng.
 */
public class WikiContribution {

    public static final int MIN_MESSAGE_LENGTH = 20;
    public static final int MAX_MESSAGE_LENGTH = 5000;
    public static final int MAX_ARTICLE_TYPE_SNAPSHOT_LENGTH = 30;
    public static final int MAX_ARTICLE_TITLE_SNAPSHOT_LENGTH = 200;
    public static final int MAX_ARTICLE_SLUG_SNAPSHOT_LENGTH = 180;
    public static final int MAX_SELECTED_TEXT_LENGTH = 1000;
    public static final int MAX_SELECTED_PREFIX_LENGTH = 100;
    public static final int MAX_SELECTED_SUFFIX_LENGTH = 100;
    public static final int MAX_SELECTED_HEADING_ANCHOR_LENGTH = 255;

    private final UUID id;
    private final UUID articleId;
    private final String articleTypeSnapshot;
    private final String articleTitleSnapshot;
    private final String articleSlugSnapshot;
    private final long articleContentVersion;
    private final UUID submittedByUserId;
    private final WikiContributionContextType contextType;
    private final WikiContributionType contributionType;
    private final String message;
    private final String selectedText;
    private final String selectedPrefix;
    private final String selectedSuffix;
    private final String selectedHeadingAnchor;
    private final WikiContributionStatus status;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private WikiContribution(
            UUID id,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            UUID submittedByUserId,
            WikiContributionContextType contextType,
            WikiContributionType contributionType,
            String message,
            String selectedText,
            String selectedPrefix,
            String selectedSuffix,
            String selectedHeadingAnchor,
            WikiContributionStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "ID đóng góp không được để trống.");
        this.articleId = Objects.requireNonNull(articleId, "ID bài viết không được để trống.");
        this.articleTypeSnapshot = validateSnapshot(articleTypeSnapshot, "Loại bài viết", MAX_ARTICLE_TYPE_SNAPSHOT_LENGTH);
        this.articleTitleSnapshot = validateSnapshot(articleTitleSnapshot, "Tiêu đề bài viết", MAX_ARTICLE_TITLE_SNAPSHOT_LENGTH);
        this.articleSlugSnapshot = validateSnapshot(articleSlugSnapshot, "Slug bài viết", MAX_ARTICLE_SLUG_SNAPSHOT_LENGTH);

        if (articleContentVersion < 1L) {
            throw new IllegalArgumentException("Phiên bản nội dung bài viết phải lớn hơn hoặc bằng 1.");
        }
        this.articleContentVersion = articleContentVersion;

        this.submittedByUserId = Objects.requireNonNull(submittedByUserId, "ID người dùng gửi đóng góp không được để trống.");
        this.contextType = Objects.requireNonNull(contextType, "Loại ngữ cảnh đóng góp không được để trống.");
        this.contributionType = Objects.requireNonNull(contributionType, "Loại đóng góp không được để trống.");

        if (message == null) {
            throw new IllegalArgumentException("Nội dung đóng góp không được để trống.");
        }
        String trimmedMessage = message.trim();
        if (trimmedMessage.length() < MIN_MESSAGE_LENGTH || trimmedMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Độ dài nội dung đóng góp phải từ %d đến %d ký tự.", MIN_MESSAGE_LENGTH, MAX_MESSAGE_LENGTH)
            );
        }
        this.message = trimmedMessage;

        if (contextType == WikiContributionContextType.GENERAL) {
            if ((selectedText != null && !selectedText.trim().isEmpty())
                    || (selectedPrefix != null && !selectedPrefix.trim().isEmpty())
                    || (selectedSuffix != null && !selectedSuffix.trim().isEmpty())
                    || (selectedHeadingAnchor != null && !selectedHeadingAnchor.trim().isEmpty())) {
                throw new IllegalArgumentException("Đóng góp dạng tổng quan không được chứa thông tin trích dẫn.");
            }
            this.selectedText = null;
            this.selectedPrefix = null;
            this.selectedSuffix = null;
            this.selectedHeadingAnchor = null;
        } else if (contextType == WikiContributionContextType.TEXT_SELECTION) {
            String normSelected = normalizeAnchorText(selectedText);
            if (normSelected == null || normSelected.isEmpty()) {
                throw new IllegalArgumentException("Đoạn văn bản trích dẫn không được để trống đối với đóng góp theo đoạn.");
            }
            if (normSelected.length() > MAX_SELECTED_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                        String.format("Độ dài đoạn văn bản trích dẫn không được vượt quá %d ký tự.", MAX_SELECTED_TEXT_LENGTH)
                );
            }
            this.selectedText = normSelected;

            String normPrefix = normalizeAnchorText(selectedPrefix);
            if (normPrefix != null && normPrefix.length() > MAX_SELECTED_PREFIX_LENGTH) {
                throw new IllegalArgumentException(
                        String.format("Độ dài tiền tố trích dẫn không được vượt quá %d ký tự.", MAX_SELECTED_PREFIX_LENGTH)
                );
            }
            this.selectedPrefix = normPrefix;

            String normSuffix = normalizeAnchorText(selectedSuffix);
            if (normSuffix != null && normSuffix.length() > MAX_SELECTED_SUFFIX_LENGTH) {
                throw new IllegalArgumentException(
                        String.format("Độ dài hậu tố trích dẫn không được vượt quá %d ký tự.", MAX_SELECTED_SUFFIX_LENGTH)
                );
            }
            this.selectedSuffix = normSuffix;

            String normHeading = normalizeHeadingAnchor(selectedHeadingAnchor);
            if (normHeading != null && normHeading.length() > MAX_SELECTED_HEADING_ANCHOR_LENGTH) {
                throw new IllegalArgumentException(
                        String.format("Độ dài neo tiêu đề không được vượt quá %d ký tự.", MAX_SELECTED_HEADING_ANCHOR_LENGTH)
                );
            }
            this.selectedHeadingAnchor = normHeading;
        } else {
            throw new IllegalArgumentException("Loại ngữ cảnh đóng góp không hợp lệ: " + contextType);
        }

        this.status = Objects.requireNonNull(status, "Trạng thái đóng góp không được để trống.");
        if (version < 0L) {
            throw new IllegalArgumentException("Version không được nhỏ hơn 0.");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "Thời gian tạo không được để trống.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Thời gian cập nhật không được để trống.");
    }

    /**
     * Tạo mới một đóng góp dạng tổng quan (GENERAL).
     * Trạng thái ban đầu luôn là NEW và version khởi tạo là 0.
     */
    public static WikiContribution createGeneral(
            UUID id,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            UUID submittedByUserId,
            WikiContributionType contributionType,
            String message,
            Instant now
    ) {
        Instant timestamp = Objects.requireNonNull(now, "Thời gian tạo không được để trống.");
        return new WikiContribution(
                id,
                articleId,
                articleTypeSnapshot,
                articleTitleSnapshot,
                articleSlugSnapshot,
                articleContentVersion,
                submittedByUserId,
                WikiContributionContextType.GENERAL,
                contributionType,
                message,
                null,
                null,
                null,
                null,
                WikiContributionStatus.NEW,
                0L,
                timestamp,
                timestamp
        );
    }

    /**
     * Tạo mới một đóng góp gắn với đoạn văn bản trích dẫn (TEXT_SELECTION).
     * Trạng thái ban đầu luôn là NEW và version khởi tạo là 0.
     */
    public static WikiContribution createTextSelection(
            UUID id,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            UUID submittedByUserId,
            WikiContributionType contributionType,
            String message,
            String selectedText,
            String selectedPrefix,
            String selectedSuffix,
            String selectedHeadingAnchor,
            Instant now
    ) {
        Instant timestamp = Objects.requireNonNull(now, "Thời gian tạo không được để trống.");
        return new WikiContribution(
                id,
                articleId,
                articleTypeSnapshot,
                articleTitleSnapshot,
                articleSlugSnapshot,
                articleContentVersion,
                submittedByUserId,
                WikiContributionContextType.TEXT_SELECTION,
                contributionType,
                message,
                selectedText,
                selectedPrefix,
                selectedSuffix,
                selectedHeadingAnchor,
                WikiContributionStatus.NEW,
                0L,
                timestamp,
                timestamp
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence.
     */
    public static WikiContribution reconstitute(
            UUID id,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            UUID submittedByUserId,
            WikiContributionContextType contextType,
            WikiContributionType contributionType,
            String message,
            String selectedText,
            String selectedPrefix,
            String selectedSuffix,
            String selectedHeadingAnchor,
            WikiContributionStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new WikiContribution(
                id,
                articleId,
                articleTypeSnapshot,
                articleTitleSnapshot,
                articleSlugSnapshot,
                articleContentVersion,
                submittedByUserId,
                contextType,
                contributionType,
                message,
                selectedText,
                selectedPrefix,
                selectedSuffix,
                selectedHeadingAnchor,
                status,
                version,
                createdAt,
                updatedAt
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence (bí danh của reconstitute).
     */
    public static WikiContribution rehydrate(
            UUID id,
            UUID articleId,
            String articleTypeSnapshot,
            String articleTitleSnapshot,
            String articleSlugSnapshot,
            long articleContentVersion,
            UUID submittedByUserId,
            WikiContributionContextType contextType,
            WikiContributionType contributionType,
            String message,
            String selectedText,
            String selectedPrefix,
            String selectedSuffix,
            String selectedHeadingAnchor,
            WikiContributionStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        return reconstitute(
                id,
                articleId,
                articleTypeSnapshot,
                articleTitleSnapshot,
                articleSlugSnapshot,
                articleContentVersion,
                submittedByUserId,
                contextType,
                contributionType,
                message,
                selectedText,
                selectedPrefix,
                selectedSuffix,
                selectedHeadingAnchor,
                status,
                version,
                createdAt,
                updatedAt
        );
    }

    private static String normalizeAnchorText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private static String normalizeHeadingAnchor(String anchor) {
        if (anchor == null) {
            return null;
        }
        String trimmed = anchor.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String validateSnapshot(String value, String fieldName, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " snapshot không được để trống.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(
                    String.format("%s snapshot không được vượt quá %d ký tự.", fieldName, maxLength)
            );
        }
        return trimmed;
    }

    public UUID getId() {
        return id;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public String getArticleTypeSnapshot() {
        return articleTypeSnapshot;
    }

    public String getArticleTitleSnapshot() {
        return articleTitleSnapshot;
    }

    public String getArticleSlugSnapshot() {
        return articleSlugSnapshot;
    }

    public long getArticleContentVersion() {
        return articleContentVersion;
    }

    public UUID getSubmittedByUserId() {
        return submittedByUserId;
    }

    public WikiContributionContextType getContextType() {
        return contextType;
    }

    public WikiContributionType getContributionType() {
        return contributionType;
    }

    public String getMessage() {
        return message;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public String getSelectedPrefix() {
        return selectedPrefix;
    }

    public String getSelectedSuffix() {
        return selectedSuffix;
    }

    public String getSelectedHeadingAnchor() {
        return selectedHeadingAnchor;
    }

    public WikiContributionStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WikiContribution that = (WikiContribution) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WikiContribution{" +
                "id=" + id +
                ", articleId=" + articleId +
                ", articleTypeSnapshot='" + articleTypeSnapshot + '\'' +
                ", articleTitleSnapshot='" + articleTitleSnapshot + '\'' +
                ", articleSlugSnapshot='" + articleSlugSnapshot + '\'' +
                ", articleContentVersion=" + articleContentVersion +
                ", submittedByUserId=" + submittedByUserId +
                ", contextType=" + contextType +
                ", contributionType=" + contributionType +
                ", status=" + status +
                ", version=" + version +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
