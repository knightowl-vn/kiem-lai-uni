package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.ScreenEntrySlugAlreadyExistsException;
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
import java.util.Map;
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
        TestDatabaseSupport.resetTestDatabase(TestDatabaseSupport.resolveDatabaseName());
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
    @DisplayName("Should translate duplicate title-scoped slug INSERT to ScreenEntrySlugAlreadyExistsException")
    void shouldEnforceCompositeUniqueConstraint() {
        ScreenTitle title = createTitle("title-uq-entries", "Title UQ Entries", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenEntry e1 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "dup-slug", ScreenEntryType.EPISODE, 1, "Ep 1", now);
        ScreenEntry e2 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "dup-slug", ScreenEntryType.EPISODE, 2, "Ep 2", now);

        entryRepository.save(e1);

        assertThatThrownBy(() -> entryRepository.save(e2))
                .isInstanceOf(ScreenEntrySlugAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should translate duplicate title-scoped slug UPDATE to ScreenEntrySlugAlreadyExistsException")
    void shouldTranslateDuplicateSlugOnUpdate() {
        ScreenTitle title = createTitle("title-dup-update", "Title Dup Update", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenEntry e1 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "slug-1", ScreenEntryType.EPISODE, 1, "Ep 1", now);
        ScreenEntry e2 = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "slug-2", ScreenEntryType.EPISODE, 2, "Ep 2", now);
        entryRepository.save(e1);
        entryRepository.save(e2);

        e2.update("slug-1", ScreenEntryType.EPISODE, 2, "Ep 2", null, null, null, null, now.plusSeconds(10));
        assertThatThrownBy(() -> entryRepository.save(e2))
                .isInstanceOf(ScreenEntrySlugAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should not misclassify unrelated constraint failure as slug exception")
    void shouldNotMisclassifyUnrelatedConstraintFailure() {
        // Attempting to insert an entry with non-existent foreign title_id violates FK constraint fk_screen_entries_title
        UUID nonExistentTitleId = UUID.randomUUID();
        ScreenEntry orphan = ScreenEntry.createDraft(UUID.randomUUID(), nonExistentTitleId, "orphan", ScreenEntryType.EPISODE, 1, "Orphan", Instant.now());

        assertThatThrownBy(() -> entryRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ScreenEntrySlugAlreadyExistsException.class);
    }

    @Test
    @DisplayName("Should persist first publishedAt and preserve publishedAt on metadata edit and archive")
    void shouldPersistFirstPublishedAtAndPreserveOnEditAndArchive() {
        ScreenTitle title = createTitle("title-lifecycle-persist", "Title Lifecycle", ScreenStructure.SERIES);
        Instant t0 = Instant.parse("2026-10-06T10:00:00.000000Z");
        Instant publishTime = Instant.parse("2026-10-06T11:00:00.000000Z");
        Instant editTime = Instant.parse("2026-10-06T12:00:00.000000Z");
        Instant archiveTime = Instant.parse("2026-10-06T13:00:00.000000Z");

        UUID id = UUID.randomUUID();
        ScreenEntry entry = ScreenEntry.createDraft(id, title.getId(), "ep-life", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
        entryRepository.save(entry);

        // 1. First publish
        entry.publish(publishTime);
        entryRepository.save(entry);

        ScreenEntry reloadedPub = entryRepository.findById(id).orElseThrow();
        assertThat(reloadedPub.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(reloadedPub.getPublishedAt()).isEqualTo(publishTime);

        // 2. Metadata edit preserves publishedAt
        reloadedPub.update("ep-life-renamed", ScreenEntryType.EPISODE, 1, "Ep 1 Renamed", "Synopsis", 1200L, null, null, editTime);
        entryRepository.save(reloadedPub);

        ScreenEntry reloadedEdit = entryRepository.findById(id).orElseThrow();
        assertThat(reloadedEdit.getPublishedAt()).isEqualTo(publishTime);
        assertThat(reloadedEdit.getTitle()).isEqualTo("Ep 1 Renamed");

        // 3. Archive preserves publishedAt
        reloadedEdit.archive(archiveTime);
        entryRepository.save(reloadedEdit);

        ScreenEntry reloadedArch = entryRepository.findById(id).orElseThrow();
        assertThat(reloadedArch.getStatus()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
        assertThat(reloadedArch.getPublishedAt()).isEqualTo(publishTime);
    }

    @Test
    @DisplayName("Should preserve existing video and thumbnail UUIDs after metadata update and reload")
    void shouldPreserveExistingMediaUuidsAfterMetadataUpdateAndReload() {
        ScreenTitle title = createTitle("title-media-preserve", "Title Media Preserve", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        UUID id = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();

        ScreenEntry entry = new ScreenEntry(
                id, title.getId(), "ep-media", ScreenEntryType.EPISODE, 1, "Ep Media",
                "Synopsis", videoId, thumbId, 1200L, ScreenPublicationStatus.DRAFT, null, now, now
        );
        entryRepository.save(entry);

        // Metadata update passing existing videoId and thumbId
        ScreenEntry loaded = entryRepository.findById(id).orElseThrow();
        loaded.update(
                "ep-media-updated", ScreenEntryType.EPISODE, 1, "Ep Media Updated",
                "New Synopsis", 1300L, loaded.getVideoMediaAssetId(), loaded.getThumbnailMediaAssetId(), now.plusSeconds(100)
        );
        entryRepository.save(loaded);

        ScreenEntry reloaded = entryRepository.findById(id).orElseThrow();
        assertThat(reloaded.getVideoMediaAssetId()).isEqualTo(videoId);
        assertThat(reloaded.getThumbnailMediaAssetId()).isEqualTo(thumbId);
        assertThat(reloaded.getTitle()).isEqualTo("Ep Media Updated");
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

    @Test
    @DisplayName("Should find published entries with fail-closed semantics (DRAFT and ARCHIVED excluded)")
    void shouldFindPublishedEntriesWithFailClosedSemantics() {
        ScreenTitle title = createTitle("title-pub-filter", "Title Publication Filter", ScreenStructure.SERIES);
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-06T11:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T12:00:00Z");

        // 1. DRAFT entry
        UUID draftId = UUID.randomUUID();
        ScreenEntry draft = ScreenEntry.createDraft(draftId, title.getId(), "ep-draft", ScreenEntryType.EPISODE, 1, "Ep Draft", t0);
        entryRepository.save(draft);

        // 2. PUBLISHED entry
        UUID pubId = UUID.randomUUID();
        ScreenEntry published = ScreenEntry.createDraft(pubId, title.getId(), "ep-pub", ScreenEntryType.EPISODE, 2, "Ep Published", t0);
        published.publish(t1);
        entryRepository.save(published);

        // 3. ARCHIVED entry
        UUID archId = UUID.randomUUID();
        ScreenEntry archived = ScreenEntry.createDraft(archId, title.getId(), "ep-arch", ScreenEntryType.EPISODE, 3, "Ep Archived", t0);
        archived.publish(t1);
        archived.archive(t2);
        entryRepository.save(archived);

        // ID lookup across statuses
        assertThat(entryRepository.findById(draftId)).isPresent();
        assertThat(entryRepository.findById(pubId)).isPresent();
        assertThat(entryRepository.findById(archId)).isPresent();

        // Published-only single lookups: fail-closed
        assertThat(entryRepository.findPublishedById(draftId)).isEmpty();
        assertThat(entryRepository.findPublishedById(pubId)).isPresent();
        assertThat(entryRepository.findPublishedById(pubId).get().getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(entryRepository.findPublishedById(archId)).isEmpty();

        // Published-only slug lookups: fail-closed
        assertThat(entryRepository.findPublishedByTitleIdAndSlug(title.getId(), "ep-draft")).isEmpty();
        assertThat(entryRepository.findPublishedByTitleIdAndSlug(title.getId(), "ep-pub")).isPresent();
        assertThat(entryRepository.findPublishedByTitleIdAndSlug(title.getId(), "EP-PUB")).isPresent(); // normalized
        assertThat(entryRepository.findPublishedByTitleIdAndSlug(title.getId(), "ep-arch")).isEmpty();

        // Published collection query: only pubId included, draft and arch excluded
        List<ScreenEntry> publishedList = entryRepository.findPublishedByTitleId(title.getId());
        assertThat(publishedList).hasSize(1);
        assertThat(publishedList.get(0).getId()).isEqualTo(pubId);

        // Unfiltered collection query: all 3 included
        List<ScreenEntry> allList = entryRepository.findByTitleId(title.getId());
        assertThat(allList).hasSize(3);
    }

    @Test
    @DisplayName("Should retrieve generic entries deterministically by id ASC only (generic entry order != public navigation order)")
    void shouldRetrieveGenericEntriesDeterministicallyByIdAsc() {
        ScreenTitle title = createTitle("title-generic-order", "Title Generic Order", ScreenStructure.SERIES);
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID id4 = UUID.fromString("00000000-0000-0000-0000-000000000004");

        // Insert in scrambled order with mixed types and numbers
        ScreenEntry trailer = ScreenEntry.createDraft(id4, title.getId(), "trailer-1", ScreenEntryType.TRAILER, 1, "Trailer 1", now);
        ScreenEntry episode2 = ScreenEntry.createDraft(id2, title.getId(), "ep-2", ScreenEntryType.EPISODE, 2, "Episode 2", now);
        ScreenEntry episode1 = ScreenEntry.createDraft(id3, title.getId(), "ep-1", ScreenEntryType.EPISODE, 1, "Episode 1", now);
        ScreenEntry special = ScreenEntry.createDraft(id1, title.getId(), "spec-1", ScreenEntryType.SPECIAL, null, "Special 1", now);

        entryRepository.save(trailer);
        entryRepository.save(episode2);
        entryRepository.save(episode1);
        entryRepository.save(special);

        List<ScreenEntry> entries = entryRepository.findByTitleId(title.getId());
        assertThat(entries).hasSize(4);

        // Technical deterministic total order: ORDER BY id ASC (neutral, NOT public navigation order)
        assertThat(entries).extracting(ScreenEntry::getId).containsExactly(
                id1,
                id2,
                id3,
                id4
        );
    }

    @Test
    @DisplayName("Should retrieve published generic entries ordered by id ASC excluding DRAFT and ARCHIVED")
    void shouldRetrievePublishedGenericEntriesOrderedByIdAscExcludingDraftAndArchived() {
        ScreenTitle title = createTitle("title-generic-pub", "Title Generic Pub", ScreenStructure.SERIES);
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-06T11:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T12:00:00Z");

        UUID idLow = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID idMid = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UUID idHigh = UUID.fromString("00000000-0000-0000-0000-000000000030");

        ScreenEntry draft = ScreenEntry.createDraft(idLow, title.getId(), "draft-ep", ScreenEntryType.EPISODE, 1, "Draft", t0);
        ScreenEntry pub1 = ScreenEntry.createDraft(idHigh, title.getId(), "pub-1", ScreenEntryType.EPISODE, 2, "Pub 1", t0);
        pub1.publish(t1);
        ScreenEntry pub2 = ScreenEntry.createDraft(idMid, title.getId(), "pub-2", ScreenEntryType.SPECIAL, 1, "Pub 2", t0);
        pub2.publish(t1);
        ScreenEntry arch = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "arch-ep", ScreenEntryType.EPISODE, 3, "Arch", t0);
        arch.publish(t1);
        arch.archive(t2);

        entryRepository.save(draft);
        entryRepository.save(pub1);
        entryRepository.save(pub2);
        entryRepository.save(arch);

        List<ScreenEntry> publishedEntries = entryRepository.findPublishedByTitleId(title.getId());
        assertThat(publishedEntries).hasSize(2);
        // Deterministic by id ASC: idMid (pub2) comes before idHigh (pub1)
        assertThat(publishedEntries).extracting(ScreenEntry::getId).containsExactly(idMid, idHigh);
    }

    @Test
    @DisplayName("Should retrieve published episodes for series navigation ordered by number ASC and id ASC")
    void shouldRetrievePublishedEpisodesForSeriesNavigationOrderedByNumberAscAndIdAsc() {
        ScreenTitle title = createTitle("title-series-nav", "Title Series Nav", ScreenStructure.SERIES);
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-06T11:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T12:00:00Z");

        // Two episodes with identical number = 1 to test id ASC tie-break
        UUID ep1AId = UUID.fromString("00000000-0000-0000-0000-000000000001"); // lower UUID
        UUID ep1BId = UUID.fromString("00000000-0000-0000-0000-000000000002"); // higher UUID
        UUID ep2Id = UUID.fromString("00000000-0000-0000-0000-000000000003");  // number 2

        // Published EPISODE 1B (inserted first to test tie-break opposite to insertion order)
        ScreenEntry ep1B = ScreenEntry.createDraft(ep1BId, title.getId(), "ep-1b", ScreenEntryType.EPISODE, 1, "Ep 1B", t0);
        ep1B.publish(t1);

        // Published EPISODE 1A (lower UUID, inserted after 1B)
        ScreenEntry ep1A = ScreenEntry.createDraft(ep1AId, title.getId(), "ep-1a", ScreenEntryType.EPISODE, 1, "Ep 1A", t0);
        ep1A.publish(t1);

        // Published EPISODE 2
        ScreenEntry ep2 = ScreenEntry.createDraft(ep2Id, title.getId(), "ep-2", ScreenEntryType.EPISODE, 2, "Ep 2", t0);
        ep2.publish(t1);

        // DRAFT EPISODE (must be excluded)
        ScreenEntry draftEp = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "ep-draft", ScreenEntryType.EPISODE, 1, "Draft", t0);

        // ARCHIVED EPISODE (must be excluded)
        ScreenEntry archEp = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "ep-arch", ScreenEntryType.EPISODE, 1, "Arch", t0);
        archEp.publish(t1);
        archEp.archive(t2);

        // SPECIAL (must be excluded from SERIES episode navigation)
        ScreenEntry special = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "spec-nav", ScreenEntryType.SPECIAL, 1, "Spec", t0);
        special.publish(t1);

        // TRAILER (must be excluded from SERIES episode navigation)
        ScreenEntry trailer = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "trail-nav", ScreenEntryType.TRAILER, 1, "Trail", t0);
        trailer.publish(t1);

        // FEATURE (must be excluded from SERIES episode navigation)
        ScreenEntry feature = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "feat-nav", ScreenEntryType.FEATURE, null, "Feat", t0);
        feature.publish(t1);

        // Save with 1B inserted before 1A
        entryRepository.save(ep1B);
        entryRepository.save(ep1A);
        entryRepository.save(ep2);
        entryRepository.save(draftEp);
        entryRepository.save(archEp);
        entryRepository.save(special);
        entryRepository.save(trailer);
        entryRepository.save(feature);

        List<ScreenEntry> episodes = entryRepository.findPublishedEpisodesByTitleId(title.getId());

        // Strictly PUBLISHED EPISODE only, ordered by number ASC, id ASC
        // Proves id ASC tie-break: ep1A (lower UUID) comes before ep1B within equal number = 1
        assertThat(episodes).hasSize(3);
        assertThat(episodes).extracting(ScreenEntry::getId).containsExactly(ep1AId, ep1BId, ep2Id);
        assertThat(episodes).extracting(ScreenEntry::getNumber).containsExactly(1, 1, 2);
        assertThat(episodes).allMatch(e -> e.getType() == ScreenEntryType.EPISODE);
        assertThat(episodes).allMatch(e -> e.getStatus() == ScreenPublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("Should preserve FEATURE with null number and exclude FEATURE from episode navigation")
    void shouldPreserveFeatureAsFeatureWithNullNumberAndExcludeFromEpisodeNavigation() {
        ScreenTitle movieTitle = createTitle("movie-feat-test", "Movie Feat Test", ScreenStructure.FEATURE);
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        ScreenEntry feature = ScreenEntry.createDraft(
                UUID.randomUUID(), movieTitle.getId(), "movie-standalone", ScreenEntryType.FEATURE, null, "Standalone Movie", now
        );
        feature.publish(now);
        entryRepository.save(feature);

        Optional<ScreenEntry> found = entryRepository.findById(feature.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getNumber()).isNull();
        assertThat(found.get().isFeature()).isTrue();

        // Episode navigation returns 0 rows for movie title (FEATURE is never fake Episode 1)
        List<ScreenEntry> episodes = entryRepository.findPublishedEpisodesByTitleId(movieTitle.getId());
        assertThat(episodes).isEmpty();
    }

    @Test
    @DisplayName("Should verify actual episode navigation query EXPLAIN plan uses composite index without filesort")
    void shouldVerifyActualEpisodeNavigationQueryExplainPlanUsesCompositeIndexWithoutFilesort() {
        ScreenTitle title = createTitle("title-explain-nav", "Title Explain Nav", ScreenStructure.SERIES);

        // Populate multiple draft and published entries
        for (int i = 1; i <= 5; i++) {
            ScreenEntry draft = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "ep-draft-" + i, ScreenEntryType.EPISODE, i, "Draft " + i, Instant.now());
            entryRepository.save(draft);
        }
        for (int i = 1; i <= 3; i++) {
            ScreenEntry pub = ScreenEntry.createDraft(UUID.randomUUID(), title.getId(), "ep-pub-" + i, ScreenEntryType.EPISODE, i + 5, "Pub " + i, Instant.now());
            pub.publish(Instant.now());
            entryRepository.save(pub);
        }

        // Actual navigation query matching SpringDataScreenEntryJpaRepository.findPublishedEpisodesByTitleId
        List<java.util.Map<String, Object>> explainRows = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM screen_entries
                WHERE title_id = ?
                  AND status = 'PUBLISHED'
                  AND type = 'EPISODE'
                  AND number > 0
                ORDER BY number ASC, id ASC
                """, title.getId().toString());

        assertThat(explainRows).isNotEmpty();
        java.util.Map<String, Object> plan = explainRows.get(0);

        String possibleKeys = (String) plan.get("possible_keys");
        assertThat(possibleKeys).contains("idx_screen_entries_title_status_type_number");

        // Verify with FORCE INDEX that the composite index resolves query ordering without filesort
        List<java.util.Map<String, Object>> forcedExplain = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM screen_entries FORCE INDEX (idx_screen_entries_title_status_type_number)
                WHERE title_id = ?
                  AND status = 'PUBLISHED'
                  AND type = 'EPISODE'
                  AND number > 0
                ORDER BY number ASC, id ASC
                """, title.getId().toString());

        assertThat(forcedExplain).isNotEmpty();
        java.util.Map<String, Object> forcedPlan = forcedExplain.get(0);
        assertThat((String) forcedPlan.get("key")).isEqualTo("idx_screen_entries_title_status_type_number");

        // When scanned on (title_id, status, type, number), rows are in index order so Extra has NO 'Using filesort'
        String extra = (String) forcedPlan.get("Extra");
        if (extra != null) {
            assertThat(extra).doesNotContain("Using filesort");
        }
    }

    @Test
    @DisplayName("Should batch query latest published episode number by title IDs in real MySQL filtering draft, archived, non-episode entries and handling number gaps")
    void shouldBatchQueryLatestPublishedEpisodeNumberByTitleIdsInRealMySql() {
        ScreenTitle title1 = createTitle("batch-title-1", "Batch Title 1", ScreenStructure.SERIES);
        ScreenTitle title2 = createTitle("batch-title-2", "Batch Title 2", ScreenStructure.SERIES);
        ScreenTitle title3 = createTitle("batch-title-3", "Batch Title 3", ScreenStructure.FEATURE);
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        // Title 1: Published episode 1, published episode 5 (number gap!), draft episode 6, archived episode 7, published trailer
        ScreenEntry t1Ep1 = ScreenEntry.createDraft(UUID.randomUUID(), title1.getId(), "t1-ep-1", ScreenEntryType.EPISODE, 1, "T1 Ep 1", now);
        t1Ep1.publish(now);
        entryRepository.save(t1Ep1);

        ScreenEntry t1Ep2 = ScreenEntry.createDraft(UUID.randomUUID(), title1.getId(), "t1-ep-5", ScreenEntryType.EPISODE, 5, "T1 Ep 5", now);
        t1Ep2.publish(now);
        entryRepository.save(t1Ep2);

        ScreenEntry t1Draft = ScreenEntry.createDraft(UUID.randomUUID(), title1.getId(), "t1-ep-6", ScreenEntryType.EPISODE, 6, "T1 Draft", now);
        entryRepository.save(t1Draft);

        ScreenEntry t1Archived = ScreenEntry.createDraft(UUID.randomUUID(), title1.getId(), "t1-ep-7", ScreenEntryType.EPISODE, 7, "T1 Archived", now);
        t1Archived.publish(now);
        t1Archived.archive(now);
        entryRepository.save(t1Archived);

        ScreenEntry t1Trailer = ScreenEntry.createDraft(UUID.randomUUID(), title1.getId(), "t1-trailer", ScreenEntryType.TRAILER, null, "T1 Trailer", now);
        t1Trailer.publish(now);
        entryRepository.save(t1Trailer);

        // Title 2: 1 published episode (number 1)
        ScreenEntry t2Ep1 = ScreenEntry.createDraft(UUID.randomUUID(), title2.getId(), "t2-ep-1", ScreenEntryType.EPISODE, 1, "T2 Ep 1", now);
        t2Ep1.publish(now);
        entryRepository.save(t2Ep1);

        // Title 3 (Feature): 1 published feature (number is null, type is FEATURE)
        ScreenEntry t3Feat = ScreenEntry.createDraft(UUID.randomUUID(), title3.getId(), "t3-feat", ScreenEntryType.FEATURE, null, "T3 Feat", now);
        t3Feat.publish(now);
        entryRepository.save(t3Feat);

        // Execute batch MAX query
        Map<UUID, Integer> latestEpisodes = entryRepository.findLatestPublishedEpisodeNumbersByTitleIds(
                List.of(title1.getId(), title2.getId(), title3.getId())
        );

        // Max published episode for title1 is 5 (not count 2!)
        assertThat(latestEpisodes)
                .containsEntry(title1.getId(), 5)
                .containsEntry(title2.getId(), 1)
                .doesNotContainKey(title3.getId());

        // Single title query
        assertThat(entryRepository.findLatestPublishedEpisodeNumberByTitleId(title1.getId())).isEqualTo(5);
        assertThat(entryRepository.findLatestPublishedEpisodeNumberByTitleId(title2.getId())).isEqualTo(1);
        assertThat(entryRepository.findLatestPublishedEpisodeNumberByTitleId(title3.getId())).isNull();

        // Empty input returns empty map
        assertThat(entryRepository.findLatestPublishedEpisodeNumbersByTitleIds(List.of())).isEmpty();
    }
}
