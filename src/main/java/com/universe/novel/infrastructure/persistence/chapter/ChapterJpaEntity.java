package com.universe.novel.infrastructure.persistence.chapter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapters",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapters_slug",
                        columnNames = {
                                "slug"
                        }
                ),
                @UniqueConstraint(
                        name = "uq_novel_chapters_volume_sort_order",
                        columnNames = {
                                "volume_id",
                                "sort_order"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_novel_chapters_volume_status",
                        columnList = "volume_id,status"
                )
        }
)
public class ChapterJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "volume_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String volumeId;

    @Column(
            name = "chapter_number"
    )
    private Integer chapterNumber;

    @Column(
            name = "sort_order",
            nullable = false
    )
    private int sortOrder;

    @Column(
            name = "title",
            nullable = false,
            length = 250
    )
    private String title;

    @Column(
            name = "slug",
            nullable = false,
            length = 180
    )
    private String slug;

    @Column(
            name = "summary",
            nullable = false,
            length = 1000
    )
    private String summary;

    @Column(
            name = "content",
            nullable = false,
            columnDefinition = "MEDIUMTEXT"
    )
    private String content;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

    @Column(
            name = "created_by",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String createdBy;

    @Column(
            name = "updated_by",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String updatedBy;

    @Column(
            name = "published_by",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String publishedBy;

    @Column(
            name = "archived_by",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String archivedBy;

    @Column(
            name = "aggregate_version",
            nullable = false
    )
    private long aggregateVersion;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

    @Column(
            name = "content_version",
            nullable = false
    )
    private long contentVersion;

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
            name = "published_at"
    )
    private Instant publishedAt;

    @Column(
            name = "archived_at"
    )
    private Instant archivedAt;

    public ChapterJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(
            String id
    ) {
        this.id = id;
    }

    public String getVolumeId() {
        return volumeId;
    }

    public void setVolumeId(
            String volumeId
    ) {
        this.volumeId = volumeId;
    }

    public Integer getChapterNumber() {
        return chapterNumber;
    }

    public void setChapterNumber(
            Integer chapterNumber
    ) {
        this.chapterNumber = chapterNumber;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(
            int sortOrder
    ) {
        this.sortOrder = sortOrder;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(
            String title
    ) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(
            String slug
    ) {
        this.slug = slug;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(
            String summary
    ) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(
            String content
    ) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(
            String status
    ) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(
            String createdBy
    ) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(
            String updatedBy
    ) {
        this.updatedBy = updatedBy;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(
            String publishedBy
    ) {
        this.publishedBy = publishedBy;
    }

    public String getArchivedBy() {
        return archivedBy;
    }

    public void setArchivedBy(
            String archivedBy
    ) {
        this.archivedBy = archivedBy;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public void setAggregateVersion(
            long aggregateVersion
    ) {
        this.aggregateVersion = aggregateVersion;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(
            long contentVersion
    ) {
        this.contentVersion = contentVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(
            Instant createdAt
    ) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(
            Instant updatedAt
    ) {
        this.updatedAt = updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(
            Instant publishedAt
    ) {
        this.publishedAt = publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(
            Instant archivedAt
    ) {
        this.archivedAt = archivedAt;
    }
}