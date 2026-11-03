package com.universe.screen.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing an independent audiovisual Screen catalog title.
 *
 * <p>Each audiovisual installment (series or feature film) is modeled as a standalone
 * {@code ScreenTitle}. Related titles are grouped through {@link ScreenFranchise}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable scalar UUID identity ({@code id});</li>
 *   <li>Normalized public URL slug ({@code slug});</li>
 *   <li>Required display title ({@code title}) with outer whitespace trimmed;</li>
 *   <li>Optional original title ({@code originalTitle}) and synopsis ({@code synopsis}) with outer whitespace trimmed;</li>
 *   <li>Independent classification dimensions: {@link ScreenStructure} and {@link ScreenPresentation};</li>
 *   <li>Scalar UUID references to optional poster and backdrop media assets owned by Media bounded context;</li>
 *   <li>Publication lifecycle status: {@link ScreenPublicationStatus#DRAFT}, {@link ScreenPublicationStatus#PUBLISHED}, {@link ScreenPublicationStatus#ARCHIVED};</li>
 *   <li>{@code publishedAt} is null for {@code DRAFT}, non-null for {@code PUBLISHED}, and preserved upon {@code ARCHIVED};</li>
 *   <li>Authoritative caller-supplied timestamps; domain does not query wall-clock time internally;</li>
 *   <li>Zero derived entry-level counters (no durationSeconds, no totalEntries);</li>
 *   <li>Zero framework or persistence dependencies (pure Java domain).</li>
 * </ul>
 */
public final class ScreenTitle {

    private final UUID id;
    private String slug;
    private String title;
    private String originalTitle;
    private String synopsis;
    private ScreenStructure structure;
    private ScreenPresentation presentation;
    private UUID posterMediaAssetId;
    private UUID backdropMediaAssetId;
    private ScreenPublicationStatus status;
    private Instant publishedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private Integer totalEpisodeCount;
    private ScreenAiringStatus airingStatus;
    private Integer releaseYear;
    private String country;
    private String originalAuthor;
    private String director;
    private String productionStudio;

    /**
     * Canonical constructor for ScreenTitle.
     */
    public ScreenTitle(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            Integer totalEpisodeCount,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio
    ) {
        this.id = Objects.requireNonNull(id, "Title ID cannot be null.");
        this.slug = ScreenSlugValidator.validate(slug);
        this.title = validateTitle(title);
        this.originalTitle = normalizeOptionalText(originalTitle);
        this.synopsis = normalizeOptionalText(synopsis);
        this.structure = Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        this.presentation = Objects.requireNonNull(presentation, "ScreenPresentation cannot be null.");
        this.totalEpisodeCount = validateTotalEpisodeCount(this.structure, totalEpisodeCount);
        this.airingStatus = ScreenMetadataValidator.validateAiringStatus(this.structure, airingStatus);
        this.releaseYear = ScreenMetadataValidator.validateReleaseYear(releaseYear);
        this.country = ScreenMetadataValidator.validateCountry(country);
        this.originalAuthor = ScreenMetadataValidator.validateOriginalAuthor(originalAuthor);
        this.director = ScreenMetadataValidator.validateDirector(director);
        this.productionStudio = ScreenMetadataValidator.validateProductionStudio(productionStudio);
        this.posterMediaAssetId = posterMediaAssetId;
        this.backdropMediaAssetId = backdropMediaAssetId;
        this.status = Objects.requireNonNull(status, "ScreenPublicationStatus cannot be null.");
        this.createdAt = Objects.requireNonNull(createdAt, "CreatedAt timestamp cannot be null.");
        this.updatedAt = Objects.requireNonNull(updatedAt, "UpdatedAt timestamp cannot be null.");

        if (this.updatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }

        validatePublicationState(this.status, publishedAt, this.createdAt, this.updatedAt);
        this.publishedAt = publishedAt;
    }

    /**
     * Backwards-compatible constructor with totalEpisodeCount and null release metadata.
     */
    public ScreenTitle(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            Integer totalEpisodeCount
    ) {
        this(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                status,
                publishedAt,
                createdAt,
                updatedAt,
                totalEpisodeCount,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Backwards-compatible constructor without totalEpisodeCount (defaults to null).
     */
    public ScreenTitle(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                status,
                publishedAt,
                createdAt,
                updatedAt,
                null
        );
    }

    /**
     * Factory method to create a new ScreenTitle in DRAFT status with full release metadata.
     */
    public static ScreenTitle createDraft(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant createdAt,
            Integer totalEpisodeCount,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio
    ) {
        return new ScreenTitle(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                ScreenPublicationStatus.DRAFT,
                null,
                createdAt,
                createdAt,
                totalEpisodeCount,
                airingStatus,
                releaseYear,
                country,
                originalAuthor,
                director,
                productionStudio
        );
    }

    /**
     * Factory method to create a new ScreenTitle in DRAFT status with optional totalEpisodeCount.
     */
    public static ScreenTitle createDraft(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant createdAt,
            Integer totalEpisodeCount
    ) {
        return createDraft(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                createdAt,
                totalEpisodeCount,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Factory method to create a new ScreenTitle in DRAFT status.
     */
    public static ScreenTitle createDraft(
            UUID id,
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant createdAt
    ) {
        return createDraft(
                id,
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                createdAt,
                null
        );
    }

    /**
     * Overload to create a minimal draft ScreenTitle without optional metadata.
     */
    public static ScreenTitle createDraft(
            UUID id,
            String slug,
            String title,
            ScreenStructure structure,
            ScreenPresentation presentation,
            Instant createdAt
    ) {
        return createDraft(
                id,
                slug,
                title,
                null,
                null,
                structure,
                presentation,
                null,
                null,
                createdAt
        );
    }

    /**
     * Publishes this ScreenTitle.
     *
     * <p>Sets status to {@link ScreenPublicationStatus#PUBLISHED} and records the authoritative
     * publication timestamp.
     *
     * @param authoritativePublishedAt authoritative publication timestamp
     * @throws NullPointerException if timestamp is null
     * @throws IllegalStateException if title is already published or archived
     * @throws IllegalArgumentException if publication timestamp is before createdAt
     */
    public void publish(Instant authoritativePublishedAt) {
        Objects.requireNonNull(authoritativePublishedAt, "Publication timestamp cannot be null.");

        if (this.status == ScreenPublicationStatus.PUBLISHED) {
            throw new IllegalStateException("ScreenTitle is already published.");
        }
        if (this.status == ScreenPublicationStatus.ARCHIVED) {
            throw new IllegalStateException("Archived ScreenTitle cannot be published.");
        }
        if (authoritativePublishedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Publication timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativePublishedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("Publication timestamp cannot be before current updatedAt timestamp.");
        }

        this.status = ScreenPublicationStatus.PUBLISHED;
        this.publishedAt = authoritativePublishedAt;
        this.updatedAt = authoritativePublishedAt;
    }

    /**
     * Archives this ScreenTitle.
     *
     * <p>Sets status to {@link ScreenPublicationStatus#ARCHIVED}.
     * Any prior {@code publishedAt} timestamp remains unchanged.
     *
     * @param authoritativeArchivedAt authoritative archive timestamp
     * @throws NullPointerException if timestamp is null
     * @throws IllegalStateException if title is already archived
     * @throws IllegalArgumentException if archive timestamp is before createdAt or current updatedAt
     */
    public void archive(Instant authoritativeArchivedAt) {
        Objects.requireNonNull(authoritativeArchivedAt, "Archive timestamp cannot be null.");

        if (this.status == ScreenPublicationStatus.ARCHIVED) {
            throw new IllegalStateException("ScreenTitle is already archived.");
        }
        if (authoritativeArchivedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("Archive timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeArchivedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("Archive timestamp cannot be before current updatedAt timestamp.");
        }

        this.status = ScreenPublicationStatus.ARCHIVED;
        this.updatedAt = authoritativeArchivedAt;
    }

    /**
     * Updates catalog metadata and classification including release metadata.
     *
     * <p>Status and {@code publishedAt} remain unchanged.
     */
    public void updateDetails(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant authoritativeUpdatedAt,
            Integer totalEpisodeCount,
            ScreenAiringStatus airingStatus,
            Integer releaseYear,
            String country,
            String originalAuthor,
            String director,
            String productionStudio
    ) {
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        String validatedSlug = ScreenSlugValidator.validate(slug);
        String validatedTitle = validateTitle(title);
        String normalizedOriginalTitle = normalizeOptionalText(originalTitle);
        String normalizedSynopsis = normalizeOptionalText(synopsis);
        ScreenStructure validatedStructure = Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        ScreenPresentation validatedPresentation = Objects.requireNonNull(presentation, "ScreenPresentation cannot be null.");
        Integer validatedTotalEpisodeCount = validateTotalEpisodeCount(validatedStructure, totalEpisodeCount);
        ScreenAiringStatus validatedAiringStatus = ScreenMetadataValidator.validateAiringStatus(validatedStructure, airingStatus);
        Integer validatedReleaseYear = ScreenMetadataValidator.validateReleaseYear(releaseYear);
        String normalizedCountry = ScreenMetadataValidator.validateCountry(country);
        String normalizedOriginalAuthor = ScreenMetadataValidator.validateOriginalAuthor(originalAuthor);
        String normalizedDirector = ScreenMetadataValidator.validateDirector(director);
        String normalizedProductionStudio = ScreenMetadataValidator.validateProductionStudio(productionStudio);

        this.slug = validatedSlug;
        this.title = validatedTitle;
        this.originalTitle = normalizedOriginalTitle;
        this.synopsis = normalizedSynopsis;
        this.structure = validatedStructure;
        this.presentation = validatedPresentation;
        this.totalEpisodeCount = validatedTotalEpisodeCount;
        this.airingStatus = validatedAiringStatus;
        this.releaseYear = validatedReleaseYear;
        this.country = normalizedCountry;
        this.originalAuthor = normalizedOriginalAuthor;
        this.director = normalizedDirector;
        this.productionStudio = normalizedProductionStudio;
        this.posterMediaAssetId = posterMediaAssetId;
        this.backdropMediaAssetId = backdropMediaAssetId;
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Backward-compatible updateDetails preserving existing release metadata.
     */
    public void updateDetails(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant authoritativeUpdatedAt,
            Integer totalEpisodeCount
    ) {
        updateDetails(
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                authoritativeUpdatedAt,
                totalEpisodeCount,
                this.airingStatus,
                this.releaseYear,
                this.country,
                this.originalAuthor,
                this.director,
                this.productionStudio
        );
    }

    public void updateDetails(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant authoritativeUpdatedAt
    ) {
        updateDetails(
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                authoritativeUpdatedAt,
                this.totalEpisodeCount
        );
    }

    /**
     * Convenience alias for {@link #updateDetails}.
     */
    public void update(
            String slug,
            String title,
            String originalTitle,
            String synopsis,
            ScreenStructure structure,
            ScreenPresentation presentation,
            UUID posterMediaAssetId,
            UUID backdropMediaAssetId,
            Instant authoritativeUpdatedAt
    ) {
        updateDetails(
                slug,
                title,
                originalTitle,
                synopsis,
                structure,
                presentation,
                posterMediaAssetId,
                backdropMediaAssetId,
                authoritativeUpdatedAt
        );
    }

    /**
     * Updates poster and backdrop media asset references.
     *
     * @param newPosterMediaAssetId scalar poster asset ID
     * @param newBackdropMediaAssetId scalar backdrop asset ID
     * @param authoritativeUpdatedAt authoritative modification timestamp
     */
    public void updateMedia(
            UUID newPosterMediaAssetId,
            UUID newBackdropMediaAssetId,
            Instant authoritativeUpdatedAt
    ) {
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        this.posterMediaAssetId = newPosterMediaAssetId;
        this.backdropMediaAssetId = newBackdropMediaAssetId;
        this.updatedAt = authoritativeUpdatedAt;
    }

    private static String validateTitle(String candidate) {
        Objects.requireNonNull(candidate, "Title cannot be null.");
        String trimmed = candidate.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Title cannot be blank.");
        }
        return trimmed;
    }

    private static Integer validateTotalEpisodeCount(ScreenStructure structure, Integer candidate) {
        if (structure == ScreenStructure.FEATURE) {
            if (candidate != null) {
                throw new IllegalArgumentException("FEATURE structure cannot have a total episode count.");
            }
            return null;
        }
        if (candidate != null && candidate <= 0) {
            throw new IllegalArgumentException("Total episode count must be a positive integer.");
        }
        return candidate;
    }

    private static ScreenAiringStatus validateAiringStatus(ScreenStructure structure, ScreenAiringStatus candidate) {
        return ScreenMetadataValidator.validateAiringStatus(structure, candidate);
    }

    private static Integer validateReleaseYear(Integer candidate) {
        return ScreenMetadataValidator.validateReleaseYear(candidate);
    }

    private static String normalizeOptionalText(String candidate) {
        return ScreenMetadataValidator.normalizeOptionalText(candidate);
    }

    private static void validatePublicationState(
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        if (status == ScreenPublicationStatus.DRAFT && publishedAt != null) {
            throw new IllegalArgumentException("Draft ScreenTitle cannot have a publishedAt timestamp.");
        }
        if (status == ScreenPublicationStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("Published ScreenTitle must have a publishedAt timestamp.");
        }
        if (publishedAt != null) {
            if (publishedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("PublishedAt timestamp cannot be before createdAt timestamp.");
            }
            if (updatedAt.isBefore(publishedAt)) {
                throw new IllegalArgumentException("UpdatedAt timestamp cannot be before publishedAt timestamp.");
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public ScreenStructure getStructure() {
        return structure;
    }

    public ScreenPresentation getPresentation() {
        return presentation;
    }

    public UUID getPosterMediaAssetId() {
        return posterMediaAssetId;
    }

    public UUID getBackdropMediaAssetId() {
        return backdropMediaAssetId;
    }

    public ScreenPublicationStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Integer getTotalEpisodeCount() {
        return totalEpisodeCount;
    }

    public ScreenAiringStatus getAiringStatus() {
        return airingStatus;
    }

    public Integer getReleaseYear() {
        return releaseYear;
    }

    public String getCountry() {
        return country;
    }

    public String getOriginalAuthor() {
        return originalAuthor;
    }

    public String getDirector() {
        return director;
    }

    public String getProductionStudio() {
        return productionStudio;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScreenTitle that = (ScreenTitle) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScreenTitle{" +
                "id=" + id +
                ", slug='" + slug + '\'' +
                ", title='" + title + '\'' +
                ", structure=" + structure +
                ", presentation=" + presentation +
                ", totalEpisodeCount=" + totalEpisodeCount +
                ", status=" + status +
                ", publishedAt=" + publishedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
