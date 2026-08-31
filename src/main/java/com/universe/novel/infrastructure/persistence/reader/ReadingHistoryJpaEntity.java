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
        name = "novel_reading_history",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_reading_history_user_chapter",
                        columnNames = {
                                "user_id",
                                "chapter_id"
                        }
                )
        }
)
public class ReadingHistoryJpaEntity {

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
            name = "first_read_at",
            nullable = false
    )
    private Instant firstReadAt;

    @Column(
            name = "last_read_at",
            nullable = false
    )
    private Instant lastReadAt;

    protected ReadingHistoryJpaEntity() {
    }

    public ReadingHistoryJpaEntity(
            String id,
            String userId,
            String chapterId,
            Instant firstReadAt,
            Instant lastReadAt
    ) {
        this.id = id;
        this.userId = userId;
        this.chapterId = chapterId;
        this.firstReadAt = firstReadAt;
        this.lastReadAt = lastReadAt;
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

    public Instant getFirstReadAt() {
        return firstReadAt;
    }

    public void setFirstReadAt(Instant firstReadAt) {
        this.firstReadAt = firstReadAt;
    }

    public Instant getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(Instant lastReadAt) {
        this.lastReadAt = lastReadAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReadingHistoryJpaEntity that = (ReadingHistoryJpaEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
