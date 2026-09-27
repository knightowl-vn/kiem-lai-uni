package com.universe.wiki.entry.admin.form;

import com.universe.wiki.domain.article.ArticleType;
import org.springframework.web.multipart.MultipartFile;

public class CreateWikiArticleForm {

    private String title;

    private ArticleType articleType;

    private String summary;

    private String content;

    private String editSummary;

    private MultipartFile coverImageFile;

    private Integer coverPositionX = 50;

    private Integer coverPositionY = 50;

    public CreateWikiArticleForm() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(
            String title
    ) {
        this.title =
                title;
    }

    public ArticleType getArticleType() {
        return articleType;
    }

    public void setArticleType(
            ArticleType articleType
    ) {
        this.articleType =
                articleType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(
            String summary
    ) {
        this.summary =
                summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(
            String content
    ) {
        this.content =
                content;
    }

    public String getEditSummary() {
        return editSummary;
    }

    public void setEditSummary(
            String editSummary
    ) {
        this.editSummary =
                editSummary;
    }

    public MultipartFile getCoverImageFile() {
        return coverImageFile;
    }

    public void setCoverImageFile(
            MultipartFile coverImageFile
    ) {
        this.coverImageFile =
                coverImageFile;
    }

    public Integer getCoverPositionX() {
        return coverPositionX;
    }

    public void setCoverPositionX(
            Integer coverPositionX
    ) {
        this.coverPositionX =
                coverPositionX;
    }

    public Integer getCoverPositionY() {
        return coverPositionY;
    }

    public void setCoverPositionY(
            Integer coverPositionY
    ) {
        this.coverPositionY =
                coverPositionY;
    }
}