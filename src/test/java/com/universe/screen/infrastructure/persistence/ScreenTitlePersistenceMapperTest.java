package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenTitlePersistenceMapper Unit Tests")
class ScreenTitlePersistenceMapperTest {

    @Test
    @DisplayName("Should correctly map fully-populated domain title to JPA entity and back")
    void shouldMapFullyPopulatedTitleRoundTrip() {
        UUID id = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00Z");
        Instant publishedAt = Instant.parse("2026-10-06T11:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-06T12:00:00Z");

        ScreenTitle domain = new ScreenTitle(
                id,
                "kiem-lai-season-1",
                "Kiếm Lai Phần 1",
                "The Great Ruler",
                "Hành trình của Trần Bình An",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                posterId,
                backdropId,
                ScreenPublicationStatus.PUBLISHED,
                publishedAt,
                createdAt,
                updatedAt
        );

        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(domain);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(id.toString());
        assertThat(entity.getSlug()).isEqualTo("kiem-lai-season-1");
        assertThat(entity.getTitle()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(entity.getOriginalTitle()).isEqualTo("The Great Ruler");
        assertThat(entity.getSynopsis()).isEqualTo("Hành trình của Trần Bình An");
        assertThat(entity.getStructure()).isEqualTo("SERIES");
        assertThat(entity.getPresentation()).isEqualTo("ANIMATION_3D");
        assertThat(entity.getPosterMediaAssetId()).isEqualTo(posterId.toString());
        assertThat(entity.getBackdropMediaAssetId()).isEqualTo(backdropId.toString());
        assertThat(entity.getStatus()).isEqualTo("PUBLISHED");
        assertThat(entity.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);

        ScreenTitle rehydrated = ScreenTitlePersistenceMapper.toDomain(entity);

        assertThat(rehydrated.getId()).isEqualTo(id);
        assertThat(rehydrated.getSlug()).isEqualTo("kiem-lai-season-1");
        assertThat(rehydrated.getTitle()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(rehydrated.getOriginalTitle()).isEqualTo("The Great Ruler");
        assertThat(rehydrated.getSynopsis()).isEqualTo("Hành trình của Trần Bình An");
        assertThat(rehydrated.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(rehydrated.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(rehydrated.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(rehydrated.getBackdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(rehydrated.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(rehydrated.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(rehydrated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(rehydrated.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should correctly handle nullable fields in DRAFT status")
    void shouldHandleNullableFieldsInDraft() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00Z");

        ScreenTitle draft = ScreenTitle.createDraft(
                id,
                "kiem-lai-movie",
                "Kiếm Lai Movie",
                ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION,
                createdAt
        );

        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(draft);

        assertThat(entity.getOriginalTitle()).isNull();
        assertThat(entity.getSynopsis()).isNull();
        assertThat(entity.getPosterMediaAssetId()).isNull();
        assertThat(entity.getBackdropMediaAssetId()).isNull();
        assertThat(entity.getPublishedAt()).isNull();
        assertThat(entity.getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getStructure()).isEqualTo("FEATURE");
        assertThat(entity.getPresentation()).isEqualTo("LIVE_ACTION");

        ScreenTitle rehydrated = ScreenTitlePersistenceMapper.toDomain(entity);

        assertThat(rehydrated.getId()).isEqualTo(id);
        assertThat(rehydrated.getOriginalTitle()).isNull();
        assertThat(rehydrated.getSynopsis()).isNull();
        assertThat(rehydrated.getPosterMediaAssetId()).isNull();
        assertThat(rehydrated.getBackdropMediaAssetId()).isNull();
        assertThat(rehydrated.getPublishedAt()).isNull();
        assertThat(rehydrated.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(rehydrated.getStructure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(rehydrated.getPresentation()).isEqualTo(ScreenPresentation.LIVE_ACTION);
    }

    @Test
    @DisplayName("Should correctly preserve publishedAt when ARCHIVED")
    void shouldPreservePublishedAtWhenArchived() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00Z");
        Instant publishedAt = Instant.parse("2026-10-06T11:00:00Z");
        Instant archivedAt = Instant.parse("2026-10-06T12:00:00Z");

        ScreenTitle title = ScreenTitle.createDraft(
                id,
                "kiem-lai-archived",
                "Kiếm Lai Lưu Trữ",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                createdAt
        );
        title.publish(publishedAt);
        title.archive(archivedAt);

        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(title);
        assertThat(entity.getStatus()).isEqualTo("ARCHIVED");
        assertThat(entity.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(archivedAt);

        ScreenTitle rehydrated = ScreenTitlePersistenceMapper.toDomain(entity);
        assertThat(rehydrated.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(rehydrated.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(rehydrated.getUpdatedAt()).isEqualTo(archivedAt);
    }

    @Test
    @DisplayName("Should update existing JPA entity attributes")
    void shouldUpdateEntityAttributes() {
        UUID id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T11:00:00Z");

        ScreenTitleJpaEntity entity = new ScreenTitleJpaEntity();
        entity.setId(id.toString());

        ScreenTitle domain = ScreenTitle.createDraft(
                id,
                "slug-v2",
                "Title V2",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                t1
        );
        domain.update(
                "slug-v2-updated",
                "Title Updated",
                "Original",
                "Synopsis",
                ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                t2
        );

        ScreenTitlePersistenceMapper.updateEntity(entity, domain);

        assertThat(entity.getSlug()).isEqualTo("slug-v2-updated");
        assertThat(entity.getTitle()).isEqualTo("Title Updated");
        assertThat(entity.getOriginalTitle()).isEqualTo("Original");
        assertThat(entity.getSynopsis()).isEqualTo("Synopsis");
        assertThat(entity.getStructure()).isEqualTo("FEATURE");
        assertThat(entity.getPresentation()).isEqualTo("LIVE_ACTION");
        assertThat(entity.getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Should fail rehydration explicitly if corrupted entity violates domain invariants")
    void shouldFailRehydrationOnCorruptedEntity() {
        ScreenTitleJpaEntity invalidEntity = new ScreenTitleJpaEntity(
                UUID.randomUUID().toString(),
                "INVALID SLUG WITH UPPERCASE!",
                "Valid Title",
                null,
                null,
                "SERIES",
                "ANIMATION_3D",
                null,
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenTitlePersistenceMapper.toDomain(invalidEntity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should fail rehydration when posterMediaAssetId is blank or malformed")
    void shouldFailRehydrationWhenPosterMediaAssetIdIsBlankOrMalformed() {
        ScreenTitleJpaEntity blankPoster = new ScreenTitleJpaEntity(
                UUID.randomUUID().toString(),
                "valid-slug",
                "Valid Title",
                null,
                null,
                "SERIES",
                "ANIMATION_3D",
                "   ", // blank string
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenTitlePersistenceMapper.toDomain(blankPoster))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenTitleJpaEntity malformedPoster = new ScreenTitleJpaEntity(
                UUID.randomUUID().toString(),
                "valid-slug",
                "Valid Title",
                null,
                null,
                "SERIES",
                "ANIMATION_3D",
                "not-a-valid-uuid", // malformed string
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenTitlePersistenceMapper.toDomain(malformedPoster))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should fail rehydration when backdropMediaAssetId is blank or malformed")
    void shouldFailRehydrationWhenBackdropMediaAssetIdIsBlankOrMalformed() {
        ScreenTitleJpaEntity blankBackdrop = new ScreenTitleJpaEntity(
                UUID.randomUUID().toString(),
                "valid-slug",
                "Valid Title",
                null,
                null,
                "SERIES",
                "ANIMATION_3D",
                null,
                "", // empty blank string
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenTitlePersistenceMapper.toDomain(blankBackdrop))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenTitleJpaEntity malformedBackdrop = new ScreenTitleJpaEntity(
                UUID.randomUUID().toString(),
                "valid-slug",
                "Valid Title",
                null,
                null,
                "SERIES",
                "ANIMATION_3D",
                null,
                "123-malformed", // malformed string
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenTitlePersistenceMapper.toDomain(malformedBackdrop))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should correctly map totalEpisodeCount to entity and rehydrate to domain")
    void shouldMapTotalEpisodeCountRoundTrip() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        ScreenTitle withTotal = ScreenTitle.createDraft(
                id, "series-total", "Series Total", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 24
        );

        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(withTotal);
        assertThat(entity.getTotalEpisodeCount()).isEqualTo(24);

        ScreenTitle rehydrated = ScreenTitlePersistenceMapper.toDomain(entity);
        assertThat(rehydrated.getTotalEpisodeCount()).isEqualTo(24);

        // Update entity updates totalEpisodeCount
        ScreenTitle updatedTotal = ScreenTitle.createDraft(
                id, "series-total", "Series Total", null, null,
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now, 36
        );
        ScreenTitlePersistenceMapper.updateEntity(entity, updatedTotal);
        assertThat(entity.getTotalEpisodeCount()).isEqualTo(36);
    }

    @Test
    @DisplayName("Should correctly map extended release metadata fields to entity and rehydrate to domain")
    void shouldMapExtendedReleaseMetadataRoundTrip() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        ScreenTitle domain = ScreenTitle.createDraft(
                id, "meta-roundtrip", "Meta Roundtrip", "剑来", "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now,
                24, com.universe.screen.domain.ScreenAiringStatus.ONGOING, 2024,
                "Trung Quốc", "Phong Hỏa Hí Chư Hầu", "Đạo diễn A", "Studio B"
        );

        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(domain);
        assertThat(entity.getAiringStatus()).isEqualTo("ONGOING");
        assertThat(entity.getReleaseYear()).isEqualTo(2024);
        assertThat(entity.getCountry()).isEqualTo("Trung Quốc");
        assertThat(entity.getOriginalAuthor()).isEqualTo("Phong Hỏa Hí Chư Hầu");
        assertThat(entity.getDirector()).isEqualTo("Đạo diễn A");
        assertThat(entity.getProductionStudio()).isEqualTo("Studio B");

        ScreenTitle rehydrated = ScreenTitlePersistenceMapper.toDomain(entity);
        assertThat(rehydrated.getAiringStatus()).isEqualTo(com.universe.screen.domain.ScreenAiringStatus.ONGOING);
        assertThat(rehydrated.getReleaseYear()).isEqualTo(2024);
        assertThat(rehydrated.getCountry()).isEqualTo("Trung Quốc");
        assertThat(rehydrated.getOriginalAuthor()).isEqualTo("Phong Hỏa Hí Chư Hầu");
        assertThat(rehydrated.getDirector()).isEqualTo("Đạo diễn A");
        assertThat(rehydrated.getProductionStudio()).isEqualTo("Studio B");

        // Test updateEntity
        ScreenTitle updatedDomain = ScreenTitle.createDraft(
                id, "meta-roundtrip", "Meta Roundtrip", "剑来", "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now,
                24, com.universe.screen.domain.ScreenAiringStatus.COMPLETED, 2025,
                "Việt Nam", "Tác giả mới", "Đạo diễn mới", "Studio mới"
        );
        ScreenTitlePersistenceMapper.updateEntity(entity, updatedDomain);
        assertThat(entity.getAiringStatus()).isEqualTo("COMPLETED");
        assertThat(entity.getReleaseYear()).isEqualTo(2025);
        assertThat(entity.getCountry()).isEqualTo("Việt Nam");
        assertThat(entity.getOriginalAuthor()).isEqualTo("Tác giả mới");
        assertThat(entity.getDirector()).isEqualTo("Đạo diễn mới");
        assertThat(entity.getProductionStudio()).isEqualTo("Studio mới");
    }

    @Test
    @DisplayName("Should correctly map default timeline metadata fields to entity, updateEntity, and rehydrate to domain")
    void shouldMapDefaultTimelineMetadataRoundTrip() {
        UUID id = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T11:00:00Z");

        ScreenTitle domain = ScreenTitle.createDraft(
                id, "timeline-series", "Timeline Series",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t1
        );
        domain.updateDefaultTimeline(15L, 105L, 1320L, 1400L, t2);

        ScreenTitleJpaEntity entity = ScreenTitlePersistenceMapper.toEntity(domain);
        assertThat(entity.getDefaultIntroStartSeconds()).isEqualTo(15L);
        assertThat(entity.getDefaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(entity.getDefaultOutroStartSeconds()).isEqualTo(1320L);
        assertThat(entity.getDefaultOutroEndSeconds()).isEqualTo(1400L);

        ScreenTitle rehydrated = ScreenTitlePersistenceMapper.toDomain(entity);
        assertThat(rehydrated.getDefaultIntroStartSeconds()).isEqualTo(15L);
        assertThat(rehydrated.getDefaultIntroEndSeconds()).isEqualTo(105L);
        assertThat(rehydrated.getDefaultOutroStartSeconds()).isEqualTo(1320L);
        assertThat(rehydrated.getDefaultOutroEndSeconds()).isEqualTo(1400L);

        // Update entity timeline metadata
        domain.updateDefaultTimeline(0L, 90L, null, null, t2);
        ScreenTitlePersistenceMapper.updateEntity(entity, domain);
        assertThat(entity.getDefaultIntroStartSeconds()).isEqualTo(0L);
        assertThat(entity.getDefaultIntroEndSeconds()).isEqualTo(90L);
        assertThat(entity.getDefaultOutroStartSeconds()).isNull();
        assertThat(entity.getDefaultOutroEndSeconds()).isNull();
    }

    @Test
    @DisplayName("Should fail rehydration when persisted default timeline metadata on ScreenTitleJpaEntity is corrupted")
    void shouldFailRehydrationWhenPersistedDefaultTimelineIsCorrupted() {
        ScreenTitleJpaEntity partialIntroEntity = new ScreenTitleJpaEntity(
                UUID.randomUUID().toString(),
                "valid-slug",
                "Valid Title",
                null,
                null,
                "SERIES",
                "ANIMATION_3D",
                null,
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );
        partialIntroEntity.setDefaultIntroStartSeconds(15L);
        partialIntroEntity.setDefaultIntroEndSeconds(null);

        assertThatThrownBy(() -> ScreenTitlePersistenceMapper.toDomain(partialIntroEntity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
