package com.universe.novel.entry.admin.form;

import java.util.UUID;

/**
 * Form dữ liệu gửi từ Admin để gán/cập nhật liên kết Wiki toàn chương (CHAPTER_WIDE).
 */
public class BindChapterWideWikiReferenceForm {

    private String term;
    private UUID wikiArticleId;

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public UUID getWikiArticleId() {
        return wikiArticleId;
    }

    public void setWikiArticleId(UUID wikiArticleId) {
        this.wikiArticleId = wikiArticleId;
    }
}
