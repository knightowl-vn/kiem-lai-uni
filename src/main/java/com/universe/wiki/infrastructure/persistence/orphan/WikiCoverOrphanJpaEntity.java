package com.universe.wiki.infrastructure.persistence.orphan;

import com.universe.wiki.domain.orphan.WikiCoverOrphanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mapping the {@code wiki_cover_orphans} table.
 *
 * <p>Represents Wiki-owned operational reconciliation state for unreferenced media assets.
 * Holds no foreign-key relationships to other modules to preserve bounded-context autonomy.
 */
@Entity
@Table(
        name = "wiki_cover_orphans",
        indexes = {
                @Index(
                        name = "idx_wiki_cover_orphans_status_first_seen",
                        columnList = "status, first_seen_orphan_at"
                ),
                @Index(
                        name = "idx_wiki_cover_orphans_status_locked",
                        columnList = "status, locked_at"
                )
        }
)
public class WikiCoverOrphanJpaEntity {

    @Id
    @Column(
            name = "media_asset_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String mediaAssetId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private WikiCoverOrphanStatus status;

    @Column(
            name = "first_seen_orphan_at",
            nullable = false
    )
    private Instant firstSeenOrphanAt;

    @Column(
            name = "claim_token",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String claimToken;

    @Column(
            name = "locked_at"
    )
    private Instant lockedAt;

    @Column(
            name = "retry_count",
            nullable = false
    )
    private int retryCount;

    @Column(
            name = "last_error",
            length = 500
    )
    private String lastError;

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

    protected WikiCoverOrphanJpaEntity() {
    }

    public WikiCoverOrphanJpaEntity(
            String mediaAssetId,
            WikiCoverOrphanStatus status,
            Instant firstSeenOrphanAt,
            String claimToken,
            Instant lockedAt,
            int retryCount,
            String lastError,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.mediaAssetId = mediaAssetId;
        this.status = status;
        this.firstSeenOrphanAt = firstSeenOrphanAt;
        this.claimToken = claimToken;
        this.lockedAt = lockedAt;
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getMediaAssetId() {
        return mediaAssetId;
    }

    public void setMediaAssetId(String mediaAssetId) {
        this.mediaAssetId = mediaAssetId;
    }

    public WikiCoverOrphanStatus getStatus() {
        return status;
    }

    public void setStatus(WikiCoverOrphanStatus status) {
        this.status = status;
    }

    public Instant getFirstSeenOrphanAt() {
        return firstSeenOrphanAt;
    }

    public void setFirstSeenOrphanAt(Instant firstSeenOrphanAt) {
        this.firstSeenOrphanAt = firstSeenOrphanAt;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
