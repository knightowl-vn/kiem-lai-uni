package com.universe.novel.entry.admin.form;

import java.util.UUID;

/**
 * Form dữ liệu gửi từ Admin để gán/cập nhật liên kết Wiki theo vị trí xuất hiện cụ thể (OCCURRENCE_SPECIFIC).
 */
public class BindOccurrenceSpecificWikiReferenceForm {

    private String term;
    private Integer occurrenceIndex;
    private String contextSnippet;
    private UUID wikiArticleId;

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public Integer getOccurrenceIndex() {
        return occurrenceIndex;
    }

    public void setOccurrenceIndex(Integer occurrenceIndex) {
        this.occurrenceIndex = occurrenceIndex;
    }

    public String getContextSnippet() {
        return contextSnippet;
    }

    public void setContextSnippet(String contextSnippet) {
        this.contextSnippet = contextSnippet;
    }

    public UUID getWikiArticleId() {
        return wikiArticleId;
    }

    public void setWikiArticleId(UUID wikiArticleId) {
        this.wikiArticleId = wikiArticleId;
    }
}
