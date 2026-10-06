package com.universe.screen.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate Root representing an individual playable audiovisual entry belonging to a {@link ScreenTitle}.
 *
 * <p>A {@code ScreenEntry} can be an episodic installment ({@link ScreenEntryType#EPISODE}),
 * a feature-length film presentation ({@link ScreenEntryType#FEATURE}), an additional special
 * ({@link ScreenEntryType#SPECIAL}), or a promotional video ({@link ScreenEntryType#TRAILER}).
 *
 * <p>Invariants:
 * <ul>
 *   <li>Immutable scalar UUID identity ({@code id});</li>
 *   <li>Immutable scalar reference to parent title ({@code titleId});</li>
 *   <li>Normalized public URL slug ({@code slug});</li>
 *   <li>ScreenEntryType determines numbering constraints:
 *     <ul>
 *       <li>{@code EPISODE}: required positive integer number (&gt; 0);</li>
 *       <li>{@code FEATURE}: number must be null (movies are never modeled as fake "Episode 1");</li>
 *       <li>{@code SPECIAL}: nullable number; if present, must be positive (&gt; 0);</li>
 *       <li>{@code TRAILER}: nullable number; if present, must be positive (&gt; 0);</li>
 *     </ul>
 *   </li>
 *   <li>Required display title ({@code title}) with outer whitespace trimmed;</li>
 *   <li>Optional synopsis ({@code synopsis}) with outer whitespace trimmed;</li>
 *   <li>Scalar UUID references to optional playable video and thumbnail media assets owned by Media bounded context;</li>
 *   <li>Nullable duration in seconds ({@code durationSeconds}); if present, must be positive (&gt; 0);</li>
 *   <li>Publication lifecycle status: {@link ScreenPublicationStatus#DRAFT}, {@link ScreenPublicationStatus#PUBLISHED}, {@link ScreenPublicationStatus#ARCHIVED};</li>
 *   <li>{@code publishedAt} is null for {@code DRAFT}, non-null for {@code PUBLISHED}, and preserved upon {@code ARCHIVED};</li>
 *   <li>Authoritative caller-supplied timestamps; domain does not query wall-clock time internally;</li>
 *   <li>Failed mutations are atomic: candidate values are validated and normalized before any internal field assignment;</li>
 *   <li>Zero framework or persistence dependencies (pure Java domain).</li>
 * </ul>
 */
public final class ScreenEntry {

    private final UUID id;
    private final UUID titleId;
    private String slug;
    private ScreenEntryType type;
    private Integer number;
    private String title;
    private String synopsis;
    private UUID videoMediaAssetId;
    private UUID thumbnailMediaAssetId;
    private Long durationSeconds;
    private ScreenPublicationStatus status;
    private Instant publishedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Canonical constructor for ScreenEntry.
     */
    public ScreenEntry(
            UUID id,
            UUID titleId,
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            UUID videoMediaAssetId,
            UUID thumbnailMediaAssetId,
            Long durationSeconds,
            ScreenPublicationStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "Entry ID cannot be null.");
        this.titleId = Objects.requireNonNull(titleId, "Title ID cannot be null.");
        this.slug = ScreenSlugValidator.validate(slug);
        this.type = Objects.requireNonNull(type, "ScreenEntryType cannot be null.");
        this.number = validateNumberForType(this.type, number);
        this.title = validateTitle(title);
        this.synopsis = normalizeOptionalText(synopsis);
        this.videoMediaAssetId = videoMediaAssetId;
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
        this.durationSeconds = validateDuration(durationSeconds);
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
     * Factory method to create a new ScreenEntry in DRAFT status with full optional metadata.
     */
    public static ScreenEntry createDraft(
            UUID id,
            UUID titleId,
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            UUID videoMediaAssetId,
            UUID thumbnailMediaAssetId,
            Long durationSeconds,
            Instant createdAt
    ) {
        return new ScreenEntry(
                id,
                titleId,
                slug,
                type,
                number,
                title,
                synopsis,
                videoMediaAssetId,
                thumbnailMediaAssetId,
                durationSeconds,
                ScreenPublicationStatus.DRAFT,
                null,
                createdAt,
                createdAt
        );
    }

    /**
     * Overload to create a minimal draft ScreenEntry without optional metadata.
     */
    public static ScreenEntry createDraft(
            UUID id,
            UUID titleId,
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            Instant createdAt
    ) {
        return createDraft(
                id,
                titleId,
                slug,
                type,
                number,
                title,
                null,
                null,
                null,
                null,
                createdAt
        );
    }

    /**
     * Publishes this ScreenEntry.
     *
     * <p>Sets status to {@link ScreenPublicationStatus#PUBLISHED} and records the authoritative
     * publication timestamp.
     *
     * @param authoritativePublishedAt authoritative publication timestamp
     * @throws NullPointerException if timestamp is null
     * @throws IllegalStateException if entry is already published or archived
     * @throws IllegalArgumentException if publication timestamp is before createdAt or current updatedAt
     */
    public void publish(Instant authoritativePublishedAt) {
        Objects.requireNonNull(authoritativePublishedAt, "Publication timestamp cannot be null.");

        if (this.status == ScreenPublicationStatus.PUBLISHED) {
            throw new IllegalStateException("ScreenEntry is already published.");
        }
        if (this.status == ScreenPublicationStatus.ARCHIVED) {
            throw new IllegalStateException("Archived ScreenEntry cannot be published.");
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
     * Archives this ScreenEntry.
     *
     * <p>Sets status to {@link ScreenPublicationStatus#ARCHIVED}.
     * Any prior {@code publishedAt} timestamp remains unchanged.
     *
     * @param authoritativeArchivedAt authoritative archive timestamp
     * @throws NullPointerException if timestamp is null
     * @throws IllegalStateException if entry is already archived
     * @throws IllegalArgumentException if archive timestamp is before createdAt or current updatedAt
     */
    public void archive(Instant authoritativeArchivedAt) {
        Objects.requireNonNull(authoritativeArchivedAt, "Archive timestamp cannot be null.");

        if (this.status == ScreenPublicationStatus.ARCHIVED) {
            throw new IllegalStateException("ScreenEntry is already archived.");
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
     * Updates all editable entry details atomically.
     *
     * <p>Validates and normalizes all candidate values prior to modifying aggregate state.
     *
     * @param slug updated URL slug
     * @param type updated entry type
     * @param number updated entry number (validated according to entry type)
     * @param title updated display title
     * @param synopsis optional synopsis
     * @param durationSeconds optional duration in seconds (&gt; 0 when present)
     * @param videoMediaAssetId optional playable video asset scalar ID
     * @param thumbnailMediaAssetId optional thumbnail asset scalar ID
     * @param authoritativeUpdatedAt authoritative modification timestamp
     */
    public void updateDetails(
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            Long durationSeconds,
            UUID videoMediaAssetId,
            UUID thumbnailMediaAssetId,
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
        ScreenEntryType validatedType = Objects.requireNonNull(type, "ScreenEntryType cannot be null.");
        Integer validatedNumber = validateNumberForType(validatedType, number);
        String validatedTitle = validateTitle(title);
        String normalizedSynopsis = normalizeOptionalText(synopsis);
        Long validatedDuration = validateDuration(durationSeconds);

        this.slug = validatedSlug;
        this.type = validatedType;
        this.number = validatedNumber;
        this.title = validatedTitle;
        this.synopsis = normalizedSynopsis;
        this.durationSeconds = validatedDuration;
        this.videoMediaAssetId = videoMediaAssetId;
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Convenience alias for {@link #updateDetails}.
     */
    public void update(
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
            Long durationSeconds,
            UUID videoMediaAssetId,
            UUID thumbnailMediaAssetId,
            Instant authoritativeUpdatedAt
    ) {
        updateDetails(
                slug,
                type,
                number,
                title,
                synopsis,
                durationSeconds,
                videoMediaAssetId,
                thumbnailMediaAssetId,
                authoritativeUpdatedAt
        );
    }

    /**
     * Updates entry classification and metadata without altering media references or duration.
     */
    public void updateMetadata(
            String slug,
            ScreenEntryType type,
            Integer number,
            String title,
            String synopsis,
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
        ScreenEntryType validatedType = Objects.requireNonNull(type, "ScreenEntryType cannot be null.");
        Integer validatedNumber = validateNumberForType(validatedType, number);
        String validatedTitle = validateTitle(title);
        String normalizedSynopsis = normalizeOptionalText(synopsis);

        this.slug = validatedSlug;
        this.type = validatedType;
        this.number = validatedNumber;
        this.title = validatedTitle;
        this.synopsis = normalizedSynopsis;
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Updates video and thumbnail media asset scalar references.
     *
     * @param newVideoMediaAssetId scalar video asset ID
     * @param newThumbnailMediaAssetId scalar thumbnail asset ID
     * @param authoritativeUpdatedAt authoritative modification timestamp
     */
    public void updateMedia(
            UUID newVideoMediaAssetId,
            UUID newThumbnailMediaAssetId,
            Instant authoritativeUpdatedAt
    ) {
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        this.videoMediaAssetId = newVideoMediaAssetId;
        this.thumbnailMediaAssetId = newThumbnailMediaAssetId;
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Updates entry duration in seconds.
     *
     * @param newDurationSeconds duration in seconds (nullable; &gt; 0 when present)
     * @param authoritativeUpdatedAt authoritative modification timestamp
     */
    public void updateDuration(
            Long newDurationSeconds,
            Instant authoritativeUpdatedAt
    ) {
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        Long validatedDuration = validateDuration(newDurationSeconds);
        this.durationSeconds = validatedDuration;
        this.updatedAt = authoritativeUpdatedAt;
    }

    private static Integer validateNumberForType(ScreenEntryType type, Integer number) {
        Objects.requireNonNull(type, "ScreenEntryType cannot be null.");
        switch (type) {
            case EPISODE -> {
                if (number == null) {
                    throw new IllegalArgumentException("EPISODE entry requires a non-null entry number.");
                }
                if (number <= 0) {
                    throw new IllegalArgumentException("EPISODE entry number must be positive: " + number);
                }
                return number;
            }
            case FEATURE -> {
                if (number != null) {
                    throw new IllegalArgumentException("FEATURE entry must not have an entry number.");
                }
                return null;
            }
            case SPECIAL -> {
                if (number != null && number <= 0) {
                    throw new IllegalArgumentException("SPECIAL entry number must be positive when provided: " + number);
                }
                return number;
            }
            case TRAILER -> {
                if (number != null && number <= 0) {
                    throw new IllegalArgumentException("TRAILER entry number must be positive when provided: " + number);
                }
                return number;
            }
            default -> throw new IllegalArgumentException("Unsupported ScreenEntryType: " + type);
        }
    }

    private static Long validateDuration(Long durationSeconds) {
        if (durationSeconds == null) {
            return null;
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("Duration must be positive: " + durationSeconds);
        }
        return durationSeconds;
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
            throw new IllegalArgumentException("Draft ScreenEntry cannot have a publishedAt timestamp.");
        }
        if (status == ScreenPublicationStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("Published ScreenEntry must have a publishedAt timestamp.");
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

    public UUID getTitleId() {
        return titleId;
    }

    public String getSlug() {
        return slug;
    }

    public ScreenEntryType getType() {
        return type;
    }

    public Integer getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public UUID getVideoMediaAssetId() {
        return videoMediaAssetId;
    }

    public UUID getThumbnailMediaAssetId() {
        return thumbnailMediaAssetId;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
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

    public boolean isEpisode() {
        return type == ScreenEntryType.EPISODE;
    }

    public boolean isFeature() {
        return type == ScreenEntryType.FEATURE;
    }

    public boolean isSpecial() {
        return type == ScreenEntryType.SPECIAL;
    }

    public boolean isTrailer() {
        return type == ScreenEntryType.TRAILER;
    }

    public boolean isDraft() {
        return status == ScreenPublicationStatus.DRAFT;
    }

    public boolean isPublished() {
        return status == ScreenPublicationStatus.PUBLISHED;
    }

    public boolean isArchived() {
        return status == ScreenPublicationStatus.ARCHIVED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScreenEntry that = (ScreenEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScreenEntry{" +
                "id=" + id +
                ", titleId=" + titleId +
                ", slug='" + slug + '\'' +
                ", type=" + type +
                ", number=" + number +
                ", title='" + title + '\'' +
                ", status=" + status +
                ", durationSeconds=" + durationSeconds +
                ", publishedAt=" + publishedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
