package com.universe.wiki.infrastructure.persistence.image;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "wiki_revision_image_references"
)
public class WikiRevisionImageReferenceJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "revision_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String revisionId;

    @Column(
            name = "image_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String imageId;

    public WikiRevisionImageReferenceJpaEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(
            String id
    ) {
        this.id = id;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(
            String revisionId
    ) {
        this.revisionId = revisionId;
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