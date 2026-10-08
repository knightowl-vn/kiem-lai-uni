package com.universe.screen.entry.admin.form;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;

public class EditScreenTitleForm {

    private String title;
    private String slug;
    private String originalTitle;
    private String synopsis;
    private ScreenStructure structure;
    private ScreenPresentation presentation;
    private String posterMediaAssetId;
    private String backdropMediaAssetId;

    public EditScreenTitleForm() {
    }

    public EditScreenTitleForm(
            String title,
            String slug,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            String posterMediaAssetId,
            String backdropMediaAssetId
    ) {
        this.title = title;
        this.slug = slug;
        this.originalTitle = originalTitle;
        this.synopsis = synopsis;
        this.structure = structure;
        this.presentation = presentation;
        this.posterMediaAssetId = posterMediaAssetId;
        this.backdropMediaAssetId = backdropMediaAssetId;
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

    public String getOriginalTitle() {
        return originalTitle;
    }

    public void setOriginalTitle(String originalTitle) {
        this.originalTitle = originalTitle;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public void setSynopsis(String synopsis) {
        this.synopsis = synopsis;
    }

    public ScreenStructure getStructure() {
        return structure;
    }

    public void setStructure(ScreenStructure structure) {
        this.structure = structure;
    }

    public ScreenPresentation getPresentation() {
        return presentation;
    }

    public void setPresentation(ScreenPresentation presentation) {
        this.presentation = presentation;
    }

    public String getPosterMediaAssetId() {
        return posterMediaAssetId;
    }

    public void setPosterMediaAssetId(String posterMediaAssetId) {
        this.posterMediaAssetId = posterMediaAssetId;
    }

    public String getBackdropMediaAssetId() {
        return backdropMediaAssetId;
    }

    public void setBackdropMediaAssetId(String backdropMediaAssetId) {
        this.backdropMediaAssetId = backdropMediaAssetId;
    }
}
