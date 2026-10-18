package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenEntry Pure Domain Tests")
class ScreenEntryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T14:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-01T16:00:00Z");

    @Test
    @DisplayName("Creates draft entry with minimal required attributes")
    void shouldCreateMinimalDraftEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id,
                titleId,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1: Xuất Sơn",
                T0
        );

        assertThat(entry.getId()).isEqualTo(id);
        assertThat(entry.getTitleId()).isEqualTo(titleId);
        assertThat(entry.getSlug()).isEqualTo("tap-1");
        assertThat(entry.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(entry.getNumber()).isEqualTo(1);
        assertThat(entry.getTitle()).isEqualTo("Tập 1: Xuất Sơn");
        assertThat(entry.getSynopsis()).isNull();
        assertThat(entry.getVideoMediaAssetId()).isNull();
        assertThat(entry.getThumbnailMediaAssetId()).isNull();
        assertThat(entry.getDurationSeconds()).isNull();
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getCreatedAt()).isEqualTo(T0);
        assertThat(entry.getUpdatedAt()).isEqualTo(T0);
        assertThat(entry.isEpisode()).isTrue();
        assertThat(entry.isDraft()).isTrue();
    }

    @Test
    @DisplayName("Creates draft entry with full optional metadata")
    void shouldCreateDraftEntryWithFullMetadata() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id,
                titleId,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1: Khởi Đầu",
                "Trần Bình An bắt đầu bước vào Ly Châu Động Thiên.",
                videoId,
                thumbId,
                1260L,
                T0
        );

        assertThat(entry.getId()).isEqualTo(id);
        assertThat(entry.getTitleId()).isEqualTo(titleId);
        assertThat(entry.getSlug()).isEqualTo("tap-1");
        assertThat(entry.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(entry.getNumber()).isEqualTo(1);
        assertThat(entry.getTitle()).isEqualTo("Tập 1: Khởi Đầu");
        assertThat(entry.getSynopsis()).isEqualTo("Trần Bình An bắt đầu bước vào Ly Châu Động Thiên.");
        assertThat(entry.getVideoMediaAssetId()).isEqualTo(videoId);
        assertThat(entry.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(entry.getDurationSeconds()).isEqualTo(1260L);
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getCreatedAt()).isEqualTo(T0);
        assertThat(entry.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Enforces required fields and rejects nulls or blanks")
    void shouldEnforceRequiredFields() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Null id
        assertThatThrownBy(() -> ScreenEntry.createDraft(null, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Entry ID cannot be null");

        // Null titleId
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, null, "slug", ScreenEntryType.EPISODE, 1, "Title", T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Title ID cannot be null");

        // Null slug
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, null, ScreenEntryType.EPISODE, 1, "Title", T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Slug cannot be null");

        // Blank slug
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "   ", ScreenEntryType.EPISODE, 1, "Title", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Slug cannot be blank");

        // Invalid slug
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "slug--invalid", ScreenEntryType.EPISODE, 1, "Title", T0))
                .isInstanceOf(IllegalArgumentException.class);

        // Null type
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "slug", null, 1, "Title", T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenEntryType cannot be null");

        // Null title
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "slug", ScreenEntryType.EPISODE, 1, null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Title cannot be null");

        // Blank title
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "slug", ScreenEntryType.EPISODE, 1, "   ", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Title cannot be blank");

        // Null createdAt
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp cannot be null");

        // Canonical constructor with null status
        assertThatThrownBy(() -> new ScreenEntry(id, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", null, null, null, null, null, null, T0, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenPublicationStatus cannot be null");

        // Canonical constructor with updatedAt before createdAt
        assertThatThrownBy(() -> new ScreenEntry(id, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", null, null, null, null, ScreenPublicationStatus.DRAFT, null, T1, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("EPISODE numbering semantics: requires positive integer and rejects null, zero, negative")
    void shouldEnforceEpisodeNumberingSemantics() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Positive integer accepted
        ScreenEntry ep1 = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);
        assertThat(ep1.getNumber()).isEqualTo(1);

        ScreenEntry ep100 = ScreenEntry.createDraft(UUID.randomUUID(), titleId, "tap-100", ScreenEntryType.EPISODE, 100, "Tập 100", T0);
        assertThat(ep100.getNumber()).isEqualTo(100);

        // Null number rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "tap-null", ScreenEntryType.EPISODE, null, "Tập Null", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EPISODE entry requires a non-null entry number");

        // Zero rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "tap-0", ScreenEntryType.EPISODE, 0, "Tập 0", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EPISODE entry number must be positive");

        // Negative rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "tap-neg", ScreenEntryType.EPISODE, -1, "Tập Âm", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EPISODE entry number must be positive");
    }

    @Test
    @DisplayName("FEATURE numbering semantics: number must be null; rejects numbered FEATURE (critical movie invariant)")
    void shouldEnforceFeatureNumberingSemantics() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Null number accepted
        ScreenEntry feature = ScreenEntry.createDraft(id, titleId, "movie-play", ScreenEntryType.FEATURE, null, "Bản Chiếu Hoàn Chỉnh", T0);
        assertThat(feature.getNumber()).isNull();
        assertThat(feature.isFeature()).isTrue();

        // Numbered FEATURE rejected (proves movie is never modeled as fake Episode 1)
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "movie-play", ScreenEntryType.FEATURE, 1, "Movie 1", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEATURE entry must not have an entry number");

        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "movie-play", ScreenEntryType.FEATURE, 0, "Movie 0", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEATURE entry must not have an entry number");
    }

    @Test
    @DisplayName("SPECIAL numbering semantics: allows null or positive integer; rejects zero or negative")
    void shouldEnforceSpecialNumberingSemantics() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Null number accepted
        ScreenEntry specialNull = ScreenEntry.createDraft(id, titleId, "ova-special", ScreenEntryType.SPECIAL, null, "OVA Đặc Biệt", T0);
        assertThat(specialNull.getNumber()).isNull();
        assertThat(specialNull.isSpecial()).isTrue();

        // Positive number accepted
        ScreenEntry specialNumbered = ScreenEntry.createDraft(UUID.randomUUID(), titleId, "ova-1", ScreenEntryType.SPECIAL, 1, "Ngoại Truyện 1", T0);
        assertThat(specialNumbered.getNumber()).isEqualTo(1);

        // Zero rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "ova-0", ScreenEntryType.SPECIAL, 0, "OVA 0", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPECIAL entry number must be positive when provided");

        // Negative rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "ova-neg", ScreenEntryType.SPECIAL, -2, "OVA Âm", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPECIAL entry number must be positive when provided");
    }

    @Test
    @DisplayName("TRAILER numbering semantics: allows null or positive integer; rejects zero or negative")
    void shouldEnforceTrailerNumberingSemantics() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Null number accepted
        ScreenEntry trailerNull = ScreenEntry.createDraft(id, titleId, "trailer-official", ScreenEntryType.TRAILER, null, "Trailer Chính Thức", T0);
        assertThat(trailerNull.getNumber()).isNull();
        assertThat(trailerNull.isTrailer()).isTrue();

        // Positive number accepted
        ScreenEntry trailerNumbered = ScreenEntry.createDraft(UUID.randomUUID(), titleId, "trailer-1", ScreenEntryType.TRAILER, 1, "Trailer 1", T0);
        assertThat(trailerNumbered.getNumber()).isEqualTo(1);

        // Zero rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "trailer-0", ScreenEntryType.TRAILER, 0, "Trailer 0", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRAILER entry number must be positive when provided");

        // Negative rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "trailer-neg", ScreenEntryType.TRAILER, -1, "Trailer Âm", T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRAILER entry number must be positive when provided");
    }

    @Test
    @DisplayName("Normalizes optional synopsis and trims required title outer whitespace")
    void shouldHandleTextNormalization() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Title whitespace trimmed, diacritics and inner spaces preserved
        ScreenEntry entry = ScreenEntry.createDraft(
                id,
                titleId,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "  Tập 1: Kiếm Khí Ngút Trời  ",
                "   Tóm tắt tập 1 với tiếng Việt có dấu.   ",
                null,
                null,
                null,
                T0
        );
        assertThat(entry.getTitle()).isEqualTo("Tập 1: Kiếm Khí Ngút Trời");
        assertThat(entry.getSynopsis()).isEqualTo("Tóm tắt tập 1 với tiếng Việt có dấu.");

        // Blank synopsis normalized to null
        ScreenEntry blankSynopsisEntry = ScreenEntry.createDraft(
                UUID.randomUUID(),
                titleId,
                "tap-2",
                ScreenEntryType.EPISODE,
                2,
                "Tập 2",
                "   \t  \n  ",
                null,
                null,
                null,
                T0
        );
        assertThat(blankSynopsisEntry.getSynopsis()).isNull();
    }

    @Test
    @DisplayName("Duration semantics: nullable, accepts positive value, rejects zero and negative")
    void shouldEnforceDurationSemantics() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // Null duration accepted
        ScreenEntry entryNull = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", null, null, null, null, T0);
        assertThat(entryNull.getDurationSeconds()).isNull();

        // Positive duration accepted
        ScreenEntry entryPos = ScreenEntry.createDraft(UUID.randomUUID(), titleId, "tap-2", ScreenEntryType.EPISODE, 2, "Tập 2", null, null, null, 1440L, T0);
        assertThat(entryPos.getDurationSeconds()).isEqualTo(1440L);

        // Zero duration rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "tap-3", ScreenEntryType.EPISODE, 3, "Tập 3", null, null, null, 0L, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration must be positive");

        // Negative duration rejected
        assertThatThrownBy(() -> ScreenEntry.createDraft(id, titleId, "tap-4", ScreenEntryType.EPISODE, 4, "Tập 4", null, null, null, -10L, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration must be positive");
    }

    @Test
    @DisplayName("Accepts scalar MediaAsset UUID references and updates them")
    void shouldHandleScalarMediaAssetIds() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId1 = UUID.randomUUID();
        UUID thumbId1 = UUID.randomUUID();
        UUID videoId2 = UUID.randomUUID();
        UUID thumbId2 = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                null, videoId1, thumbId1, 1200L, T0
        );

        assertThat(entry.getVideoMediaAssetId()).isEqualTo(videoId1);
        assertThat(entry.getThumbnailMediaAssetId()).isEqualTo(thumbId1);

        // Update media
        entry.updateMedia(videoId2, thumbId2, T1);
        assertThat(entry.getVideoMediaAssetId()).isEqualTo(videoId2);
        assertThat(entry.getThumbnailMediaAssetId()).isEqualTo(thumbId2);
        assertThat(entry.getUpdatedAt()).isEqualTo(T1);

        // Clear media
        entry.updateMedia(null, null, T2);
        assertThat(entry.getVideoMediaAssetId()).isNull();
        assertThat(entry.getThumbnailMediaAssetId()).isNull();
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("Updates duration via updateDuration")
    void shouldUpdateDuration() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        entry.updateDuration(1500L, T1);
        assertThat(entry.getDurationSeconds()).isEqualTo(1500L);
        assertThat(entry.getUpdatedAt()).isEqualTo(T1);

        entry.updateDuration(null, T2);
        assertThat(entry.getDurationSeconds()).isNull();
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);

        // Reject non-positive duration in updateDuration
        assertThatThrownBy(() -> entry.updateDuration(0L, T3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration must be positive");
        assertThatThrownBy(() -> entry.updateDuration(-5L, T3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration must be positive");
    }

    @Test
    @DisplayName("Enforces draft publication invariants upon construction")
    void shouldEnforceDraftPublicationInvariants() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        // DRAFT must not have publishedAt
        assertThatThrownBy(() -> new ScreenEntry(
                id, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", null, null, null, null,
                ScreenPublicationStatus.DRAFT, T0, T0, T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Draft ScreenEntry cannot have a publishedAt timestamp");

        // PUBLISHED must have non-null publishedAt
        assertThatThrownBy(() -> new ScreenEntry(
                id, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", null, null, null, null,
                ScreenPublicationStatus.PUBLISHED, null, T0, T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Published ScreenEntry must have a publishedAt timestamp");

        // publishedAt cannot be before createdAt
        assertThatThrownBy(() -> new ScreenEntry(
                id, titleId, "slug", ScreenEntryType.EPISODE, 1, "Title", null, null, null, null,
                ScreenPublicationStatus.PUBLISHED, T0, T1, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PublishedAt timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("Lifecycle: First publish sets publishedAt and changes status to PUBLISHED")
    void shouldPublishDraftEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.isPublished()).isFalse();

        entry.publish(T1);

        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(entry.getPublishedAt()).isEqualTo(T1);
        assertThat(entry.getUpdatedAt()).isEqualTo(T1);
        assertThat(entry.isPublished()).isTrue();
    }

    @Test
    @DisplayName("Lifecycle: Re-publishing an already published entry is rejected")
    void shouldRejectPublishWhenAlreadyPublished() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        entry.publish(T1);

        assertThatThrownBy(() -> entry.publish(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScreenEntry is already published");
    }

    @Test
    @DisplayName("Lifecycle: Publishing an archived entry is rejected")
    void shouldRejectPublishWhenArchived() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        entry.archive(T1);

        assertThatThrownBy(() -> entry.publish(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Archived ScreenEntry cannot be published");
    }

    @Test
    @DisplayName("Lifecycle: Subsequent mutation preserves publishedAt timestamp")
    void shouldPreservePublishedAtUponMutation() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        entry.publish(T1);
        assertThat(entry.getPublishedAt()).isEqualTo(T1);

        // Later update details
        entry.update(
                "tap-1-remaster",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1 (Bản Làm Lại)",
                "Tóm tắt mới",
                1300L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                T2
        );

        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(entry.getPublishedAt()).isEqualTo(T1); // Preserved!
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);
        assertThat(entry.getTitle()).isEqualTo("Tập 1 (Bản Làm Lại)");
    }

    @Test
    @DisplayName("Lifecycle: Archiving a published entry preserves publishedAt timestamp")
    void shouldPreservePublishedAtUponArchive() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        entry.publish(T1);
        assertThat(entry.getPublishedAt()).isEqualTo(T1);

        entry.archive(T2);

        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(entry.getPublishedAt()).isEqualTo(T1); // Preserved!
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);
        assertThat(entry.isArchived()).isTrue();
    }

    @Test
    @DisplayName("Lifecycle: Archiving a draft entry sets status to ARCHIVED with null publishedAt")
    void shouldArchiveDraftEntryDirectly() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-draft", ScreenEntryType.SPECIAL, null, "Dự thảo", T0);

        entry.archive(T1);

        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Lifecycle: Re-archiving an already archived entry is rejected")
    void shouldRejectArchiveWhenAlreadyArchived() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        entry.archive(T1);

        assertThatThrownBy(() -> entry.archive(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScreenEntry is already archived");
    }

    @Test
    @DisplayName("Rejects publication or archive timestamps before createdAt")
    void shouldRejectTimestampsBeforeCreatedAt() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T1);

        assertThatThrownBy(() -> entry.publish(T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Publication timestamp cannot be before createdAt timestamp");

        assertThatThrownBy(() -> entry.archive(T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("Rejects timestamp rollback across mutations")
    void shouldRejectTimestampRollbackAcrossMutations() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0);

        // Update to T2
        entry.updateDuration(1200L, T2);
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);

        // Update details with T1 (before current updatedAt T2)
        assertThatThrownBy(() -> entry.updateDetails(
                "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1 Mới", null, 1200L, null, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before current updatedAt timestamp");

        // Update media with T1
        assertThatThrownBy(() -> entry.updateMedia(UUID.randomUUID(), null, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before current updatedAt timestamp");

        // Update duration with T1
        assertThatThrownBy(() -> entry.updateDuration(1300L, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before current updatedAt timestamp");

        // Publish with T1
        assertThatThrownBy(() -> entry.publish(T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Publication timestamp cannot be before current updatedAt timestamp");

        // Publish at T2 (valid: T2 >= T2)
        entry.publish(T2);

        // Archive at T1 rejected
        assertThatThrownBy(() -> entry.archive(T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive timestamp cannot be before current updatedAt timestamp");
    }

    @Test
    @DisplayName("Failed updateDetails is atomic: CASE A — preserves state when title is blank")
    void shouldPreserveStateWhenUpdateDetailsFailsWithBlankTitle() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "orig-slug", ScreenEntryType.EPISODE, 1, "Original Title",
                "Original Synopsis", videoId, thumbId, 1200L, T0
        );

        // CASE A: valid new slug/number/duration/media, blank title
        assertThatThrownBy(() -> entry.updateDetails(
                "new-valid-slug", ScreenEntryType.EPISODE, 2, "   ",
                "New Synopsis", 1300L, UUID.randomUUID(), UUID.randomUUID(), T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Title cannot be blank");

        // Verify ALL original fields and updatedAt remain unchanged
        assertThat(entry.getId()).isEqualTo(id);
        assertThat(entry.getTitleId()).isEqualTo(titleId);
        assertThat(entry.getSlug()).isEqualTo("orig-slug");
        assertThat(entry.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(entry.getNumber()).isEqualTo(1);
        assertThat(entry.getTitle()).isEqualTo("Original Title");
        assertThat(entry.getSynopsis()).isEqualTo("Original Synopsis");
        assertThat(entry.getVideoMediaAssetId()).isEqualTo(videoId);
        assertThat(entry.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(entry.getDurationSeconds()).isEqualTo(1200L);
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getCreatedAt()).isEqualTo(T0);
        assertThat(entry.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Failed updateDetails is atomic: CASE B — preserves state when number is invalid for type")
    void shouldPreserveStateWhenUpdateDetailsFailsWithInvalidNumber() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "orig-slug", ScreenEntryType.EPISODE, 1, "Original Title", T0
        );

        // CASE B: Change type to FEATURE but provide non-null number
        assertThatThrownBy(() -> entry.updateDetails(
                "new-valid-slug", ScreenEntryType.FEATURE, 1, "New Valid Title",
                null, 1300L, null, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEATURE entry must not have an entry number");

        // Verify state is completely preserved
        assertThat(entry.getSlug()).isEqualTo("orig-slug");
        assertThat(entry.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(entry.getNumber()).isEqualTo(1);
        assertThat(entry.getTitle()).isEqualTo("Original Title");
        assertThat(entry.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Failed updateDetails is atomic: CASE C — preserves state when duration is invalid")
    void shouldPreserveStateWhenUpdateDetailsFailsWithInvalidDuration() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "orig-slug", ScreenEntryType.EPISODE, 1, "Original Title", T0
        );

        // CASE C: Candidate duration <= 0
        assertThatThrownBy(() -> entry.updateDetails(
                "new-valid-slug", ScreenEntryType.EPISODE, 2, "New Valid Title",
                null, 0L, null, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration must be positive");

        // Verify state is completely preserved
        assertThat(entry.getSlug()).isEqualTo("orig-slug");
        assertThat(entry.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(entry.getNumber()).isEqualTo(1);
        assertThat(entry.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Failed updateMetadata is atomic: preserves state when slug is invalid")
    void shouldPreserveStateWhenUpdateMetadataFails() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "orig-slug", ScreenEntryType.EPISODE, 1, "Original Title", T0
        );

        assertThatThrownBy(() -> entry.updateMetadata(
                "invalid--slug", ScreenEntryType.EPISODE, 2, "New Title", "New Synopsis", T1
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(entry.getSlug()).isEqualTo("orig-slug");
        assertThat(entry.getNumber()).isEqualTo(1);
        assertThat(entry.getTitle()).isEqualTo("Original Title");
        assertThat(entry.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("detachVideo successfully removes video from DRAFT entry and preserves all other fields")
    void shouldDetachVideoFromDraftEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", videoId, thumbId, 1200L, T0
        );

        entry.detachVideo(T1);

        assertThat(entry.getVideoMediaAssetId()).isNull();
        assertThat(entry.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(entry.getDurationSeconds()).isEqualTo(1200L);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(entry.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("detachVideo successfully removes video from ARCHIVED entry")
    void shouldDetachVideoFromArchivedEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", videoId, null, 1200L, T0
        );
        entry.archive(T1);

        entry.detachVideo(T2);

        assertThat(entry.getVideoMediaAssetId()).isNull();
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("detachVideo rejects detaching video from PUBLISHED entry")
    void shouldRejectDetachingVideoFromPublishedEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", videoId, null, 1200L, T0
        );
        entry.publish(T1);

        assertThatThrownBy(() -> entry.detachVideo(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    @DisplayName("detachVideo rejects detaching when no video is attached")
    void shouldRejectDetachingWhenNoVideoAttached() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.detachVideo(T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chưa gắn video");
    }

    @Test
    @DisplayName("detachVideo validates authoritativeUpdatedAt timestamp")
    void shouldValidateDetachTimestamp() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", videoId, null, 1200L, T1
        );

        assertThatThrownBy(() -> entry.detachVideo(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be null");

        assertThatThrownBy(() -> entry.detachVideo(T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before");
    }

    @Test
    @DisplayName("replaceVideo successfully swaps video on DRAFT entry")
    void shouldReplaceVideoOnDraftEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID oldVideoId = UUID.randomUUID();
        UUID newVideoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", oldVideoId, thumbId, 1200L, T0
        );

        entry.replaceVideo(newVideoId, T1);

        assertThat(entry.getVideoMediaAssetId()).isEqualTo(newVideoId);
        assertThat(entry.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(entry.getDurationSeconds()).isEqualTo(1200L);
        assertThat(entry.getPublishedAt()).isNull();
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(entry.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("replaceVideo successfully swaps video on PUBLISHED entry preserving status and publishedAt")
    void shouldReplaceVideoOnPublishedEntryPreservingPublicationState() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID oldVideoId = UUID.randomUUID();
        UUID newVideoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", oldVideoId, null, 1200L, T0
        );
        entry.publish(T1);

        entry.replaceVideo(newVideoId, T2);

        assertThat(entry.getVideoMediaAssetId()).isEqualTo(newVideoId);
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(entry.getPublishedAt()).isEqualTo(T1);
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("replaceVideo successfully swaps video on ARCHIVED entry")
    void shouldReplaceVideoOnArchivedEntry() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID oldVideoId = UUID.randomUUID();
        UUID newVideoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", oldVideoId, null, 1200L, T0
        );
        entry.archive(T1);

        entry.replaceVideo(newVideoId, T2);

        assertThat(entry.getVideoMediaAssetId()).isEqualTo(newVideoId);
        assertThat(entry.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(entry.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("replaceVideo rejects null newVideoMediaAssetId")
    void shouldRejectNullNewVideoIdOnReplacement() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID oldVideoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", oldVideoId, null, 1200L, T0
        );

        assertThatThrownBy(() -> entry.replaceVideo(null, T1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("New video Media Asset ID cannot be null");
    }

    @Test
    @DisplayName("replaceVideo rejects replacing when entry has no attached video")
    void shouldRejectReplacementWhenNoVideoAttached() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID newVideoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", T0
        );

        assertThatThrownBy(() -> entry.replaceVideo(newVideoId, T1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chưa gắn video");
    }

    @Test
    @DisplayName("replaceVideo validates authoritativeUpdatedAt timestamp")
    void shouldValidateReplaceTimestamp() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID oldVideoId = UUID.randomUUID();
        UUID newVideoId = UUID.randomUUID();

        ScreenEntry entry = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả", oldVideoId, null, 1200L, T1
        );

        assertThatThrownBy(() -> entry.replaceVideo(newVideoId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be null");

        assertThatThrownBy(() -> entry.replaceVideo(newVideoId, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before");
    }

    @Test
    @DisplayName("Implements identity-based equals and hashCode")
    void shouldImplementIdentityBasedEquality() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();

        ScreenEntry e1 = ScreenEntry.createDraft(id, titleId, "slug-1", ScreenEntryType.EPISODE, 1, "Title 1", T0);
        ScreenEntry e2 = ScreenEntry.createDraft(id, UUID.randomUUID(), "slug-2", ScreenEntryType.FEATURE, null, "Title 2", T0);
        ScreenEntry e3 = ScreenEntry.createDraft(UUID.randomUUID(), titleId, "slug-1", ScreenEntryType.EPISODE, 1, "Title 1", T0);

        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        assertThat(e1).isNotEqualTo(e3);
    }
}
