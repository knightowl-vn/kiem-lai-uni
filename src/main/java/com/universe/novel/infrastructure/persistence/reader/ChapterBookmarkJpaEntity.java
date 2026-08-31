package com.universe.novel.infrastructure.persistence.reader;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "novel_chapter_bookmarks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapter_bookmarks_user_chapter",
                        columnNames = {
                                "user_id",
                                "chapter_id"
                        }
                )
        }
)
public class ChapterBookmarkJpaEntity {

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
            name = "chapter_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String chapterId;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected ChapterBookmarkJpaEntity() {
    }

    public ChapterBookmarkJpaEntity(
            String id,
            String userId,
            String chapterId,
            Instant createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.chapterId = chapterId;
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

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
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
        ChapterBookmarkJpaEntity that = (ChapterBookmarkJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
