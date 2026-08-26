package com.universe.novel.infrastructure.persistence.reader;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(
        name = "novel_reading_progress",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_reading_progress_user",
                        columnNames = {
                                "user_id"
                        }
                )
        }
)
public class ReadingProgressJpaEntity {

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
            name = "last_opened_chapter_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String lastOpenedChapterId;

    @Column(
            name = "highest_reached_chapter_number",
            nullable = false
    )
    private int highestReachedChapterNumber;

    @Version
    @Column(
            name = "persistence_version",
            nullable = false
    )
    private Long persistenceVersion;

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

    public ReadingProgressJpaEntity() {
    }

    public ReadingProgressJpaEntity(
            String id,
            String userId,
            String lastOpenedChapterId,
            int highestReachedChapterNumber,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.userId = userId;
        this.lastOpenedChapterId = lastOpenedChapterId;
        this.highestReachedChapterNumber = highestReachedChapterNumber;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public String getLastOpenedChapterId() {
        return lastOpenedChapterId;
    }

    public void setLastOpenedChapterId(String lastOpenedChapterId) {
        this.lastOpenedChapterId = lastOpenedChapterId;
    }

    public int getHighestReachedChapterNumber() {
        return highestReachedChapterNumber;
    }

    public void setHighestReachedChapterNumber(int highestReachedChapterNumber) {
        this.highestReachedChapterNumber = highestReachedChapterNumber;
    }

    public Long getPersistenceVersion() {
        return persistenceVersion;
    }

    public void setPersistenceVersion(Long persistenceVersion) {
        this.persistenceVersion = persistenceVersion;
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
}
