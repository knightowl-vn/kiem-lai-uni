package com.universe.screen.entry.admin.form;

import com.universe.screen.domain.ScreenEntryType;

public class EditScreenEntryForm {

    private String title;
    private String slug;
    private ScreenEntryType type;
    private Integer number;
    private String synopsis;
    private Long durationSeconds;
    private String videoMediaAssetId;
    private String thumbnailMediaAssetId;

    public EditScreenEntryForm() {
    }

    public EditScreenEntryForm(
            String title,
            String slug,
            ScreenEntryType type,
            Integer number,
            String synopsis,
            Long durationSeconds,
            String videoMediaAssetId,
            String thumbnailMediaAssetId
    ) {
        this.title = title;
        this.slug = slug;
        this.type = type;
        this.number = number;
        this.synopsis = synopsis;
        this.durationSeconds = durationSeconds;
        this.videoMediaAssetId = videoMediaAssetId;
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public ScreenEntryType getType() {
        return type;
    }

    public void setType(ScreenEntryType type) {
        this.type = type;
    }

    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public void setSynopsis(String synopsis) {
        this.synopsis = synopsis;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getVideoMediaAssetId() {
        return videoMediaAssetId;
    }

    public void setVideoMediaAssetId(String videoMediaAssetId) {
        this.videoMediaAssetId = videoMediaAssetId;
    }

    public String getThumbnailMediaAssetId() {
        return thumbnailMediaAssetId;
    }

    public void setThumbnailMediaAssetId(String thumbnailMediaAssetId) {
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
    }
}
