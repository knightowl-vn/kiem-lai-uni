package com.universe.novel.infrastructure.persistence.revision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapter_revisions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapter_revisions_chapter_number",
                        columnNames = {
                                "chapter_id",
                                "revision_number"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_novel_chapter_revisions_chapter",
                        columnList = "chapter_id"
                ),
                @Index(
                        name = "idx_novel_chapter_revisions_chapter_created",
                        columnList = "chapter_id,created_at"
                ),
                @Index(
                        name = "idx_novel_chapter_revisions_editor",
                        columnList = "edited_by"
                )
        }
)
public class ChapterRevisionJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "chapter_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String chapterId;

    @Column(
            name = "volume_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String volumeId;

    @Column(
            name = "revision_number",
            nullable = false
    )
    private long revisionNumber;

    @Column(
            name = "content_version",
            nullable = false
    )
    private long contentVersion;

    @Column(
            name = "chapter_number",
            nullable = false
    )
    private int chapterNumber;

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
            name = "change_type",
            nullable = false,
            length = 30
    )
    private String changeType;

    @Column(
            name = "edit_summary",
            length = 500
    )
    private String editSummary;

    @Column(
            name = "edited_by",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String editedBy;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    public ChapterRevisionJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public String getVolumeId() {
        return volumeId;
    }

    public void setVolumeId(String volumeId) {
        this.volumeId = volumeId;
    }

    public long getRevisionNumber() {
        return revisionNumber;
    }

    public void setRevisionNumber(long revisionNumber) {
        this.revisionNumber = revisionNumber;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(long contentVersion) {
        this.contentVersion = contentVersion;
    }

    public int getChapterNumber() {
        return chapterNumber;
    }

    public void setChapterNumber(int chapterNumber) {
        this.chapterNumber = chapterNumber;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getEditSummary() {
        return editSummary;
    }

    public void setEditSummary(String editSummary) {
        this.editSummary = editSummary;
    }

    public String getEditedBy() {
        return editedBy;
    }

    public void setEditedBy(String editedBy) {
        this.editedBy = editedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
