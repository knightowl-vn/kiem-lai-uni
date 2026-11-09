package com.universe.screen.entry.admin.form;

import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenTimelineMode;

public class EditScreenEntryForm {

    private String title;
    private String slug;
    private ScreenEntryType type;
    private Integer number;
    private String synopsis;
    private Long durationSeconds;
    private String videoMediaAssetId;
    private String thumbnailMediaAssetId;
    private boolean updateTimeline = false;
    private ScreenTimelineMode introMode;
    private String introStart;
    private String introEnd;
    private ScreenTimelineMode outroMode;
    private String outroStart;

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
            String thumbnailMediaAssetId,
            ScreenTimelineMode introMode,
            String introStart,
            String introEnd,
            ScreenTimelineMode outroMode,
            String outroStart
    ) {
        this.title = title;
        this.slug = slug;
        this.type = type;
        this.number = number;
        this.synopsis = synopsis;
        this.durationSeconds = durationSeconds;
        this.videoMediaAssetId = videoMediaAssetId;
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
        this.updateTimeline = true;
        this.introMode = introMode;
        this.introStart = introStart;
        this.introEnd = introEnd;
        this.outroMode = outroMode;
        this.outroStart = outroStart;
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
        this.updateTimeline = false;
        this.introMode = null;
        this.introStart = null;
        this.introEnd = null;
        this.outroMode = null;
        this.outroStart = null;
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

    public boolean isUpdateTimeline() {
        return updateTimeline;
    }

    public void setUpdateTimeline(boolean updateTimeline) {
        this.updateTimeline = updateTimeline;
    }

    public boolean hasTimelineUpdateIntent() {
        return updateTimeline
                || introMode != null
                || outroMode != null
                || (introStart != null && !introStart.isBlank())
                || (introEnd != null && !introEnd.isBlank())
                || (outroStart != null && !outroStart.isBlank());
    }

    public ScreenTimelineMode getIntroMode() {
        return introMode;
    }

    public void setIntroMode(ScreenTimelineMode introMode) {
        this.introMode = introMode;
        if (introMode != null) {
            this.updateTimeline = true;
        }
    }

    public String getIntroStart() {
        return introStart;
    }

    public void setIntroStart(String introStart) {
        this.introStart = introStart;
        if (introStart != null && !introStart.isBlank()) {
            this.updateTimeline = true;
        }
    }

    public String getIntroEnd() {
        return introEnd;
    }

    public void setIntroEnd(String introEnd) {
        this.introEnd = introEnd;
        if (introEnd != null && !introEnd.isBlank()) {
            this.updateTimeline = true;
        }
    }

    public ScreenTimelineMode getOutroMode() {
        return outroMode;
    }

    public void setOutroMode(ScreenTimelineMode outroMode) {
        this.outroMode = outroMode;
        if (outroMode != null) {
            this.updateTimeline = true;
        }
    }

    public String getOutroStart() {
        return outroStart;
    }

    public void setOutroStart(String outroStart) {
        this.outroStart = outroStart;
        if (outroStart != null && !outroStart.isBlank()) {
            this.updateTimeline = true;
        }
    }
}
