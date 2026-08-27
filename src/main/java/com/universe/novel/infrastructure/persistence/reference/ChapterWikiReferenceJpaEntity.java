package com.universe.novel.infrastructure.persistence.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "novel_chapter_wiki_references",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_novel_chapter_wiki_ref_chapter_term_occ",
                        columnNames = {
                                "chapter_id",
                                "normalized_term",
                                "occurrence_index"
                        }
                )
        }
)
public class ChapterWikiReferenceJpaEntity {

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
            name = "term",
            nullable = false,
            length = 100
    )
    private String term;

    @Column(
            name = "normalized_term",
            nullable = false,
            length = 100
    )
    private String normalizedTerm;

    @Column(
            name = "reference_scope",
            nullable = false,
            length = 30
    )
    private String referenceScope;

    @Column(
            name = "occurrence_index",
            nullable = false
    )
    private int occurrenceIndex;

    @Column(
            name = "context_snippet",
            length = 255
    )
    private String contextSnippet;

    @Column(
            name = "bound_content_version"
    )
    private Long boundContentVersion;

    @Column(
            name = "wiki_article_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String wikiArticleId;

    @Column(
            name = "created_by",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String createdBy;

    @Column(
            name = "updated_by",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String updatedBy;

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

    public ChapterWikiReferenceJpaEntity() {
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

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getNormalizedTerm() {
        return normalizedTerm;
    }

    public void setNormalizedTerm(String normalizedTerm) {
        this.normalizedTerm = normalizedTerm;
    }

    public String getReferenceScope() {
        return referenceScope;
    }

    public void setReferenceScope(String referenceScope) {
        this.referenceScope = referenceScope;
    }

    public int getOccurrenceIndex() {
        return occurrenceIndex;
    }

    public void setOccurrenceIndex(int occurrenceIndex) {
        this.occurrenceIndex = occurrenceIndex;
    }

    public String getContextSnippet() {
        return contextSnippet;
    }

    public void setContextSnippet(String contextSnippet) {
        this.contextSnippet = contextSnippet;
    }

    public Long getBoundContentVersion() {
        return boundContentVersion;
    }

    public void setBoundContentVersion(Long boundContentVersion) {
        this.boundContentVersion = boundContentVersion;
    }

    public String getWikiArticleId() {
        return wikiArticleId;
    }

    public void setWikiArticleId(String wikiArticleId) {
        this.wikiArticleId = wikiArticleId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
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
