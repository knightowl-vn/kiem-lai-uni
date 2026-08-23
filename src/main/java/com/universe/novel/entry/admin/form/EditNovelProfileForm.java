package com.universe.novel.entry.admin.form;

import org.springframework.web.multipart.MultipartFile;

public class EditNovelProfileForm {

    private String title;

    private String author;

    private String description;

    private String coverImageUrl;

    private MultipartFile coverImageFile;

    private String status;

    public String getTitle() {
        return title;
    }

    public void setTitle(
            String title
    ) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(
            String author
    ) {
        this.author = author;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(
            String description
    ) {
        this.description = description;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(
            String coverImageUrl
    ) {
        this.coverImageUrl = coverImageUrl;
    }

    public MultipartFile getCoverImageFile() {
        return coverImageFile;
    }

    public void setCoverImageFile(
            MultipartFile coverImageFile
    ) {
        this.coverImageFile = coverImageFile;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(
            String status
    ) {
        this.status = status;
    }
}
