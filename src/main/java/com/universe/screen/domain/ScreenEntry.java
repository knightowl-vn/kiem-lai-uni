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
    private ScreenTimelineMode introMode;
    private Long introStartSeconds;
    private Long introEndSeconds;
    private ScreenTimelineMode outroMode;
    private Long outroStartSeconds;
    private Long outroEndSeconds;

    /**
     * Canonical constructor for ScreenEntry including timeline configuration and outro end boundary.
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
            Instant updatedAt,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds
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

        ValidatedEntryTimeline validatedTimeline = validateEntryTimeline(
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                outroEndSeconds
        );
        this.introMode = validatedTimeline.introMode();
        this.introStartSeconds = validatedTimeline.introStartSeconds();
        this.introEndSeconds = validatedTimeline.introEndSeconds();
        this.outroMode = validatedTimeline.outroMode();
        this.outroStartSeconds = validatedTimeline.outroStartSeconds();
        this.outroEndSeconds = validatedTimeline.outroEndSeconds();
    }

    /**
     * Backwards-compatible constructor with outro start but without outro end boundary.
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
            Instant updatedAt,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds
    ) {
        this(
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
                status,
                publishedAt,
                createdAt,
                updatedAt,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                null
        );
    }

    /**
     * Backwards-compatible constructor defaulting timeline configuration to INHERIT.
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
        this(
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
                status,
                publishedAt,
                createdAt,
                updatedAt,
                ScreenTimelineMode.INHERIT,
                null,
                null,
                ScreenTimelineMode.INHERIT,
                null
        );
    }

    /**
     * Factory method to create a new ScreenEntry in DRAFT status with full optional metadata, timeline configuration, and outro end boundary.
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
            Instant createdAt,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds
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
                createdAt,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                outroEndSeconds
        );
    }

    /**
     * Backward-compatible factory method to create a draft ScreenEntry without outro end boundary.
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
            Instant createdAt,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds
    ) {
        return createDraft(
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
                createdAt,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                null
        );
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
     * Updates all editable entry details and timeline configuration atomically including outro end boundary.
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
            Instant authoritativeUpdatedAt,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds
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
        ValidatedEntryTimeline validatedTimeline = validateEntryTimeline(
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                outroEndSeconds
        );

        this.slug = validatedSlug;
        this.type = validatedType;
        this.number = validatedNumber;
        this.title = validatedTitle;
        this.synopsis = normalizedSynopsis;
        this.durationSeconds = validatedDuration;
        this.videoMediaAssetId = videoMediaAssetId;
        this.thumbnailMediaAssetId = thumbnailMediaAssetId;
        this.introMode = validatedTimeline.introMode();
        this.introStartSeconds = validatedTimeline.introStartSeconds();
        this.introEndSeconds = validatedTimeline.introEndSeconds();
        this.outroMode = validatedTimeline.outroMode();
        this.outroStartSeconds = validatedTimeline.outroStartSeconds();
        this.outroEndSeconds = validatedTimeline.outroEndSeconds();
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Backward-compatible updateDetails with outro start but without outro end boundary.
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
            Instant authoritativeUpdatedAt,
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds
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
                authoritativeUpdatedAt,
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                null
        );
    }

    /**
     * Updates all editable entry details atomically while preserving existing timeline configuration.
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
        updateDetails(
                slug,
                type,
                number,
                title,
                synopsis,
                durationSeconds,
                videoMediaAssetId,
                thumbnailMediaAssetId,
                authoritativeUpdatedAt,
                this.introMode,
                this.introStartSeconds,
                this.introEndSeconds,
                this.outroMode,
                this.outroStartSeconds,
                this.outroEndSeconds
        );
    }

    /**
     * Updates episode-specific Intro and Outro timeline configuration atomically including outro end boundary.
     */
    public void updateTimeline(
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds,
            Instant authoritativeUpdatedAt
    ) {
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        ValidatedEntryTimeline validatedTimeline = validateEntryTimeline(
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                outroEndSeconds
        );

        this.introMode = validatedTimeline.introMode();
        this.introStartSeconds = validatedTimeline.introStartSeconds();
        this.introEndSeconds = validatedTimeline.introEndSeconds();
        this.outroMode = validatedTimeline.outroMode();
        this.outroStartSeconds = validatedTimeline.outroStartSeconds();
        this.outroEndSeconds = validatedTimeline.outroEndSeconds();
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Backward-compatible updateTimeline without outro end boundary.
     */
    public void updateTimeline(
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Instant authoritativeUpdatedAt
    ) {
        updateTimeline(
                introMode,
                introStartSeconds,
                introEndSeconds,
                outroMode,
                outroStartSeconds,
                null,
                authoritativeUpdatedAt
        );
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
     * Detaches the currently attached video from this ScreenEntry.
     *
     * <p>Invariants:
     * <ul>
     *   <li>Cannot detach video from a {@link ScreenPublicationStatus#PUBLISHED} entry to prevent leaving a public entry without playable media.</li>
     *   <li>Cannot detach if no video is currently attached.</li>
     *   <li>Preserves publication timestamps, thumbnail, duration, and catalog details.</li>
     * </ul>
     *
     * @param authoritativeUpdatedAt authoritative modification timestamp
     * @throws IllegalStateException if entry is published or if no video is attached
     * @throws IllegalArgumentException if modification timestamp is before createdAt or current updatedAt
     */
    public void detachVideo(Instant authoritativeUpdatedAt) {
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (this.status == ScreenPublicationStatus.PUBLISHED) {
            throw new IllegalStateException("Không thể gỡ bỏ video khỏi Screen Entry đang ở trạng thái PUBLISHED. Vui lòng lưu trữ (Archive) entry hoặc sử dụng tính năng thay thế (Replace) video.");
        }
        if (this.videoMediaAssetId == null) {
            throw new IllegalStateException("Screen Entry hiện tại chưa gắn video nào để gỡ bỏ.");
        }
        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        this.videoMediaAssetId = null;
        this.updatedAt = authoritativeUpdatedAt;
    }

    /**
     * Atomically replaces the currently attached video with a new video Media Asset.
     *
     * <p>Allowed on DRAFT, PUBLISHED, and ARCHIVED entries. When replacing video on a PUBLISHED
     * entry, the published status and authoritative publication timestamp are strictly preserved,
     * ensuring the entry remains continuously valid and publicly playable.
     *
     * @param newVideoMediaAssetId scalar ID of the new video Media Asset
     * @param authoritativeUpdatedAt authoritative modification timestamp
     * @throws NullPointerException if newVideoMediaAssetId is null
     * @throws IllegalStateException if entry has no video currently attached (use initial attach instead)
     * @throws IllegalArgumentException if modification timestamp is before createdAt or current updatedAt
     */
    public void replaceVideo(UUID newVideoMediaAssetId, Instant authoritativeUpdatedAt) {
        Objects.requireNonNull(newVideoMediaAssetId, "New video Media Asset ID cannot be null.");
        Objects.requireNonNull(authoritativeUpdatedAt, "UpdatedAt timestamp cannot be null.");

        if (this.videoMediaAssetId == null) {
            throw new IllegalStateException("Screen Entry chưa gắn video nào để thay thế. Vui lòng sử dụng tính năng gắn video ban đầu.");
        }
        if (authoritativeUpdatedAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before createdAt timestamp.");
        }
        if (authoritativeUpdatedAt.isBefore(this.updatedAt)) {
            throw new IllegalArgumentException("UpdatedAt timestamp cannot be before current updatedAt timestamp.");
        }

        this.videoMediaAssetId = newVideoMediaAssetId;
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

    private record ValidatedEntryTimeline(
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds
    ) {
    }

    private static ValidatedEntryTimeline validateEntryTimeline(
            ScreenTimelineMode introMode,
            Long introStartSeconds,
            Long introEndSeconds,
            ScreenTimelineMode outroMode,
            Long outroStartSeconds,
            Long outroEndSeconds
    ) {
        Objects.requireNonNull(introMode, "Intro ScreenTimelineMode cannot be null.");
        Objects.requireNonNull(outroMode, "Outro ScreenTimelineMode cannot be null.");

        ScreenIntroInterval validatedIntroOverride = null;
        switch (introMode) {
            case INHERIT, DISABLED -> {
                if (introStartSeconds != null || introEndSeconds != null) {
                    throw new IllegalArgumentException(
                            "Intro startSeconds and endSeconds must be null when introMode is " + introMode + "."
                    );
                }
            }
            case OVERRIDE -> {
                if (introStartSeconds == null || introEndSeconds == null) {
                    throw new IllegalArgumentException(
                            "Intro startSeconds and endSeconds are required when introMode is OVERRIDE."
                    );
                }
                validatedIntroOverride = new ScreenIntroInterval(introStartSeconds, introEndSeconds);
            }
        }

        ScreenOutroMarker validatedOutroOverride = null;
        switch (outroMode) {
            case INHERIT, DISABLED -> {
                if (outroStartSeconds != null || outroEndSeconds != null) {
                    throw new IllegalArgumentException(
                            "Outro startSeconds and endSeconds must be null when outroMode is " + outroMode + "."
                    );
                }
            }
            case OVERRIDE -> {
                if (outroStartSeconds == null) {
                    throw new IllegalArgumentException(
                            "Outro startSeconds is required when outroMode is OVERRIDE."
                    );
                }
                validatedOutroOverride = ScreenOutroMarker.ofNullable(outroStartSeconds, outroEndSeconds);
            }
        }

        if (validatedIntroOverride != null
                && validatedOutroOverride != null
                && validatedOutroOverride.startSeconds() < validatedIntroOverride.endSeconds()) {
            throw new IllegalArgumentException(
                    "Entry override outroStartSeconds (" + validatedOutroOverride.startSeconds()
                            + ") cannot be before override introEndSeconds (" + validatedIntroOverride.endSeconds() + ")."
            );
        }

        return new ValidatedEntryTimeline(
                introMode,
                validatedIntroOverride != null ? validatedIntroOverride.startSeconds() : null,
                validatedIntroOverride != null ? validatedIntroOverride.endSeconds() : null,
                outroMode,
                validatedOutroOverride != null ? validatedOutroOverride.startSeconds() : null,
                validatedOutroOverride != null ? validatedOutroOverride.endSeconds() : null
        );
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

    public ScreenTimelineMode getIntroMode() {
        return introMode;
    }

    public Long getIntroStartSeconds() {
        return introStartSeconds;
    }

    public Long getIntroEndSeconds() {
        return introEndSeconds;
    }

    public ScreenTimelineMode getOutroMode() {
        return outroMode;
    }

    public Long getOutroStartSeconds() {
        return outroStartSeconds;
    }

    public Long getOutroEndSeconds() {
        return outroEndSeconds;
    }

    public ScreenIntroInterval getIntroOverride() {
        return introMode == ScreenTimelineMode.OVERRIDE
                ? new ScreenIntroInterval(introStartSeconds, introEndSeconds)
                : null;
    }

    public ScreenOutroMarker getOutroOverride() {
        return outroMode == ScreenTimelineMode.OVERRIDE
                ? ScreenOutroMarker.ofNullable(outroStartSeconds, outroEndSeconds)
                : null;
    }

    public String formattedOutroEnd() {
        return ScreenTimelineTimestampFormat.formatSeconds(outroEndSeconds);
    }

    public EffectiveScreenTimeline resolveEffectiveTimeline(ScreenTitle parentTitle) {
        return EffectiveScreenTimeline.resolve(parentTitle, this);
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
