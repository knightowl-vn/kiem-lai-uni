package com.universe.wiki.infrastructure.persistence.article;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "wiki_article_aliases",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_article_aliases_article_alias",
                        columnNames = {
                                "article_id",
                                "normalized_alias"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_wiki_article_aliases_normalized",
                        columnList = "normalized_alias"
                )
        }
)
public class WikiArticleAliasJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "article_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String articleId;

    @Column(
            name = "alias",
            nullable = false,
            length = 200
    )
    private String alias;

    @Column(
            name = "normalized_alias",
            nullable = false,
            length = 200
    )
    private String normalizedAlias;

    @Column(
            name = "created_at",
            nullable = false,
            columnDefinition = "DATETIME(6)"
    )
    private Instant createdAt;

    public WikiArticleAliasJpaEntity() {
    }

    public WikiArticleAliasJpaEntity(
            String id,
            String articleId,
            String alias,
            String normalizedAlias,
            Instant createdAt
    ) {
        this.id = id;
        this.articleId = articleId;
        this.alias = alias;
        this.normalizedAlias = normalizedAlias;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArticleId() {
        return articleId;
    }

    public void setArticleId(String articleId) {
        this.articleId = articleId;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getNormalizedAlias() {
        return normalizedAlias;
    }

    public void setNormalizedAlias(String normalizedAlias) {
        this.normalizedAlias = normalizedAlias;
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
        if (!(o instanceof WikiArticleAliasJpaEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}