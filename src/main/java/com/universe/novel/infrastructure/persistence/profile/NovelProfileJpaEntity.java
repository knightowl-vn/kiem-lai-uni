package com.universe.novel.infrastructure.persistence.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "novel_profile")
public class NovelProfileJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
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
            name = "author",
            nullable = false,
            length = 200
    )
    private String author;

    @Column(
            name = "description",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String description;

    @Column(
            name = "cover_image_url",
            length = 500
    )
    private String coverImageUrl;

    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private String status;

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

    protected NovelProfileJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSlug() {
        return slug;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(
            String title,
            String author,
            String description,
            String coverImageUrl,
            String status,
            Instant updatedAt
    ) {
        this.title = title;
        this.author = author;
        this.description = description;
        this.coverImageUrl = coverImageUrl;
        this.status = status;
        this.updatedAt = updatedAt;
    }
}