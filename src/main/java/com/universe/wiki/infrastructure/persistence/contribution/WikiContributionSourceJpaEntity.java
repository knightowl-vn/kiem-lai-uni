package com.universe.wiki.infrastructure.persistence.contribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity ánh xạ bảng wiki_contribution_sources.
 */
@Entity
@Table(
        name = "wiki_contribution_sources",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_contribution_sources_contribution_order",
                        columnNames = {"contribution_id", "source_order"}
                )
        }
)
public class WikiContributionSourceJpaEntity {

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

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TINYINT)
    @Column(
            name = "source_order",
            nullable = false
    )
    private int sourceOrder;

    @Column(
            name = "source_type",
            nullable = false,
            length = 20
    )
    private String sourceType;

    @Column(
            name = "url",
            nullable = false,
            length = 2000
    )
    private String url;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    public WikiContributionSourceJpaEntity() {
    }

    public WikiContributionSourceJpaEntity(
            String id,
            String contributionId,
            int sourceOrder,
            String sourceType,
            String url,
            Instant createdAt
    ) {
        this.id = id;
        this.contributionId = contributionId;
        this.sourceOrder = sourceOrder;
        this.sourceType = sourceType;
        this.url = url;
        this.createdAt = createdAt;
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

    public int getSourceOrder() {
        return sourceOrder;
    }

    public void setSourceOrder(int sourceOrder) {
        this.sourceOrder = sourceOrder;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WikiContributionSourceJpaEntity that = (WikiContributionSourceJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
