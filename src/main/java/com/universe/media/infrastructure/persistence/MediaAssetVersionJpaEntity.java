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
        name = "media_asset_versions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_media_asset_versions_asset_version",
                        columnNames = {
                                "asset_id",
                                "version_number"
                        }
                ),
                @UniqueConstraint(
                        name = "uq_media_asset_versions_provider_key",
                        columnNames = {
                                "storage_provider_id",
                                "storage_key"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_media_asset_versions_content_hash",
                        columnList = "content_hash"
                )
        }
)
public class MediaAssetVersionJpaEntity implements Persistable<String> {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "asset_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String assetId;

    @Column(
            name = "version_number",
            nullable = false
    )
    private int versionNumber;

    @Column(
            name = "storage_provider_id",
            nullable = false,
            length = 50
    )
    private String storageProviderId;

    @Column(
            name = "storage_key",
            nullable = false,
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
            nullable = false,
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
            name = "size_bytes",
            nullable = false
    )
    private long sizeBytes;

    @Column(
            name = "original_filename",
            nullable = false,
            length = 255
    )
    private String originalFilename;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    public MediaAssetVersionJpaEntity() {
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

    public String getAssetId() {
        return assetId;
    }

    public void setAssetId(String assetId) {
        this.assetId = assetId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
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

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
