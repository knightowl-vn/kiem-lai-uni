package com.universe.wiki.infrastructure.persistence.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity ánh xạ bảng wiki_contribution_credits.
 *
 * Lưu trữ định danh vô hướng (scalar CHAR(36)), không liên kết khóa ngoại ORM
 * sang identity_users nhằm bảo đảm tính độc lập module.
 */
@Entity
@Table(
        name = "wiki_contribution_credits",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_contribution_credits_contribution",
                        columnNames = {"contribution_id"}
                )
        }
)
public class WikiContributionCreditJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "contribution_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String contributionId;

    @Column(
            name = "credit_status",
            nullable = false,
            length = 20
    )
    private String creditStatus;

    @Column(
            name = "credited_by_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String creditedByUserId;

    @Column(
            name = "credited_at",
            nullable = false
    )
    private Instant creditedAt;

    @Column(
            name = "credit_note",
            length = 1000
    )
    private String creditNote;

    @Column(
            name = "revoked_by_user_id",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String revokedByUserId;

    @Column(
            name = "revoked_at"
    )
    private Instant revokedAt;

    @Column(
            name = "revocation_reason",
            length = 1000
    )
    private String revocationReason;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

    public WikiContributionCreditJpaEntity() {
    }

    public WikiContributionCreditJpaEntity(
            String id,
            String contributionId,
            String creditStatus,
            String creditedByUserId,
            Instant creditedAt,
            String creditNote,
            String revokedByUserId,
            Instant revokedAt,
            String revocationReason
    ) {
        this.id = id;
        this.contributionId = contributionId;
        this.creditStatus = creditStatus;
        this.creditedByUserId = creditedByUserId;
        this.creditedAt = creditedAt;
        this.creditNote = creditNote;
        this.revokedByUserId = revokedByUserId;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContributionId() {
        return contributionId;
    }

    public void setContributionId(String contributionId) {
        this.contributionId = contributionId;
    }

    public String getCreditStatus() {
        return creditStatus;
    }

    public void setCreditStatus(String creditStatus) {
        this.creditStatus = creditStatus;
    }

    public String getCreditedByUserId() {
        return creditedByUserId;
    }

    public void setCreditedByUserId(String creditedByUserId) {
        this.creditedByUserId = creditedByUserId;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }

    public void setCreditedAt(Instant creditedAt) {
        this.creditedAt = creditedAt;
    }

    public String getCreditNote() {
        return creditNote;
    }

    public void setCreditNote(String creditNote) {
        this.creditNote = creditNote;
    }

    public String getRevokedByUserId() {
        return revokedByUserId;
    }

    public void setRevokedByUserId(String revokedByUserId) {
        this.revokedByUserId = revokedByUserId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public void setPersistenceVersion(Long persistenceVersion) {
        this.persistenceVersion = persistenceVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikiContributionCreditJpaEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
