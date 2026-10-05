package com.universe.community.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mapping the {@code community_settings} singleton configuration table.
 */
@Entity
@Table(name = "community_settings")
public class CommunitySettingsJpaEntity {

    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    @Column(name = "publication_mode", nullable = false, length = 32)
    private String publicationMode;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by_user_id", nullable = true, length = 36, columnDefinition = "CHAR(36)")
    private String updatedByUserId;

    protected CommunitySettingsJpaEntity() {
    }

    public CommunitySettingsJpaEntity(
            String id,
            String publicationMode,
            long version,
            Instant updatedAt,
            String updatedByUserId
    ) {
        this.id = id;
        this.publicationMode = publicationMode;
        this.version = version;
        this.updatedAt = updatedAt;
        this.updatedByUserId = updatedByUserId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPublicationMode() {
        return publicationMode;
    }

    public void setPublicationMode(String publicationMode) {
        this.publicationMode = publicationMode;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedByUserId() {
        return updatedByUserId;
    }

    public void setUpdatedByUserId(String updatedByUserId) {
        this.updatedByUserId = updatedByUserId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommunitySettingsJpaEntity that = (CommunitySettingsJpaEntity) o;
        return version == that.version &&
                Objects.equals(id, that.id) &&
                Objects.equals(publicationMode, that.publicationMode) &&
                Objects.equals(updatedAt, that.updatedAt) &&
                Objects.equals(updatedByUserId, that.updatedByUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, publicationMode, version, updatedAt, updatedByUserId);
    }

    @Override
    public String toString() {
        return "CommunitySettingsJpaEntity{" +
                "id='" + id + '\'' +
                ", publicationMode='" + publicationMode + '\'' +
                ", version=" + version +
                ", updatedAt=" + updatedAt +
                ", updatedByUserId='" + updatedByUserId + '\'' +
                '}';
    }
}
