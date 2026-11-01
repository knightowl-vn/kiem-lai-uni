package com.universe.screen.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "screen_titles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_screen_titles_slug",
                        columnNames = "slug"
                )
        }
)
public class ScreenTitleJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "slug",
            nullable = false,
            length = 180
    )
    private String slug;

    @Column(
            name = "title",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String title;

    @Column(
            name = "original_title",
            columnDefinition = "TEXT"
    )
    private String originalTitle;

    @Column(
            name = "synopsis",
            columnDefinition = "TEXT"
    )
    private String synopsis;

    @Column(name = "total_episode_count")
    private Integer totalEpisodeCount;

    @Column(
            name = "structure",
            nullable = false,
            length = 20
    )
    private String structure;

    @Column(
            name = "presentation",
            nullable = false,
            length = 20
    )
    private String presentation;

    @Column(
            name = "poster_media_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String posterMediaAssetId;

    @Column(
            name = "backdrop_media_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String backdropMediaAssetId;

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

    public ScreenTitleJpaEntity() {
    }

    public ScreenTitleJpaEntity(
            String id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            String structure,
            String presentation,
            String posterMediaAssetId,
            String backdropMediaAssetId,
            String status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            Integer totalEpisodeCount
    ) {
        this.id = id;
        this.slug = slug;
        this.title = title;
        this.originalTitle = originalTitle;
        this.synopsis = synopsis;
        this.structure = structure;
        this.presentation = presentation;
        this.posterMediaAssetId = posterMediaAssetId;
        this.backdropMediaAssetId = backdropMediaAssetId;
        this.status = status;
        this.publishedAt = publishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.totalEpisodeCount = totalEpisodeCount;
    }

    public ScreenTitleJpaEntity(
            String id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            String structure,
            String presentation,
            String posterMediaAssetId,
            String backdropMediaAssetId,
            String status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                status,
                publishedAt,
                createdAt,
                updatedAt,
                null
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public void setOriginalTitle(String originalTitle) {
        this.originalTitle = originalTitle;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public void setSynopsis(String synopsis) {
        this.synopsis = synopsis;
    }

    public String getStructure() {
        return structure;
    }

    public void setStructure(String structure) {
        this.structure = structure;
    }

    public String getPresentation() {
        return presentation;
    }

    public void setPresentation(String presentation) {
        this.presentation = presentation;
    }

    public String getPosterMediaAssetId() {
        return posterMediaAssetId;
    }

    public void setPosterMediaAssetId(String posterMediaAssetId) {
        this.posterMediaAssetId = posterMediaAssetId;
    }

    public String getBackdropMediaAssetId() {
        return backdropMediaAssetId;
    }

    public void setBackdropMediaAssetId(String backdropMediaAssetId) {
        this.backdropMediaAssetId = backdropMediaAssetId;
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

    public Integer getTotalEpisodeCount() {
        return totalEpisodeCount;
    }

    public void setTotalEpisodeCount(Integer totalEpisodeCount) {
        this.totalEpisodeCount = totalEpisodeCount;
    }
}
