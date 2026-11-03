package com.universe.screen.entry.admin.form;

import com.universe.screen.domain.ScreenAiringStatus;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import org.springframework.web.multipart.MultipartFile;

public class CreateScreenTitleForm {

    private String title;
    private String slug;
    private String originalTitle;
    private String synopsis;
    private ScreenStructure structure;
    private ScreenPresentation presentation;
    private String posterMediaAssetId;
    private String backdropMediaAssetId;
    private MultipartFile posterFile;
    private MultipartFile backdropFile;
    private Integer totalEpisodeCount;
    private ScreenAiringStatus airingStatus;
    private Integer releaseYear;
    private String country;
    private String originalAuthor;
    private String director;
    private String productionStudio;

    public CreateScreenTitleForm() {
    }

    public CreateScreenTitleForm(
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

    public CreateScreenTitleForm(
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            MultipartFile posterFile,
            MultipartFile backdropFile
    ) {
        this.title = title;
        this.originalTitle = originalTitle;
        this.synopsis = synopsis;
        this.structure = structure;
        this.presentation = presentation;
        this.posterFile = posterFile;
        this.backdropFile = backdropFile;
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

    public MultipartFile getPosterFile() {
        return posterFile;
    }

    public void setPosterFile(MultipartFile posterFile) {
        this.posterFile = posterFile;
    }

    public MultipartFile getBackdropFile() {
        return backdropFile;
    }

    public void setBackdropFile(MultipartFile backdropFile) {
        this.backdropFile = backdropFile;
    }

    public Integer getTotalEpisodeCount() {
        return totalEpisodeCount;
    }

    public void setTotalEpisodeCount(Integer totalEpisodeCount) {
        this.totalEpisodeCount = totalEpisodeCount;
    }

    public ScreenAiringStatus getAiringStatus() {
        return airingStatus;
    }

    public void setAiringStatus(ScreenAiringStatus airingStatus) {
        this.airingStatus = airingStatus;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public void setReleaseYear(Integer releaseYear) {
        this.releaseYear = releaseYear;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getOriginalAuthor() {
        return originalAuthor;
    }

    public void setOriginalAuthor(String originalAuthor) {
        this.originalAuthor = originalAuthor;
    }

    public String getDirector() {
        return director;
    }

    public void setDirector(String director) {
        this.director = director;
    }

    public String getProductionStudio() {
        return productionStudio;
    }

    public void setProductionStudio(String productionStudio) {
        this.productionStudio = productionStudio;
    }
}
