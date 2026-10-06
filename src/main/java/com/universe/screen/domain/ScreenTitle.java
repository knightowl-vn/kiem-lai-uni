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
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "Title ID cannot be null.");
        this.slug = ScreenSlugValidator.validate(slug);
        this.title = validateTitle(title);
        this.originalTitle = normalizeOptionalText(originalTitle);
        this.synopsis = normalizeOptionalText(synopsis);
        this.structure = Objects.requireNonNull(structure, "ScreenStructure cannot be null.");
        this.presentation = Objects.requireNonNull(presentation, "ScreenPresentation cannot be null.");
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
                createdAt
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
     * Updates catalog metadata and classification.
     *
     * <p>Status and {@code publishedAt} remain unchanged.
     *
     * @param slug updated URL slug
     * @param title updated display title
     * @param originalTitle optional original title
     * @param synopsis optional synopsis
     * @param structure updated structure
     * @param presentation updated presentation style
     * @param posterMediaAssetId optional poster media asset scalar ID
     * @param backdropMediaAssetId optional backdrop media asset scalar ID
     * @param authoritativeUpdatedAt authoritative modification timestamp
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
            Instant authoritativeUpdatedAt
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

        this.slug = validatedSlug;
        this.title = validatedTitle;
        this.originalTitle = normalizedOriginalTitle;
        this.synopsis = normalizedSynopsis;
        this.structure = validatedStructure;
        this.presentation = validatedPresentation;
        this.posterMediaAssetId = posterMediaAssetId;
        this.backdropMediaAssetId = backdropMediaAssetId;
        this.updatedAt = authoritativeUpdatedAt;
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

    private static String normalizeOptionalText(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        return candidate.trim();
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
                ", status=" + status +
                ", publishedAt=" + publishedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
