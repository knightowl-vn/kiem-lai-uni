package com.universe.community.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity for append-only Community post creation events.
 * Represents an immutable ledger of committed post creations for anti-spam quota enforcement.
 */
@Entity
@Table(name = "community_post_creation_events")
public class CommunityPostCreationEventJpaEntity {

    @Id
    @Column(
            name = "id",
            length = 36,
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "author_user_id",
            length = 36,
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private String authorUserId;

    @Column(
            name = "post_id",
            length = 36,
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private String postId;

    @Column(
            name = "normalized_caption_hash",
            length = 64,
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(64)"
    )
    private String normalizedCaptionHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunityPostCreationEventJpaEntity() {
    }

    public CommunityPostCreationEventJpaEntity(
            String id,
            String authorUserId,
            String postId,
            String normalizedCaptionHash,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "ID cannot be null.");
        this.authorUserId = Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
        this.postId = Objects.requireNonNull(postId, "Post ID cannot be null.");
        this.normalizedCaptionHash = Objects.requireNonNull(normalizedCaptionHash, "Normalized caption hash cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt cannot be null.");
    }

    public String getId() {
        return id;
    }

    public String getAuthorUserId() {
        return authorUserId;
    }

    public String getPostId() {
        return postId;
    }

    public String getNormalizedCaptionHash() {
        return normalizedCaptionHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunityPostCreationEventJpaEntity that = (CommunityPostCreationEventJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
