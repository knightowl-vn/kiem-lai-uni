package com.universe.community.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA Entity for per-author Community post creation guard row.
 * Used exclusively for multi-instance row-level locking via SELECT ... FOR UPDATE.
 */
@Entity
@Table(name = "community_post_creation_guard")
public class CommunityPostCreationGuardJpaEntity {

    @Id
    @Column(
            name = "author_user_id",
            length = 36,
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private String authorUserId;

    protected CommunityPostCreationGuardJpaEntity() {
    }

    public CommunityPostCreationGuardJpaEntity(String authorUserId) {
        this.authorUserId = Objects.requireNonNull(authorUserId, "Author user ID cannot be null.");
    }

    public String getAuthorUserId() {
        return authorUserId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunityPostCreationGuardJpaEntity that = (CommunityPostCreationGuardJpaEntity) o;
        return Objects.equals(authorUserId, that.authorUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(authorUserId);
    }
}
