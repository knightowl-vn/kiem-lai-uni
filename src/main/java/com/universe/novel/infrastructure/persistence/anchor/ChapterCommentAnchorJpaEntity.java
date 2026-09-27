package com.universe.novel.infrastructure.persistence.anchor;

import com.universe.novel.domain.anchor.ChapterCommentAnchorKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "novel_chapter_comment_anchors")
public class ChapterCommentAnchorJpaEntity implements Persistable<String> {

    @Id
    @Column(
            name = "root_comment_id",
            nullable = false,
            length = 36,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private String rootCommentId;

    @Column(
            name = "chapter_id",
            nullable = false,
            length = 36,
            updatable = false,
            columnDefinition = "CHAR(36)"
    )
    private String chapterId;

    @Column(
            name = "content_version",
            nullable = false,
            updatable = false
    )
    private long contentVersion;

    @Column(
            name = "block_key",
            nullable = false,
            length = 100,
            updatable = false
    )
    private String blockKey;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "anchor_kind",
            nullable = false,
            length = 20,
            updatable = false
    )
    private ChapterCommentAnchorKind anchorKind;

    @Column(
            name = "start_offset",
            updatable = false
    )
    private Integer startOffset;

    @Column(
            name = "end_offset",
            updatable = false
    )
    private Integer endOffset;

    @Column(
            name = "selected_text",
            nullable = false,
            updatable = false,
            columnDefinition = "MEDIUMTEXT"
    )
    private String selectedText;

    @Column(
            name = "context_before",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String contextBefore;

    @Column(
            name = "context_after",
            nullable = false,
            length = 64,
            updatable = false
    )
    private String contextAfter;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    public ChapterCommentAnchorJpaEntity() {
    }

    public ChapterCommentAnchorJpaEntity(
            String rootCommentId,
            String chapterId,
            long contentVersion,
            String blockKey,
            ChapterCommentAnchorKind anchorKind,
            Integer startOffset,
            Integer endOffset,
            String selectedText,
            String contextBefore,
            String contextAfter,
            Instant createdAt
    ) {
        this.rootCommentId = rootCommentId;
        this.chapterId = chapterId;
        this.contentVersion = contentVersion;
        this.blockKey = blockKey;
        this.anchorKind = anchorKind;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.selectedText = selectedText;
        this.contextBefore = contextBefore;
        this.contextAfter = contextAfter;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return rootCommentId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getRootCommentId() {
        return rootCommentId;
    }

    public void setRootCommentId(String rootCommentId) {
        this.rootCommentId = rootCommentId;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public void setContentVersion(long contentVersion) {
        this.contentVersion = contentVersion;
    }

    public String getBlockKey() {
        return blockKey;
    }

    public void setBlockKey(String blockKey) {
        this.blockKey = blockKey;
    }

    public ChapterCommentAnchorKind getAnchorKind() {
        return anchorKind;
    }

    public void setAnchorKind(ChapterCommentAnchorKind anchorKind) {
        this.anchorKind = anchorKind;
    }

    public Integer getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(Integer startOffset) {
        this.startOffset = startOffset;
    }

    public Integer getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(Integer endOffset) {
        this.endOffset = endOffset;
    }

    public String getSelectedText() {
        return selectedText;
    }

    public void setSelectedText(String selectedText) {
        this.selectedText = selectedText;
    }

    public String getContextBefore() {
        return contextBefore;
    }

    public void setContextBefore(String contextBefore) {
        this.contextBefore = contextBefore;
    }

    public String getContextAfter() {
        return contextAfter;
    }

    public void setContextAfter(String contextAfter) {
        this.contextAfter = contextAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
