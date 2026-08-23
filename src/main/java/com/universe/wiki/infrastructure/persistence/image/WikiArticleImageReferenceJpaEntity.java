package com.universe.wiki.infrastructure.persistence.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "wiki_article_image_references"
)
public class WikiArticleImageReferenceJpaEntity {

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
            name = "image_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String imageId;

    public WikiArticleImageReferenceJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(
            String id
    ) {
        this.id = id;
    }

    public String getArticleId() {
        return articleId;
    }

    public void setArticleId(
            String articleId
    ) {
        this.articleId = articleId;
    }

    public String getImageId() {
        return imageId;
    }

    public void setImageId(
            String imageId
    ) {
        this.imageId = imageId;
    }
}