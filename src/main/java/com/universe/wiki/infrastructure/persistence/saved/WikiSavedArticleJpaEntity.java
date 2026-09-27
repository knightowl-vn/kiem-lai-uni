package com.universe.wiki.infrastructure.persistence.saved;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity ánh xạ bảng wiki_saved_articles.
 *
 * Sử dụng định danh vô hướng (scalar UUID/String), không liên kết quan hệ ORM với
 * bảng người dùng (Identity context) hay bài viết Wiki để bảo toàn ranh giới Clean Architecture.
 */
@Entity
@Table(
        name = "wiki_saved_articles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_saved_articles_user_article",
                        columnNames = {
                                "user_id",
                                "article_id"
                        }
                )
        }
)
public class WikiSavedArticleJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String userId;

    @Column(
            name = "article_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String articleId;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected WikiSavedArticleJpaEntity() {
    }

    public WikiSavedArticleJpaEntity(
            String id,
            String userId,
            String articleId,
            Instant createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.articleId = articleId;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getArticleId() {
        return articleId;
    }

    public void setArticleId(String articleId) {
        this.articleId = articleId;
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
        WikiSavedArticleJpaEntity that = (WikiSavedArticleJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
