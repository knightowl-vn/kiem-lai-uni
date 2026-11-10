package com.universe.screen.infrastructure.persistence;

import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPublicationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScreenEntryPersistenceMapper Unit Tests")
class ScreenEntryPersistenceMapperTest {

    @Test
    @DisplayName("Should correctly map EPISODE entry round-trip with all fields")
    void shouldMapEpisodeEntryRoundTrip() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00Z");
        Instant publishedAt = Instant.parse("2026-10-06T11:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-06T12:00:00Z");

        ScreenEntry entry = new ScreenEntry(
                id,
                titleId,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1: Xuất Sơn",
                "Tập đầu tiên mở màn",
                videoId,
                thumbId,
                1800L,
                ScreenPublicationStatus.PUBLISHED,
                publishedAt,
                createdAt,
                updatedAt
        );

        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(entry);

        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isEqualTo(id.toString());
        assertThat(entity.getTitleId()).isEqualTo(titleId.toString());
        assertThat(entity.getSlug()).isEqualTo("tap-1");
        assertThat(entity.getType()).isEqualTo("EPISODE");
        assertThat(entity.getNumber()).isEqualTo(1);
        assertThat(entity.getTitle()).isEqualTo("Tập 1: Xuất Sơn");
        assertThat(entity.getSynopsis()).isEqualTo("Tập đầu tiên mở màn");
        assertThat(entity.getVideoMediaAssetId()).isEqualTo(videoId.toString());
        assertThat(entity.getThumbnailMediaAssetId()).isEqualTo(thumbId.toString());
        assertThat(entity.getDurationSeconds()).isEqualTo(1800L);
        assertThat(entity.getStatus()).isEqualTo("PUBLISHED");
        assertThat(entity.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);

        ScreenEntry rehydrated = ScreenEntryPersistenceMapper.toDomain(entity);

