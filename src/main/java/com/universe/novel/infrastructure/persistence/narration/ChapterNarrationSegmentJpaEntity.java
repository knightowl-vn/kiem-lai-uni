package com.universe.novel.infrastructure.persistence.narration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapter_narration_segments",
        indexes = {
                @Index(
                        name = "idx_novel_chapter_narration_segments_chapter",
                        columnList = "chapter_id"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_segments_chapter_status_idx",
                        columnList = "chapter_id,status,segment_index"
                ),
                @Index(
                        name = "idx_novel_chapter_narration_segments_chapter_hash",
                        columnList = "chapter_id,content_hash"
                )
        }
)
public class ChapterNarrationSegmentJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "chapter_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String chapterId;

    @Column(
            name = "segment_index",
            nullable = false
    )
    private int segmentIndex;

    @Column(
            name = "text",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String text;

    @Column(
            name = "character_count",
            nullable = false
    )
    private int characterCount;

    @Column(
            name = "content_hash",
            nullable = false,
            length = 64
    )
    private String contentHash;

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

    public ChapterNarrationSegmentJpaEntity() {
    }

    public ChapterNarrationSegmentJpaEntity(
            String id,
            String chapterId,
            int segmentIndex,
            String text,
            int characterCount,
            String contentHash,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.chapterId = chapterId;
        this.segmentIndex = segmentIndex;
        this.text = text;
        this.characterCount = characterCount;
        this.contentHash = contentHash;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getCharacterCount() {
        return characterCount;
    }

    public void setCharacterCount(int characterCount) {
        this.characterCount = characterCount;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
