package com.universe.community.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA Entity mapping the {@code community_post_revisions} table.
 *
 * <p>Uses scalar string representations for UUIDs to maintain Clean Architecture
 * boundaries and avoid ORM coupling to parent {@link CommunityPostJpaEntity}.
 */
@Entity
@Table(name = "community_post_revisions")
public class CommunityPostRevisionJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "post_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String postId;

    @Column(
            name = "revision_number",
            nullable = false
    )
    private int revisionNumber;

    @Column(
            name = "editor_user_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String editorUserId;

    @Column(
            name = "previous_caption",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String previousCaption;

    @Column(
            name = "caption",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String caption;

    @Column(
            name = "edited_at",
            nullable = false
    )
    private Instant editedAt;

    protected CommunityPostRevisionJpaEntity() {
    }

    public CommunityPostRevisionJpaEntity(
            String id,
            String postId,
            int revisionNumber,
            String editorUserId,
            String previousCaption,
            String caption,
            Instant editedAt
    ) {
        this.id = id;
        this.postId = postId;
        this.revisionNumber = revisionNumber;
        this.editorUserId = editorUserId;
        this.previousCaption = previousCaption;
        this.caption = caption;
        this.editedAt = editedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public void setRevisionNumber(int revisionNumber) {
        this.revisionNumber = revisionNumber;
    }

    public String getEditorUserId() {
        return editorUserId;
    }

    public void setEditorUserId(String editorUserId) {
        this.editorUserId = editorUserId;
    }

    public String getPreviousCaption() {
        return previousCaption;
    }

    public void setPreviousCaption(String previousCaption) {
        this.previousCaption = previousCaption;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunityPostRevisionJpaEntity that = (CommunityPostRevisionJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CommunityPostRevisionJpaEntity{" +
                "id='" + id + '\'' +
                ", postId='" + postId + '\'' +
                ", revisionNumber=" + revisionNumber +
                ", editorUserId='" + editorUserId + '\'' +
                ", previousCaption='" + (previousCaption != null && previousCaption.length() > 20 ? previousCaption.substring(0, 20) + "..." : previousCaption) + '\'' +
                ", caption='" + (caption != null && caption.length() > 20 ? caption.substring(0, 20) + "..." : caption) + '\'' +
                ", editedAt=" + editedAt +
                '}';
    }
}
