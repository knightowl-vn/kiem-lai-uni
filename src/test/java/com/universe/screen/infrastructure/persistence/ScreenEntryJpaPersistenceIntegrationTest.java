package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        ScreenTitlePersistenceAdapter.class,
        ScreenEntryPersistenceAdapter.class
})
@DisplayName("ScreenEntry JPA Real MySQL Persistence Integration Tests")
class ScreenEntryJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private ScreenTitleRepositoryPort titleRepository;

    @Autowired
    private ScreenEntryRepositoryPort entryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM screen_entries");
        jdbcTemplate.execute("DELETE FROM screen_franchise_memberships");
        jdbcTemplate.execute("DELETE FROM screen_titles");
        jdbcTemplate.execute("DELETE FROM screen_franchises");
    }

    private ScreenTitle createTitle(String slug, String title, ScreenStructure structure) {
        ScreenTitle screenTitle = ScreenTitle.createDraft(
                UUID.randomUUID(), slug, title, structure, ScreenPresentation.ANIMATION_3D, Instant.parse("2026-10-06T10:00:00Z")
        );
        return titleRepository.save(screenTitle);
    }

    @Test
    @DisplayName("Should save and find EPISODE entry with all metadata and media scalar UUIDs in real MySQL")
    void shouldSaveAndFindEpisodeEntry() {
        ScreenTitle title = createTitle("kiem-lai-s1", "Kiếm Lai S1", ScreenStructure.SERIES);

        UUID entryId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00.123456Z");
        Instant publishedAt = Instant.parse("2026-10-06T11:00:00.123456Z");
        Instant updatedAt = Instant.parse("2026-10-06T12:00:00.123456Z");

        ScreenEntry entry = new ScreenEntry(
                entryId,
                title.getId(),
                "tap-01",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1",
                "Tập đầu tiên",
                videoId,
                thumbId,
                1200L,
                ScreenPublicationStatus.PUBLISHED,
                publishedAt,
                createdAt,
                updatedAt
        );

        ScreenEntry saved = entryRepository.save(entry);
        assertThat(saved).isNotNull();
        assertThat(saved.getVideoMediaAssetId()).isEqualTo(videoId);
        assertThat(saved.getThumbnailMediaAssetId()).isEqualTo(thumbId);

        Optional<ScreenEntry> found = entryRepository.findById(entryId);
        assertThat(found).isPresent();
        ScreenEntry loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(entryId);
        assertThat(loaded.getTitleId()).isEqualTo(title.getId());
        assertThat(loaded.getSlug()).isEqualTo("tap-01");
        assertThat(loaded.getType()).isEqualTo(ScreenEntryType.EPISODE);
        assertThat(loaded.getNumber()).isEqualTo(1);
        assertThat(loaded.getTitle()).isEqualTo("Tập 1");
        assertThat(loaded.getSynopsis()).isEqualTo("Tập đầu tiên");
        assertThat(loaded.getVideoMediaAssetId()).isEqualTo(videoId);
        assertThat(loaded.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(loaded.getDurationSeconds()).isEqualTo(1200L);
        assertThat(loaded.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(loaded.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(loaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should save FEATURE entry with null number (movie is not fake Episode 1)")
    void shouldSaveFeatureEntryWithNullNumber() {
        ScreenTitle title = createTitle("kiem-lai-movie", "Kiếm Lai Movie", ScreenStructure.FEATURE);

        UUID entryId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenEntry feature = ScreenEntry.createDraft(
                entryId,
                title.getId(),
                "movie-full",
                ScreenEntryType.FEATURE,
                null,
                "Bản Chiếu Rạp",
                createdAt
        );

        entryRepository.save(feature);

        Optional<ScreenEntry> found = entryRepository.findById(entryId);
        assertThat(found).isPresent();
        ScreenEntry loaded = found.get();
        assertThat(loaded.getType()).isEqualTo(ScreenEntryType.FEATURE);
        assertThat(loaded.getNumber()).isNull();
        assertThat(loaded.isFeature()).isTrue();
    }

    @Test
    @DisplayName("Should save SPECIAL and TRAILER with nullable numbers")
    void shouldSaveSpecialAndTrailerWithNullableNumbers() {
        ScreenTitle title = createTitle("kiem-lai-extras", "Kiếm Lai Extras", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenEntry special = ScreenEntry.createDraft(
                UUID.randomUUID(), title.getId(), "special-side", ScreenEntryType.SPECIAL, null, "Side Story", now
        );
        ScreenEntry trailer = ScreenEntry.createDraft(
                UUID.randomUUID(), title.getId(), "trailer-teaser", ScreenEntryType.TRAILER, 1, "Teaser 1", now
        );

        entryRepository.save(special);
        entryRepository.save(trailer);

        Optional<ScreenEntry> foundSpecial = entryRepository.findById(special.getId());
        Optional<ScreenEntry> foundTrailer = entryRepository.findById(trailer.getId());

        assertThat(foundSpecial).isPresent();
        assertThat(foundSpecial.get().getNumber()).isNull();

        assertThat(foundTrailer).isPresent();
        assertThat(foundTrailer.get().getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should find entries by title ID and by (titleId, slug)")
    void shouldFindByTitleIdAndSlug() {
        ScreenTitle title = createTitle("title-entries", "Title Entries", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenEntry e1 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
        ScreenEntry e2 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "ep-2", ScreenEntryType.EPISODE, 2, "Ep 2", now);

        entryRepository.save(e1);
        entryRepository.save(e2);

        List<ScreenEntry> entries = entryRepository.findByTitleId(title.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(ScreenEntry::getId)
                .containsExactlyInAnyOrder(e1.getId(), e2.getId());

        Optional<ScreenEntry> foundSlug = entryRepository.findByTitleIdAndSlug(title.getId(), "ep-1");
        assertThat(foundSlug).isPresent();
        assertThat(foundSlug.get().getId()).isEqualTo(e1.getId());

        assertThat(entryRepository.existsByTitleIdAndSlug(title.getId(), "ep-2")).isTrue();
        assertThat(entryRepository.existsByTitleIdAndSlug(title.getId(), "non-existent")).isFalse();
    }

    @Test
    @DisplayName("Should update entry details and persist changes to real MySQL")
    void shouldUpdateEntryDetails() {
        ScreenTitle title = createTitle("title-update", "Title Update", ScreenStructure.SERIES);
        Instant t1 = Instant.parse("2026-10-06T10:00:00.000000Z");
        Instant t2 = Instant.parse("2026-10-06T11:00:00.000000Z");

        UUID id = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, title.getId(), "slug-old", ScreenEntryType.EPISODE, 1, "Old Title", t1);
        entryRepository.save(entry);

        entry.update(
                "slug-new",
                ScreenEntryType.EPISODE,
                1,
                "New Title",
                "New synopsis",
                1500L,
                UUID.randomUUID(),
                UUID.randomUUID(),
                t2
        );
        entryRepository.save(entry);

        Optional<ScreenEntry> found = entryRepository.findById(id);
        assertThat(found).isPresent();
        ScreenEntry loaded = found.get();
        assertThat(loaded.getSlug()).isEqualTo("slug-new");
        assertThat(loaded.getTitle()).isEqualTo("New Title");
        assertThat(loaded.getSynopsis()).isEqualTo("New synopsis");
        assertThat(loaded.getDurationSeconds()).isEqualTo(1500L);
        assertThat(loaded.getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Should enforce V87 unique constraint on (title_id, slug)")
    void shouldEnforceCompositeUniqueConstraint() {
        ScreenTitle title = createTitle("title-uq-entries", "Title UQ Entries", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenEntry e1 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "dup-slug", ScreenEntryType.EPISODE, 1, "Ep 1", now);
        ScreenEntry e2 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "dup-slug", ScreenEntryType.EPISODE, 2, "Ep 2", now);

        entryRepository.save(e1);

        assertThatThrownBy(() -> entryRepository.save(e2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should enforce V87 check constraint on EPISODE number requiring non-null > 0")
    void shouldEnforceNumberCheckConstraint() {
        ScreenTitle title = createTitle("title-chk-entries", "Title Check Entries", ScreenStructure.SERIES);

        // Bypass domain validation by manually using entity to test database constraint chk_screen_entries_number_for_type
        ScreenEntryJpaEntity invalidEntity = new ScreenEntryJpaEntity(
                UUID.randomUUID().toString(),
                title.getId().toString(),
                "invalid-number",
                "EPISODE",
                null, // EPISODE with null number violates chk_screen_entries_number_for_type in V87
                "Invalid Episode",
                null,
                null,
                null,
                null,
                "DRAFT",
                null,
                Instant.now(),
                Instant.now()
        );

        assertThatThrownBy(() -> jdbcTemplate.execute(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) VALUES ('"
                        + invalidEntity.getId() + "', '"
                        + invalidEntity.getTitleId() + "', '"
                        + invalidEntity.getSlug() + "', '"
                        + invalidEntity.getType() + "', "
                        + "NULL, '"
                        + invalidEntity.getTitle() + "', '"
                        + invalidEntity.getStatus() + "', NOW(6), NOW(6))"
        )).isInstanceOf(Exception.class);
    }
}
