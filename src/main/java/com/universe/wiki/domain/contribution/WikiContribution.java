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
    public static final int MIN_RESOLUTION_NOTE_LENGTH = 5;
    public static final int MAX_RESOLUTION_NOTE_LENGTH = 2000;

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
    private WikiContributionStatus status;
    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;
    private String resolutionNote;
    private UUID resolvedByUserId;
    private Instant resolvedAt;
    private Long resolvedArticleContentVersion;

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
            Instant updatedAt,
            String resolutionNote,
            UUID resolvedByUserId,
            Instant resolvedAt,
            Long resolvedArticleContentVersion
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

        if (status == WikiContributionStatus.NEW || status == WikiContributionStatus.REVIEWING) {
            if (resolutionNote != null || resolvedByUserId != null || resolvedAt != null || resolvedArticleContentVersion != null) {
                throw new IllegalArgumentException("Đóng góp ở trạng thái " + status + " không được chứa thông tin xử lý kết thúc.");
            }
            this.resolutionNote = null;
            this.resolvedByUserId = null;
            this.resolvedAt = null;
            this.resolvedArticleContentVersion = null;
        } else if (status == WikiContributionStatus.RESOLVED) {
            this.resolutionNote = validateResolutionNote(resolutionNote);
            this.resolvedByUserId = Objects.requireNonNull(resolvedByUserId, "ID người kiểm duyệt không được để trống khi đã giải quyết.");
            this.resolvedAt = Objects.requireNonNull(resolvedAt, "Thời gian quyết định không được để trống khi đã giải quyết.");
            if (resolvedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("Thời gian quyết định không thể trước thời gian tạo đóng góp.");
            }
            if (resolvedArticleContentVersion != null && resolvedArticleContentVersion < 1L) {
                throw new IllegalArgumentException("Phiên bản nội dung bài viết khi giải quyết phải lớn hơn hoặc bằng 1.");
            }
            this.resolvedArticleContentVersion = resolvedArticleContentVersion;
        } else if (status == WikiContributionStatus.REJECTED) {
            this.resolutionNote = validateResolutionNote(resolutionNote);
            this.resolvedByUserId = Objects.requireNonNull(resolvedByUserId, "ID người kiểm duyệt không được để trống khi từ chối.");
            this.resolvedAt = Objects.requireNonNull(resolvedAt, "Thời gian quyết định không được để trống khi từ chối.");
            if (resolvedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("Thời gian quyết định không thể trước thời gian tạo đóng góp.");
            }
            if (resolvedArticleContentVersion != null) {
                throw new IllegalArgumentException("Đóng góp bị từ chối không được lưu phiên bản bài viết giải quyết.");
            }
            this.resolvedArticleContentVersion = null;
        }
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
                timestamp,
                null,
                null,
                null,
                null
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
                timestamp,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence kèm dữ liệu xử lý quy trình.
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
            Instant updatedAt,
            String resolutionNote,
            UUID resolvedByUserId,
            Instant resolvedAt,
            Long resolvedArticleContentVersion
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
                updatedAt,
                resolutionNote,
                resolvedByUserId,
                resolvedAt,
                resolvedArticleContentVersion
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence (tương thích ngược).
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
                updatedAt,
                null,
                null,
                null,
                null
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
            Instant updatedAt,
            String resolutionNote,
            UUID resolvedByUserId,
            Instant resolvedAt,
            Long resolvedArticleContentVersion
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
                updatedAt,
                resolutionNote,
                resolvedByUserId,
                resolvedAt,
                resolvedArticleContentVersion
        );
    }

    /**
     * Khôi phục Aggregate từ tầng persistence (bí danh tương thích ngược).
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
                updatedAt,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Bắt đầu xem xét đóng góp (chuyển từ NEW sang REVIEWING).
     *
     * @param now thời điểm thực hiện thao tác
     */
    public void markReviewing(Instant now) {
        Objects.requireNonNull(now, "Thời gian thao tác không được để trống.");
        if (this.status == WikiContributionStatus.REVIEWING) {
            throw new IllegalStateException("Đóng góp đã đang trong trạng thái xem xét.");
        }
        if (this.status != WikiContributionStatus.NEW) {
            throw new IllegalStateException(
                    String.format("Không thể chuyển sang trạng thái xem xét từ trạng thái hiện tại: %s.", this.status)
            );
        }
        this.status = WikiContributionStatus.REVIEWING;
        this.updatedAt = now;
    }

    /**
     * Chấp thuận và giải quyết đóng góp (RESOLVED).
     *
     * @param resolverUserId ID người kiểm duyệt
     * @param note ghi chú giải quyết (bắt buộc, 5..2000 ký tự)
     * @param currentArticleVersion phiên bản bài viết hiện tại tại thời điểm giải quyết (nếu có)
     * @param now thời điểm giải quyết
     */
    public void resolve(UUID resolverUserId, String note, Long currentArticleVersion, Instant now) {
        Objects.requireNonNull(resolverUserId, "ID người kiểm duyệt không được để trống.");
        Objects.requireNonNull(now, "Thời gian quyết định không được để trống.");
        if (this.status == WikiContributionStatus.RESOLVED || this.status == WikiContributionStatus.REJECTED) {
            throw new IllegalStateException(
                    String.format("Không thể giải quyết đóng góp đã ở trạng thái kết thúc: %s.", this.status)
            );
        }
        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian quyết định không thể trước thời gian tạo đóng góp.");
        }
        if (currentArticleVersion != null && currentArticleVersion < 1L) {
            throw new IllegalArgumentException("Phiên bản nội dung bài viết khi giải quyết phải lớn hơn hoặc bằng 1.");
        }

        String validNote = validateResolutionNote(note);

        this.status = WikiContributionStatus.RESOLVED;
        this.resolutionNote = validNote;
        this.resolvedByUserId = resolverUserId;
        this.resolvedAt = now;
        this.resolvedArticleContentVersion = currentArticleVersion;
        this.updatedAt = now;
    }

    /**
     * Từ chối đóng góp (REJECTED).
     *
     * @param resolverUserId ID người kiểm duyệt
     * @param note ghi chú từ chối (bắt buộc, 5..2000 ký tự)
     * @param now thời điểm từ chối
     */
    public void reject(UUID resolverUserId, String note, Instant now) {
        Objects.requireNonNull(resolverUserId, "ID người kiểm duyệt không được để trống.");
        Objects.requireNonNull(now, "Thời gian quyết định không được để trống.");
        if (this.status == WikiContributionStatus.RESOLVED || this.status == WikiContributionStatus.REJECTED) {
            throw new IllegalStateException(
                    String.format("Không thể từ chối đóng góp đã ở trạng thái kết thúc: %s.", this.status)
            );
        }
        if (now.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Thời gian quyết định không thể trước thời gian tạo đóng góp.");
        }

        String validNote = validateResolutionNote(note);

        this.status = WikiContributionStatus.REJECTED;
        this.resolutionNote = validNote;
        this.resolvedByUserId = resolverUserId;
        this.resolvedAt = now;
        this.resolvedArticleContentVersion = null;
        this.updatedAt = now;
    }

    private static String validateResolutionNote(String note) {
        if (note == null || note.trim().isEmpty()) {
            throw new IllegalArgumentException("Ghi chú xử lý không được để trống.");
        }
        String trimmed = note.trim();
        if (trimmed.length() < MIN_RESOLUTION_NOTE_LENGTH || trimmed.length() > MAX_RESOLUTION_NOTE_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("Độ dài ghi chú xử lý phải từ %d đến %d ký tự.", MIN_RESOLUTION_NOTE_LENGTH, MAX_RESOLUTION_NOTE_LENGTH)
            );
        }
        return trimmed;
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

    public String getResolutionNote() {
        return resolutionNote;
    }

    public UUID getResolvedByUserId() {
        return resolvedByUserId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getResolvedArticleContentVersion() {
        return resolvedArticleContentVersion;
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
                ", resolutionNote='" + resolutionNote + '\'' +
                ", resolvedByUserId=" + resolvedByUserId +
                ", resolvedAt=" + resolvedAt +
                ", resolvedArticleContentVersion=" + resolvedArticleContentVersion +
                '}';
    }
}
