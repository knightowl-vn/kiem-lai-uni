package com.universe.wiki.infrastructure.persistence.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "wiki_images",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_images_content_hash",
                        columnNames = "content_hash"
                ),
                @UniqueConstraint(
                        name = "uq_wiki_images_public_id",
                        columnNames = "public_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_wiki_images_created_at",
                        columnList = "created_at"
                )
        }
)
public class WikiImageJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "content_hash",
            nullable = false,
            length = 64,
            columnDefinition = "CHAR(64)"
    )
    private String contentHash;

    @Column(
            name = "public_id",
            nullable = false,
            length = 255
    )
    private String publicId;

    @Column(
            name = "url",
            nullable = false,
            length = 1000
    )
    private String url;

    @Column(
            name = "source_content_type",
            nullable = false,
            length = 100
    )
    private String sourceContentType;

    @Column(
            name = "size_bytes",
            nullable = false
    )
    private long sizeBytes;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    public WikiImageJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(
            String id
    ) {
        this.id = id;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(
            String contentHash
    ) {
        this.contentHash = contentHash;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(
            String publicId
    ) {
        this.publicId = publicId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(
            String url
    ) {
        this.url = url;
    }

    public String getSourceContentType() {
        return sourceContentType;
    }

    public void setSourceContentType(
            String sourceContentType
    ) {
        this.sourceContentType =
                sourceContentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(
            long sizeBytes
    ) {
        this.sizeBytes = sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(
            Instant createdAt
    ) {
        this.createdAt = createdAt;
    }
}