package com.universe.screen.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "screen_entries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_screen_entries_title_slug",
                        columnNames = {
                                "title_id",
                                "slug"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_screen_entries_title_status_type_number",
                        columnList = "title_id, status, type, number"
                )
        }
)
public class ScreenEntryJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "title_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String titleId;

    @Column(
            name = "slug",
            nullable = false,
            length = 180
    )
    private String slug;

    @Column(
            name = "type",
            nullable = false,
            length = 20
    )
    private String type;

    @Column(name = "number")
    private Integer number;

    @Column(
            name = "title",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String title;

    @Column(
            name = "synopsis",
            columnDefinition = "TEXT"
    )
    private String synopsis;

    @Column(
            name = "video_media_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String videoMediaAssetId;

    @Column(
            name = "thumbnail_media_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String thumbnailMediaAssetId;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

    @Column(name = "published_at")
    private Instant publishedAt;

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

    public ScreenEntryJpaEntity() {
    }

    public ScreenEntryJpaEntity(
            String id,
            String titleId,
            String slug,
            String type,
            Integer number,
            String title,
            String synopsis,
            String videoMediaAssetId,
            String thumbnailMediaAssetId,
            Long durationSeconds,
            String status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.titleId = titleId;
        this.slug = slug;
        this.type = type;
        this.number = number;
        this.title = title;
        this.synopsis = synopsis;
        this.videoMediaAssetId = videoMediaAssetId;
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
        this.durationSeconds = durationSeconds;
        this.status = status;
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitleId() {
        return titleId;
    }

    public void setTitleId(String titleId) {
        this.titleId = titleId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public void setSynopsis(String synopsis) {
        this.synopsis = synopsis;
    }

    public String getVideoMediaAssetId() {
        return videoMediaAssetId;
    }

    public void setVideoMediaAssetId(String videoMediaAssetId) {
        this.videoMediaAssetId = videoMediaAssetId;
    }

    public String getThumbnailMediaAssetId() {
        return thumbnailMediaAssetId;
    }

    public void setThumbnailMediaAssetId(String thumbnailMediaAssetId) {
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
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
}
