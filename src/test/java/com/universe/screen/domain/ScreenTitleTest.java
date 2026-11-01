package com.universe.screen.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenTitle Pure Domain Tests")
class ScreenTitleTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-01T14:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-01T16:00:00Z");

    @ParameterizedTest(name = "Classification {0} + {1} is fully valid")
    @MethodSource("provideAllFourClassificationCombinations")
    @DisplayName("Proves all four supported classification combinations")
    void shouldSupportAllFourClassificationCombinations(
            ScreenStructure structure,
            ScreenPresentation presentation
    ) {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id,
                "installment-slug",
                "Kiếm Lai Installment",
                structure,
                presentation,
                T0
        );

        assertThat(title.getStructure()).isEqualTo(structure);
        assertThat(title.getPresentation()).isEqualTo(presentation);
        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(title.getPublishedAt()).isNull();
    }

    private static Stream<Arguments> provideAllFourClassificationCombinations() {
        return Stream.of(
                Arguments.of(ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D),
                Arguments.of(ScreenStructure.SERIES, ScreenPresentation.LIVE_ACTION),
                Arguments.of(ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D),
                Arguments.of(ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION)
        );
    }

    @Test
    @DisplayName("Creates draft with full optional metadata")
    void shouldCreateDraftWithFullMetadata() {
        UUID id = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();

        ScreenTitle title = ScreenTitle.createDraft(
                id,
                "kiem-lai-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Cậu bé Trần Bình An bắt đầu hành trình tu tiên.",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                T0
        );

        assertThat(title.getId()).isEqualTo(id);
        assertThat(title.getSlug()).isEqualTo("kiem-lai-1");
        assertThat(title.getTitle()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(title.getOriginalTitle()).isEqualTo("The Great Ruler");
        assertThat(title.getSynopsis()).isEqualTo("Cậu bé Trần Bình An bắt đầu hành trình tu tiên.");
        assertThat(title.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(title.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(title.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(title.getBackdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(title.getPublishedAt()).isNull();
        assertThat(title.getCreatedAt()).isEqualTo(T0);
        assertThat(title.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Enforces required fields and rejects nulls or blanks")
    void shouldEnforceRequiredFields() {
        UUID id = UUID.randomUUID();

        // Null id
        assertThatThrownBy(() -> ScreenTitle.createDraft(null, "slug", "Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Title ID cannot be null");

        // Null slug
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, null, "Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Slug cannot be null");

        // Blank slug
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "   ", "Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Slug cannot be blank");

        // Invalid slug
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "slug--invalid", "Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(IllegalArgumentException.class);

        // Null title
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "slug", null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Title cannot be null");

        // Blank title
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "slug", "   ", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Title cannot be blank");

        // Null structure
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "slug", "Title", null, ScreenPresentation.ANIMATION_3D, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenStructure cannot be null");

        // Null presentation
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "slug", "Title", ScreenStructure.SERIES, null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenPresentation cannot be null");

        // Null createdAt
        assertThatThrownBy(() -> ScreenTitle.createDraft(id, "slug", "Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CreatedAt timestamp cannot be null");

        // Canonical constructor with null status
        assertThatThrownBy(() -> new ScreenTitle(id, "slug", "Title", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, null, null, T0, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenPublicationStatus cannot be null");

        // Canonical constructor with updatedAt before createdAt
        assertThatThrownBy(() -> new ScreenTitle(id, "slug", "Title", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, ScreenPublicationStatus.DRAFT, null, T1, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("Normalizes optional text fields and handles null and blank semantics")
    void shouldHandleOptionalTextSemantics() {
        UUID id = UUID.randomUUID();

        // Null optional fields remain null
        ScreenTitle titleWithNulls = ScreenTitle.createDraft(
                id, "slug-1", "Title 1", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0
        );
        assertThat(titleWithNulls.getOriginalTitle()).isNull();
        assertThat(titleWithNulls.getSynopsis()).isNull();

        // Blank optional fields are normalized to null
        ScreenTitle titleWithBlanks = ScreenTitle.createDraft(
                id, "slug-2", "Title 2", "   ", "\t  \n",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0
        );
        assertThat(titleWithBlanks.getOriginalTitle()).isNull();
        assertThat(titleWithBlanks.getSynopsis()).isNull();

        // Non-blank optional fields are trimmed preserving casing and accents
        ScreenTitle titleWithText = ScreenTitle.createDraft(
                id, "slug-3", "Title 3", "  Kiếm Lai (Tiếng Hán)  ", "  Tóm tắt cốt truyện.  ",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0
        );
        assertThat(titleWithText.getOriginalTitle()).isEqualTo("Kiếm Lai (Tiếng Hán)");
        assertThat(titleWithText.getSynopsis()).isEqualTo("Tóm tắt cốt truyện.");
    }

    @Test
    @DisplayName("Normalizes required title outer whitespace while preserving inner content, casing, and diacritics")
    void shouldNormalizeRequiredTitleWhitespaceWithoutModifyingContent() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "kiem-lai-1", "  Kiếm Lai Phần 1: Khởi Đầu  ",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        assertThat(title.getTitle()).isEqualTo("Kiếm Lai Phần 1: Khởi Đầu");
    }

    @Test
    @DisplayName("Accepts scalar poster and backdrop MediaAsset IDs and updates them")
    void shouldHandleScalarMediaAssetIds() {
        UUID id = UUID.randomUUID();
        UUID posterId1 = UUID.randomUUID();
        UUID backdropId1 = UUID.randomUUID();
        UUID posterId2 = UUID.randomUUID();
        UUID backdropId2 = UUID.randomUUID();

        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-media", "Media Title", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterId1, backdropId1, T0
        );

        assertThat(title.getPosterMediaAssetId()).isEqualTo(posterId1);
        assertThat(title.getBackdropMediaAssetId()).isEqualTo(backdropId1);

        // Update media
        title.updateMedia(posterId2, backdropId2, T1);
        assertThat(title.getPosterMediaAssetId()).isEqualTo(posterId2);
        assertThat(title.getBackdropMediaAssetId()).isEqualTo(backdropId2);
        assertThat(title.getUpdatedAt()).isEqualTo(T1);

        // Clear media references
        title.updateMedia(null, null, T2);
        assertThat(title.getPosterMediaAssetId()).isNull();
        assertThat(title.getBackdropMediaAssetId()).isNull();
        assertThat(title.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("Enforces draft publication invariants upon construction")
    void shouldEnforceDraftPublicationInvariants() {
        UUID id = UUID.randomUUID();

        // DRAFT must not have publishedAt
        assertThatThrownBy(() -> new ScreenTitle(
                id, "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                ScreenPublicationStatus.DRAFT, T0, T0, T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Draft ScreenTitle cannot have a publishedAt timestamp");

        // PUBLISHED must have non-null publishedAt
        assertThatThrownBy(() -> new ScreenTitle(
                id, "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                ScreenPublicationStatus.PUBLISHED, null, T0, T0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Published ScreenTitle must have a publishedAt timestamp");

        // publishedAt cannot be before createdAt
        assertThatThrownBy(() -> new ScreenTitle(
                id, "slug", "Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                ScreenPublicationStatus.PUBLISHED, T0, T1, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PublishedAt timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("Lifecycle: First publish sets publishedAt and changes status to PUBLISHED")
    void shouldPublishDraftTitle() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-lifecycle", "Lifecycle Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(title.getPublishedAt()).isNull();

        title.publish(T1);

        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(title.getPublishedAt()).isEqualTo(T1);
        assertThat(title.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Lifecycle: Re-publishing an already published title is rejected")
    void shouldRejectPublishWhenAlreadyPublished() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-lifecycle", "Lifecycle Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        title.publish(T1);

        assertThatThrownBy(() -> title.publish(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScreenTitle is already published");
    }

    @Test
    @DisplayName("Lifecycle: Publishing an archived title is rejected")
    void shouldRejectPublishWhenArchived() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-lifecycle", "Lifecycle Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        title.archive(T1);

        assertThatThrownBy(() -> title.publish(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Archived ScreenTitle cannot be published");
    }

    @Test
    @DisplayName("Lifecycle: Subsequent mutation preserves publishedAt timestamp")
    void shouldPreservePublishedAtUponMutation() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-orig", "Original Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        title.publish(T1);
        assertThat(title.getPublishedAt()).isEqualTo(T1);

        // Later update details
        title.update(
                "slug-updated",
                "Updated Title",
                "New Original Title",
                "New Synopsis",
                ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                T2
        );

        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(title.getPublishedAt()).isEqualTo(T1); // Unchanged!
        assertThat(title.getUpdatedAt()).isEqualTo(T2);
        assertThat(title.getTitle()).isEqualTo("Updated Title");
        assertThat(title.getStructure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(title.getPresentation()).isEqualTo(ScreenPresentation.LIVE_ACTION);
    }

    @Test
    @DisplayName("Lifecycle: Archiving a published title preserves publishedAt timestamp")
    void shouldPreservePublishedAtUponArchive() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-archive", "Archive Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        title.publish(T1);
        assertThat(title.getPublishedAt()).isEqualTo(T1);

        title.archive(T2);

        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(title.getPublishedAt()).isEqualTo(T1); // Unchanged!
        assertThat(title.getUpdatedAt()).isEqualTo(T2);
    }

    @Test
    @DisplayName("Lifecycle: Archiving a draft title sets status to ARCHIVED with null publishedAt")
    void shouldArchiveDraftTitleDirectly() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-archive-draft", "Draft To Archive",
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, T0
        );

        title.archive(T1);

        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(title.getPublishedAt()).isNull();
        assertThat(title.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("Lifecycle: Re-archiving an already archived title is rejected")
    void shouldRejectArchiveWhenAlreadyArchived() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-rearchive", "Re-archive Title",
                ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, T0
        );

        title.archive(T1);

        assertThatThrownBy(() -> title.archive(T2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ScreenTitle is already archived");
    }

    @Test
    @DisplayName("Rejects publication or archive timestamps before createdAt")
    void shouldRejectTimestampsBeforeCreatedAt() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-ts", "Timestamp Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T1
        );

        assertThatThrownBy(() -> title.publish(T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Publication timestamp cannot be before createdAt timestamp");

        assertThatThrownBy(() -> title.archive(T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive timestamp cannot be before createdAt timestamp");
    }

    @Test
    @DisplayName("Rejects timestamp rollback across updateDetails, updateMedia, publish, and archive")
    void shouldRejectTimestampRollbackAcrossMutations() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "slug-rollback", "Rollback Test Title",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0
        );

        // Update to T2
        title.updateDetails(
                "slug-rollback", "Rollback Test Title Updated", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T2
        );
        assertThat(title.getUpdatedAt()).isEqualTo(T2);

        // Case: createdAt = T0, updatedAt = T2, updateDetails(..., T1) rejected
        assertThatThrownBy(() -> title.updateDetails(
                "slug-rollback", "Attempt Earlier Update", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before current updatedAt timestamp");

        // Case: createdAt = T0, updatedAt = T2, updateMedia(..., T1) rejected
        assertThatThrownBy(() -> title.updateMedia(UUID.randomUUID(), null, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before current updatedAt timestamp");

        // Case: createdAt = T0, updatedAt = T2, publish(T1) rejected
        assertThatThrownBy(() -> title.publish(T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Publication timestamp cannot be before current updatedAt timestamp");

        // Now publish at T2 (valid: T2 >= T2)
        title.publish(T2);
        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(title.getPublishedAt()).isEqualTo(T2);

        // Case: publishedAt = T2, updatedAt = T2, archive(T1) rejected
        assertThatThrownBy(() -> title.archive(T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Archive timestamp cannot be before current updatedAt timestamp");
    }

    @Test
    @DisplayName("Rejects published construction where updatedAt is before publishedAt")
    void shouldRejectConstructionWhereUpdatedAtBeforePublishedAt() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ScreenTitle(
                id, "slug-time", "Time Title", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null,
                ScreenPublicationStatus.PUBLISHED, T2, T0, T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UpdatedAt timestamp cannot be before publishedAt timestamp");
    }

    @Test
    @DisplayName("Failed updateDetails is atomic: CASE A — preserves state when title is blank")
    void shouldPreserveStateWhenUpdateDetailsFailsWithBlankTitle() {
        UUID id = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "orig-slug", "Original Title", "Original CN", "Original Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId, T0
        );

        // CASE A: candidate slug is valid and different, candidate title is blank
        assertThatThrownBy(() -> title.updateDetails(
                "new-valid-slug", "   ", "New CN", "New Synopsis",
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION,
                UUID.randomUUID(), UUID.randomUUID(), T1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Title cannot be blank");

        // Verify ALL original fields and updatedAt remain unchanged
        assertThat(title.getId()).isEqualTo(id);
        assertThat(title.getSlug()).isEqualTo("orig-slug");
        assertThat(title.getTitle()).isEqualTo("Original Title");
        assertThat(title.getOriginalTitle()).isEqualTo("Original CN");
        assertThat(title.getSynopsis()).isEqualTo("Original Synopsis");
        assertThat(title.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(title.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(title.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(title.getBackdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(title.getPublishedAt()).isNull();
        assertThat(title.getCreatedAt()).isEqualTo(T0);
        assertThat(title.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Failed updateDetails is atomic: CASE B — preserves state when structure is null")
    void shouldPreserveStateWhenUpdateDetailsFailsWithNullStructure() {
        UUID id = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "orig-slug", "Original Title", "Original CN", "Original Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId, T0
        );

        // CASE B: candidate slug/title/text are valid and different, structure = null
        assertThatThrownBy(() -> title.updateDetails(
                "new-valid-slug", "New Valid Title", "New CN", "New Synopsis",
                null, ScreenPresentation.LIVE_ACTION,
                UUID.randomUUID(), UUID.randomUUID(), T1
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenStructure cannot be null");

        // Verify ALL original fields and updatedAt remain unchanged
        assertThat(title.getId()).isEqualTo(id);
        assertThat(title.getSlug()).isEqualTo("orig-slug");
        assertThat(title.getTitle()).isEqualTo("Original Title");
        assertThat(title.getOriginalTitle()).isEqualTo("Original CN");
        assertThat(title.getSynopsis()).isEqualTo("Original Synopsis");
        assertThat(title.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(title.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(title.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(title.getBackdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(title.getPublishedAt()).isNull();
        assertThat(title.getCreatedAt()).isEqualTo(T0);
        assertThat(title.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Failed updateDetails is atomic: CASE C — preserves state when presentation is null")
    void shouldPreserveStateWhenUpdateDetailsFailsWithNullPresentation() {
        UUID id = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "orig-slug", "Original Title", "Original CN", "Original Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, backdropId, T0
        );

        // CASE C: valid values through structure, presentation = null
        assertThatThrownBy(() -> title.updateDetails(
                "new-valid-slug", "New Valid Title", "New CN", "New Synopsis",
                ScreenStructure.FEATURE, null,
                UUID.randomUUID(), UUID.randomUUID(), T1
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ScreenPresentation cannot be null");

        // Verify ALL original fields and updatedAt remain unchanged
        assertThat(title.getId()).isEqualTo(id);
        assertThat(title.getSlug()).isEqualTo("orig-slug");
        assertThat(title.getTitle()).isEqualTo("Original Title");
        assertThat(title.getOriginalTitle()).isEqualTo("Original CN");
        assertThat(title.getSynopsis()).isEqualTo("Original Synopsis");
        assertThat(title.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(title.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(title.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(title.getBackdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(title.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(title.getPublishedAt()).isNull();
        assertThat(title.getCreatedAt()).isEqualTo(T0);
        assertThat(title.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("Implements identity-based equals and hashCode")
    void shouldImplementIdentityBasedEquality() {
        UUID id = UUID.randomUUID();
        ScreenTitle t1 = ScreenTitle.createDraft(id, "slug-1", "Title 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0);
        ScreenTitle t2 = ScreenTitle.createDraft(id, "slug-2", "Title 2", ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, T0);
        ScreenTitle t3 = ScreenTitle.createDraft(UUID.randomUUID(), "slug-1", "Title 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, T0);

        assertThat(t1).isEqualTo(t2);
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
        assertThat(t1).isNotEqualTo(t3);
    }

    @Test
    @DisplayName("TotalEpisodeCount: accepts null and positive integer for SERIES")
    void shouldAllowNullAndPositiveTotalEpisodeCountForSeries() {
        UUID id = UUID.randomUUID();
        ScreenTitle seriesNull = ScreenTitle.createDraft(
                id, "series-null", "Series Null", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0, null
        );
        assertThat(seriesNull.getTotalEpisodeCount()).isNull();

        ScreenTitle series24 = ScreenTitle.createDraft(
                UUID.randomUUID(), "series-24", "Series 24", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0, 24
        );
        assertThat(series24.getTotalEpisodeCount()).isEqualTo(24);
    }

    @Test
    @DisplayName("TotalEpisodeCount: rejects zero and negative total for SERIES")
    void shouldRejectNonPositiveTotalEpisodeCountForSeries() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> ScreenTitle.createDraft(
                id, "series-zero", "Series Zero", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0, 0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");

        assertThatThrownBy(() -> ScreenTitle.createDraft(
                id, "series-neg", "Series Negative", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0, -5
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");
    }

    @Test
    @DisplayName("TotalEpisodeCount: rejects non-null total for FEATURE")
    void shouldRejectAnyTotalEpisodeCountForFeature() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> ScreenTitle.createDraft(
                id, "movie-feat", "Movie Feat", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null, T0, 1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEATURE");
    }

    @Test
    @DisplayName("TotalEpisodeCount: updates total and preserves on overload updateDetails")
    void shouldUpdateAndPreserveTotalEpisodeCount() {
        UUID id = UUID.randomUUID();
        ScreenTitle title = ScreenTitle.createDraft(
                id, "series-up", "Series Update", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T0, 12
        );
        assertThat(title.getTotalEpisodeCount()).isEqualTo(12);

        // Update to 24
        title.updateDetails("series-up", "Series Update", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T1, 24);
        assertThat(title.getTotalEpisodeCount()).isEqualTo(24);

        // Update without total param preserves existing 24
        title.updateDetails("series-up", "Series Update Renamed", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, T2);
        assertThat(title.getTitle()).isEqualTo("Series Update Renamed");
        assertThat(title.getTotalEpisodeCount()).isEqualTo(24);
    }
}
