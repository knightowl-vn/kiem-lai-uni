package com.universe.wiki.infrastructure.persistence.appreciation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity ánh xạ bảng wiki_appreciation_ratings.
 *
 * Sử dụng định danh vô hướng (scalar UUID/String), không liên kết quan hệ ORM trực tiếp
 * để bảo toàn ranh giới kiến trúc Clean Architecture / DDD.
 */
@Entity
@Table(
        name = "wiki_appreciation_ratings",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_wiki_appreciation_ratings_article_user",
                        columnNames = {
                                "wiki_article_id",
                                "user_id"
                        }
                )
        }
)
public class WikiAppreciationJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "wiki_article_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String wikiArticleId;

    @Column(
            name = "user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String userId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TINYINT)
    @Column(
            name = "value",
            nullable = false
    )
    private int value;

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

    protected WikiAppreciationJpaEntity() {
    }

    public WikiAppreciationJpaEntity(
            String id,
            String wikiArticleId,
            String userId,
            int value,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.wikiArticleId = wikiArticleId;
        this.userId = userId;
        this.value = value;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWikiArticleId() {
        return wikiArticleId;
    }

    public void setWikiArticleId(String wikiArticleId) {
        this.wikiArticleId = wikiArticleId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WikiAppreciationJpaEntity that = (WikiAppreciationJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
