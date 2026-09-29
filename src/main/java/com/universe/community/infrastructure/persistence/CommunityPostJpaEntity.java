package com.universe.community.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity mapping the {@code community_posts} table.
 *
 * <p>Uses scalar string representations for UUIDs to maintain Clean Architecture
 * boundaries and avoid ORM coupling to foreign bounded contexts (Identity, Media).
 */
@Entity
@Table(name = "community_posts")
public class CommunityPostJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "author_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String authorUserId;

    @Column(
            name = "caption",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String caption;

    @Column(
            name = "image_media_asset_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String imageMediaAssetId;

    @Column(
            name = "content_version",
            nullable = false
    )
    private int contentVersion;

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

    protected CommunityPostJpaEntity() {
    }

    public CommunityPostJpaEntity(
            String id,
            String authorUserId,
            String caption,
            String imageMediaAssetId,
            int contentVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.authorUserId = authorUserId;
        this.caption = caption;
        this.imageMediaAssetId = imageMediaAssetId;
        this.contentVersion = contentVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(String authorUserId) {
        this.authorUserId = authorUserId;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public String getImageMediaAssetId() {
        return imageMediaAssetId;
    }

    public void setImageMediaAssetId(String imageMediaAssetId) {
        this.imageMediaAssetId = imageMediaAssetId;
    }

    public int getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(int contentVersion) {
        this.contentVersion = contentVersion;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunityPostJpaEntity that = (CommunityPostJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommunityPostJpaEntity{" +
                "id='" + id + '\'' +
                ", authorUserId='" + authorUserId + '\'' +
                ", caption='" + (caption != null && caption.length() > 30 ? caption.substring(0, 30) + "..." : caption) + '\'' +
                ", imageMediaAssetId='" + imageMediaAssetId + '\'' +
                ", contentVersion=" + contentVersion +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