        assertThat(rehydrated.getId()).isEqualTo(id);
        assertThat(rehydrated.getTitleId()).isEqualTo(titleId);
        assertThat(rehydrated.getSlug()).isEqualTo("tap-1");
        assertThat(rehydrated.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(rehydrated.getNumber()).isEqualTo(1);
        assertThat(rehydrated.getTitle()).isEqualTo("Tập 1: Xuất Sơn");
        assertThat(rehydrated.getSynopsis()).isEqualTo("Tập đầu tiên mở màn");
        assertThat(rehydrated.getVideoMediaAssetId()).isEqualTo(videoId);
        assertThat(rehydrated.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(rehydrated.getDurationSeconds()).isEqualTo(1800L);
        assertThat(rehydrated.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(rehydrated.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(rehydrated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(rehydrated.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should correctly map FEATURE entry with null number (movie is not fake Episode 1)")
    void shouldMapFeatureEntryWithNullNumber() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00Z");

        ScreenEntry feature = ScreenEntry.createDraft(
                id,
                titleId,
                "movie-main",
                ScreenEntryType.FEATURE,
                null,
                "Kiếm Lai Movie",
                createdAt
        );

        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(feature);

        assertThat(entity.getNumber()).isNull();
        assertThat(entity.getType()).isEqualTo("FEATURE");

        ScreenEntry rehydrated = ScreenEntryPersistenceMapper.toDomain(entity);

        assertThat(rehydrated.getNumber()).isNull();
        assertThat(rehydrated.getType()).isEqualTo(ScreenEntryType.FEATURE);
        assertThat(rehydrated.isFeature()).isTrue();
    }

    @Test
    @DisplayName("Should correctly handle SPECIAL and TRAILER with nullable numbers")
    void shouldHandleSpecialAndTrailerNumbering() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        ScreenEntry specialWithoutNumber = ScreenEntry.createDraft(
                id1, titleId, "special-ova", ScreenEntryType.SPECIAL, null, "Special OVA", now
        );
        ScreenEntry trailerWithNumber = ScreenEntry.createDraft(
                id2, titleId, "trailer-1", ScreenEntryType.TRAILER, 1, "Official Trailer 1", now
        );

        ScreenEntryJpaEntity entity1 = ScreenEntryPersistenceMapper.toEntity(specialWithoutNumber);
        ScreenEntryJpaEntity entity2 = ScreenEntryPersistenceMapper.toEntity(trailerWithNumber);

        assertThat(entity1.getNumber()).isNull();
        assertThat(entity2.getNumber()).isEqualTo(1);

        ScreenEntry rehydrated1 = ScreenEntryPersistenceMapper.toDomain(entity1);
        ScreenEntry rehydrated2 = ScreenEntryPersistenceMapper.toDomain(entity2);

        assertThat(rehydrated1.getNumber()).isNull();
        assertThat(rehydrated2.getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should update existing JPA entity fields")
    void shouldUpdateEntityFields() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T11:00:00Z");

        ScreenEntryJpaEntity entity = new ScreenEntryJpaEntity();
        entity.setId(id.toString());

        ScreenEntry domain = ScreenEntry.createDraft(
                id, titleId, "slug-1", ScreenEntryType.EPISODE, 1, "Title 1", t1
        );
        domain.update(
                "slug-1-updated",
                ScreenEntryType.EPISODE,
                2,
                "Title Updated",
                "Updated synopsis",
                2400L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                t2
        );

        ScreenEntryPersistenceMapper.updateEntity(entity, domain);

        assertThat(entity.getSlug()).isEqualTo("slug-1-updated");
        assertThat(entity.getNumber()).isEqualTo(2);
        assertThat(entity.getTitle()).isEqualTo("Title Updated");
        assertThat(entity.getSynopsis()).isEqualTo("Updated synopsis");
        assertThat(entity.getDurationSeconds()).isEqualTo(2400L);
        assertThat(entity.getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Should fail rehydration if persisted entity violates domain invariants (e.g. FEATURE has number)")
    void shouldFailRehydrationWhenInvariantsViolated() {
        ScreenEntryJpaEntity invalidFeature = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "movie",
                "FEATURE",
                1, // invalid: FEATURE must not have an entry number
                "Movie Title",
                null,
                null,
                null,
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(invalidFeature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FEATURE entry must not have an entry number");
    }

    @Test
    @DisplayName("Should fail rehydration when videoMediaAssetId is blank or malformed")
    void shouldFailRehydrationWhenVideoMediaAssetIdIsBlankOrMalformed() {
        ScreenEntryJpaEntity blankVideo = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "entry-slug",
                "EPISODE",
                1,
                "Episode Title",
                null,
                "   ", // blank string
                null,
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(blankVideo))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenEntryJpaEntity malformedVideo = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "entry-slug",
                "EPISODE",
                1,
                "Episode Title",
                null,
                "invalid-uuid-format", // malformed string
                null,
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(malformedVideo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should fail rehydration when thumbnailMediaAssetId is blank or malformed")
    void shouldFailRehydrationWhenThumbnailMediaAssetIdIsBlankOrMalformed() {
        ScreenEntryJpaEntity blankThumbnail = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "entry-slug",
                "EPISODE",
                1,
                "Episode Title",
                null,
                null,
                "", // empty blank string
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(blankThumbnail))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenEntryJpaEntity malformedThumbnail = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "entry-slug",
                "EPISODE",
                1,
                "Episode Title",
                null,
                null,
                "not-a-uuid", // malformed string
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(malformedThumbnail))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should correctly map entry timeline modes and overrides to entity, updateEntity, and rehydrate to domain")
    void shouldMapEntryTimelineMetadataRoundTrip() {
        UUID id = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T11:00:00Z");

        ScreenEntry domain = ScreenEntry.createDraft(
                id, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", t1
        );
        domain.updateTimeline(
                com.universe.screen.domain.ScreenTimelineMode.OVERRIDE, 20L, 110L,
                com.universe.screen.domain.ScreenTimelineMode.DISABLED, null, t2
        );

        ScreenEntryJpaEntity entity = ScreenEntryPersistenceMapper.toEntity(domain);
        assertThat(entity.getIntroMode()).isEqualTo("OVERRIDE");
        assertThat(entity.getIntroStartSeconds()).isEqualTo(20L);
        assertThat(entity.getIntroEndSeconds()).isEqualTo(110L);
        assertThat(entity.getOutroMode()).isEqualTo("DISABLED");
        assertThat(entity.getOutroStartSeconds()).isNull();

        ScreenEntry rehydrated = ScreenEntryPersistenceMapper.toDomain(entity);
        assertThat(rehydrated.getIntroMode()).isEqualTo(com.universe.screen.domain.ScreenTimelineMode.OVERRIDE);
        assertThat(rehydrated.getIntroStartSeconds()).isEqualTo(20L);
        assertThat(rehydrated.getIntroEndSeconds()).isEqualTo(110L);
        assertThat(rehydrated.getOutroMode()).isEqualTo(com.universe.screen.domain.ScreenTimelineMode.DISABLED);
        assertThat(rehydrated.getOutroStartSeconds()).isNull();

        // Update entity timeline to INHERIT + OVERRIDE
        domain.updateTimeline(
                com.universe.screen.domain.ScreenTimelineMode.INHERIT, null, null,
                com.universe.screen.domain.ScreenTimelineMode.OVERRIDE, 1340L, 1420L, t2
        );
        ScreenEntryPersistenceMapper.updateEntity(entity, domain);
        assertThat(entity.getIntroMode()).isEqualTo("INHERIT");
        assertThat(entity.getIntroStartSeconds()).isNull();
        assertThat(entity.getIntroEndSeconds()).isNull();
        assertThat(entity.getOutroMode()).isEqualTo("OVERRIDE");
        assertThat(entity.getOutroStartSeconds()).isEqualTo(1340L);
        assertThat(entity.getOutroEndSeconds()).isEqualTo(1420L);

        ScreenEntry rehydratedBounded = ScreenEntryPersistenceMapper.toDomain(entity);
        assertThat(rehydratedBounded.getOutroEndSeconds()).isEqualTo(1420L);
    }

    @Test
    @DisplayName("Should fail rehydration when persisted timeline mode or override values on ScreenEntryJpaEntity are corrupted")
    void shouldFailRehydrationWhenPersistedEntryTimelineIsCorrupted() {
        ScreenEntryJpaEntity corruptedOverride = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "entry-slug",
                "EPISODE",
                1,
                "Episode Title",
                null,
                null,
                null,
                1500L,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );
        corruptedOverride.setIntroMode("OVERRIDE");
        corruptedOverride.setIntroStartSeconds(null);
        corruptedOverride.setIntroEndSeconds(null);

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(corruptedOverride))
                .isInstanceOf(IllegalArgumentException.class);

        ScreenEntryJpaEntity nullModeEntity = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "entry-slug",
                "EPISODE",
                1,
                "Episode Title",
                null,
                null,
                null,
                1500L,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );
        nullModeEntity.setIntroMode(null);

        assertThatThrownBy(() -> ScreenEntryPersistenceMapper.toDomain(nullModeEntity))
                .isInstanceOf(RuntimeException.class);
    }
}
