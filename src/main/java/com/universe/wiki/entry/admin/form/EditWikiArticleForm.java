package com.universe.wiki.entry.admin.form;

import com.universe.wiki.domain.article.ArticleType;

public class EditWikiArticleForm {

    private String title;

    private ArticleType articleType;

    private String summary;

    private String content;

    private String editSummary;


    public String getTitle() {
        return title;
    }

    public void setTitle(
            String title
    ) {
        this.title = title;
    }


    public ArticleType getArticleType() {
        return articleType;
    }

    public void setArticleType(
            ArticleType articleType
    ) {
        this.articleType = articleType;
    }


    public String getSummary() {
        return summary;
    }

    public void setSummary(
            String summary
    ) {
        this.summary = summary;
    }


    public String getContent() {
        return content;
    }

    public void setContent(
            String content
    ) {
        this.content = content;
    }


    public String getEditSummary() {
        return editSummary;
    }

    public void setEditSummary(
            String editSummary
    ) {
        this.editSummary = editSummary;
    }
}