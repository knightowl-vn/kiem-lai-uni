package com.universe.wiki.infrastructure.persistence.article;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "wiki_articles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_articles_type_slug",
                        columnNames = {
                                "article_type",
                                "slug"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_wiki_articles_status",
                        columnList = "status"
                ),
                @Index(
                        name = "idx_wiki_articles_article_type",
                        columnList = "article_type"
                ),
                @Index(
                        name = "idx_wiki_articles_type_status",
                        columnList = "article_type,status"
                ),
                @Index(
                        name = "idx_wiki_articles_created_at",
                        columnList = "created_at"
                ),
                @Index(
                        name = "idx_wiki_articles_published_at",
                        columnList = "published_at"
                )
        }
)
public class WikiArticleJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "title",
            nullable = false,
            length = 200
    )
    private String title;

    @Column(
            name = "slug",
            nullable = false,
            length = 180
    )
    private String slug;

    @Column(
            name = "article_type",
            nullable = false,
            length = 30
    )
    private String articleType;

    @Column(
            name = "summary",
            nullable = false,
            length = 1000
    )
    private String summary;

    @Column(
            name = "content",
            nullable = false,
            columnDefinition = "MEDIUMTEXT"
    )
    private String content;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

    @Column(
            name = "created_by",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String createdBy;

    @Column(
            name = "updated_by",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String updatedBy;

    @Column(
            name = "published_by",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String publishedBy;

    @Column(
            name = "archived_by",
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String archivedBy;

    @Column(
            name = "aggregate_version",
            nullable = false
    )
    private long aggregateVersion;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

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

    @Column(
            name = "published_at"
    )
    private Instant publishedAt;

    @Column(
            name = "archived_at"
    )
    private Instant archivedAt;

    public WikiArticleJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(
            String id
    ) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(
            String title
    ) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(
            String slug
    ) {
        this.slug = slug;
    }

    public String getArticleType() {
        return articleType;
    }

    public void setArticleType(
            String articleType
    ) {
        this.articleType = articleType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(
            String summary
    ) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(
            String content
    ) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(
            String status
    ) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(
            String createdBy
    ) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(
            String updatedBy
    ) {
        this.updatedBy = updatedBy;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(
            String publishedBy
    ) {
        this.publishedBy = publishedBy;
    }

    public String getArchivedBy() {
        return archivedBy;
    }

    public void setArchivedBy(
            String archivedBy
    ) {
        this.archivedBy = archivedBy;
    }

    public long getAggregateVersion() {
        return aggregateVersion;
    }

    public void setAggregateVersion(
            long aggregateVersion
    ) {
        this.aggregateVersion = aggregateVersion;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(
            Instant createdAt
    ) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(
            Instant updatedAt
    ) {
        this.updatedAt = updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(
            Instant publishedAt
    ) {
        this.publishedAt = publishedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(
            Instant archivedAt
    ) {
        this.archivedAt = archivedAt;
    }
}