package com.universe.screen.entry.admin.form;

import com.universe.screen.domain.ScreenEntryType;

public class CreateScreenEntryForm {

    private String title;
    private String slug;
    private ScreenEntryType type;
    private Integer number;
    private String synopsis;
    private Long durationSeconds;

    public CreateScreenEntryForm() {
    }

    public CreateScreenEntryForm(
            String title,
            String slug,
            ScreenEntryType type,
            Integer number,
            String synopsis,
            Long durationSeconds
    ) {
        this.title = title;
        this.slug = slug;
        this.type = type;
        this.number = number;
        this.synopsis = synopsis;
        this.durationSeconds = durationSeconds;
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
}
