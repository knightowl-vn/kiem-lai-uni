package com.universe.wiki.infrastructure.persistence.contribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity ánh xạ bảng wiki_contributions.
 *
 * Lưu trữ định danh vô hướng (scalar CHAR(36)), không liên kết khóa ngoại ORM
 * sang bảng wiki_articles hay identity_users nhằm bảo đảm lịch sử đóng góp độc lập.
 */
@Entity
@Table(
        name = "wiki_contributions",
        indexes = {
                @Index(
                        name = "idx_wiki_contributions_status_created_id",
                        columnList = "status, created_at, id"
                ),
                @Index(
                        name = "idx_wiki_contributions_article_created_id",
                        columnList = "article_id, created_at, id"
                ),
                @Index(
                        name = "idx_wiki_contributions_user_created_id",
                        columnList = "submitted_by_user_id, created_at, id"
                ),
                @Index(
                        name = "idx_wiki_contributions_assignee",
                        columnList = "assigned_to_user_id, status"
                )
        }
)
public class WikiContributionJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "article_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String articleId;

    @Column(
            name = "article_type_snapshot",
            nullable = false,
            length = 30
    )
    private String articleTypeSnapshot;

    @Column(
            name = "article_title_snapshot",
            nullable = false,
            length = 200
    )
    private String articleTitleSnapshot;

    @Column(
            name = "article_slug_snapshot",
            nullable = false,
            length = 180
    )
    private String articleSlugSnapshot;

    @Column(
            name = "article_content_version",
            nullable = false
    )
    private long articleContentVersion;

    @Column(
            name = "submitted_by_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String submittedByUserId;

    @Column(
            name = "context_type",
            nullable = false,
            length = 20
    )
    private String contextType;

    @Column(
            name = "contribution_type",
            nullable = false,
            length = 32
    )
    private String contributionType;

    @Column(
            name = "message",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String message;

    @Column(
            name = "selected_text",
            length = 1000
    )
    private String selectedText;

    @Column(
            name = "selected_prefix",
            length = 100
    )
    private String selectedPrefix;

    @Column(
            name = "selected_suffix",
            length = 100
    )
    private String selectedSuffix;

    @Column(
            name = "selected_heading_anchor",
            length = 255
    )
    private String selectedHeadingAnchor;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

    @Version
    @Column(
            name = "version",
            nullable = false
    )
    private Long version;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    @Column(
            name = "resolution_note",
            length = 2000
    )
    private String resolutionNote;

    @Column(
            name = "resolved_by_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String resolvedByUserId;

    @Column(
            name = "resolved_at"
    )
    private Instant resolvedAt;

    @Column(
            name = "resolved_article_content_version"
    )
    private Long resolvedArticleContentVersion;

    @Column(
            name = "assigned_to_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String assignedToUserId;

    @Column(
            name = "assigned_at"
    )
    private Instant assignedAt;

    @Column(
            name = "review_started_by_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String reviewStartedByUserId;

    @Column(
            name = "review_started_at"
    )
    private Instant reviewStartedAt;

    @Column(
            name = "review_started_article_content_version"
    )
    private Long reviewStartedArticleContentVersion;

    @Column(
            name = "resolution_outcome",
            length = 30
    )
    private String resolutionOutcome;

    public WikiContributionJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArticleId() {
        return articleId;
    }

    public void setArticleId(String articleId) {
        this.articleId = articleId;
    }

    public String getArticleTypeSnapshot() {
        return articleTypeSnapshot;
    }

    public void setArticleTypeSnapshot(String articleTypeSnapshot) {
        this.articleTypeSnapshot = articleTypeSnapshot;
    }

    public String getArticleTitleSnapshot() {
        return articleTitleSnapshot;
    }

    public void setArticleTitleSnapshot(String articleTitleSnapshot) {
        this.articleTitleSnapshot = articleTitleSnapshot;
    }

    public String getArticleSlugSnapshot() {
        return articleSlugSnapshot;
    }

    public void setArticleSlugSnapshot(String articleSlugSnapshot) {
        this.articleSlugSnapshot = articleSlugSnapshot;
    }

    public long getArticleContentVersion() {
        return articleContentVersion;
    }

    public void setArticleContentVersion(long articleContentVersion) {
        this.articleContentVersion = articleContentVersion;
    }

    public String getSubmittedByUserId() {
        return submittedByUserId;
    }

    public void setSubmittedByUserId(String submittedByUserId) {
        this.submittedByUserId = submittedByUserId;
    }

    public String getContextType() {
        return contextType;
    }

    public void setContextType(String contextType) {
        this.contextType = contextType;
    }

    public String getContributionType() {
        return contributionType;
    }

    public void setContributionType(String contributionType) {
        this.contributionType = contributionType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public void setSelectedText(String selectedText) {
        this.selectedText = selectedText;
    }

    public String getSelectedPrefix() {
        return selectedPrefix;
    }

    public void setSelectedPrefix(String selectedPrefix) {
        this.selectedPrefix = selectedPrefix;
    }

    public String getSelectedSuffix() {
        return selectedSuffix;
    }

    public void setSelectedSuffix(String selectedSuffix) {
        this.selectedSuffix = selectedSuffix;
    }

    public String getSelectedHeadingAnchor() {
        return selectedHeadingAnchor;
    }

    public void setSelectedHeadingAnchor(String selectedHeadingAnchor) {
        this.selectedHeadingAnchor = selectedHeadingAnchor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public String getResolvedByUserId() {
        return resolvedByUserId;
    }

    public void setResolvedByUserId(String resolvedByUserId) {
        this.resolvedByUserId = resolvedByUserId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Long getResolvedArticleContentVersion() {
        return resolvedArticleContentVersion;
    }

    public void setResolvedArticleContentVersion(Long resolvedArticleContentVersion) {
        this.resolvedArticleContentVersion = resolvedArticleContentVersion;
    }

    public String getAssignedToUserId() {
        return assignedToUserId;
    }

    public void setAssignedToUserId(String assignedToUserId) {
        this.assignedToUserId = assignedToUserId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public String getReviewStartedByUserId() {
        return reviewStartedByUserId;
    }

    public void setReviewStartedByUserId(String reviewStartedByUserId) {
        this.reviewStartedByUserId = reviewStartedByUserId;
    }

    public Instant getReviewStartedAt() {
        return reviewStartedAt;
    }

    public void setReviewStartedAt(Instant reviewStartedAt) {
        this.reviewStartedAt = reviewStartedAt;
    }

    public Long getReviewStartedArticleContentVersion() {
        return reviewStartedArticleContentVersion;
    }

    public void setReviewStartedArticleContentVersion(Long reviewStartedArticleContentVersion) {
        this.reviewStartedArticleContentVersion = reviewStartedArticleContentVersion;
    }

    public String getResolutionOutcome() {
        return resolutionOutcome;
    }

    public void setResolutionOutcome(String resolutionOutcome) {
        this.resolutionOutcome = resolutionOutcome;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WikiContributionJpaEntity that = (WikiContributionJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
