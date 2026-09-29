package com.universe.media.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(
        name = "media_image_variants",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_media_image_variants_version_key",
                        columnNames = {
                                "version_id",
                                "variant_key"
                        }
                ),
                @UniqueConstraint(
                        name = "uq_media_image_variants_provider_key",
                        columnNames = {
                                "storage_provider_id",
                                "storage_key"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_media_image_variants_version_id",
                        columnList = "version_id"
                )
        }
)
public class MediaImageVariantJpaEntity implements Persistable<String> {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "version_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String versionId;

    @Column(
            name = "variant_key",
            nullable = false,
            length = 100
    )
    private String variantKey;

    @Column(
            name = "target_width",
            nullable = false
    )
    private int targetWidth;

    @Column(
            name = "storage_provider_id",
            length = 50
    )
    private String storageProviderId;

    @Column(
            name = "storage_key",
            length = 500
    )
    private String storageKey;

    @Column(
            name = "public_url",
            length = 1000
    )
    private String publicUrl;

    @Column(
            name = "content_hash",
            length = 64,
            columnDefinition = "CHAR(64)"
    )
    private String contentHash;

    @Column(
            name = "mime_type",
            nullable = false,
            length = 100
    )
    private String mimeType;

    @Column(
            name = "size_bytes"
    )
    private Long sizeBytes;

    @Column(
            name = "width"
    )
    private Integer width;

    @Column(
            name = "height"
    )
    private Integer height;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    public MediaImageVariantJpaEntity() {
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getVariantKey() {
        return variantKey;
    }

    public void setVariantKey(String variantKey) {
        this.variantKey = variantKey;
    }

    public int getTargetWidth() {
        return targetWidth;
    }

    public void setTargetWidth(int targetWidth) {
        this.targetWidth = targetWidth;
    }

    public String getStorageProviderId() {
        return storageProviderId;
    }

    public void setStorageProviderId(String storageProviderId) {
        this.storageProviderId = storageProviderId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
