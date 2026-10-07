package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import com.universe.screen.domain.ScreenFranchiseMembership;
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
import java.time.temporal.ChronoUnit;
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
        ScreenFranchisePersistenceAdapter.class,
        ScreenFranchiseMembershipPersistenceAdapter.class
})
@DisplayName("ScreenTitle JPA Real MySQL Persistence Integration Tests")
class ScreenTitleJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase(TestDatabaseSupport.resolveDatabaseName());
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private ScreenTitleRepositoryPort repositoryPort;

    @Autowired
    private ScreenFranchiseRepositoryPort franchiseRepository;

    @Autowired
    private ScreenFranchiseMembershipRepositoryPort membershipRepository;

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

    @Test
    @DisplayName("Should save, findById, and verify round-trip fidelity with full metadata and media scalar UUIDs")
    void shouldSaveAndFindByIdFullMetadata() {
        UUID id = UUID.randomUUID();
        UUID posterId = UUID.randomUUID();
        UUID backdropId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00.123456Z");
        Instant publishedAt = Instant.parse("2026-10-06T11:00:00.654321Z");
        Instant updatedAt = Instant.parse("2026-10-06T12:00:00.999999Z");

        ScreenTitle title = new ScreenTitle(
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

        ScreenTitle saved = repositoryPort.save(title);
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(saved.getBackdropMediaAssetId()).isEqualTo(backdropId);

        Optional<ScreenTitle> found = repositoryPort.findById(id);
        assertThat(found).isPresent();
        ScreenTitle loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getSlug()).isEqualTo("kiem-lai-season-1");
        assertThat(loaded.getTitle()).isEqualTo("Kiếm Lai Phần 1");
        assertThat(loaded.getOriginalTitle()).isEqualTo("The Great Ruler");
        assertThat(loaded.getSynopsis()).isEqualTo("Hành trình của Trần Bình An");
        assertThat(loaded.getStructure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(loaded.getPresentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(loaded.getPosterMediaAssetId()).isEqualTo(posterId);
        assertThat(loaded.getBackdropMediaAssetId()).isEqualTo(backdropId);
        assertThat(loaded.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(loaded.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(loaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("Should save minimal DRAFT title with null optional fields and verify nullability round-trip")
    void shouldSaveAndLoadMinimalDraft() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenTitle draft = ScreenTitle.createDraft(
                id,
                "kiem-lai-movie-1",
                "Kiếm Lai Movie 1",
                ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION,
                now
        );

        repositoryPort.save(draft);

        Optional<ScreenTitle> found = repositoryPort.findById(id);
        assertThat(found).isPresent();
        ScreenTitle loaded = found.get();
        assertThat(loaded.getOriginalTitle()).isNull();
        assertThat(loaded.getSynopsis()).isNull();
        assertThat(loaded.getPosterMediaAssetId()).isNull();
        assertThat(loaded.getBackdropMediaAssetId()).isNull();
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(ScreenPublicationStatus.DRAFT);
        assertThat(loaded.getStructure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(loaded.getPresentation()).isEqualTo(ScreenPresentation.LIVE_ACTION);
    }

    @Test
    @DisplayName("Should update title details and publish, then verify state in MySQL")
    void shouldUpdateDetailsAndPublish() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-06T10:00:00.000000Z");
        Instant updatedAt = Instant.parse("2026-10-06T11:00:00.000000Z");
        Instant publishedAt = Instant.parse("2026-10-06T12:00:00.000000Z");

        ScreenTitle title = ScreenTitle.createDraft(
                id,
                "draft-title",
                "Draft Title",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                createdAt
        );
        repositoryPort.save(title);

        title.update(
                "updated-slug",
                "Updated Title",
                "Original",
                "Synopsis",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                updatedAt
        );
        title.publish(publishedAt);

        repositoryPort.save(title);

        Optional<ScreenTitle> found = repositoryPort.findById(id);
        assertThat(found).isPresent();
        ScreenTitle loaded = found.get();
        assertThat(loaded.getSlug()).isEqualTo("updated-slug");
        assertThat(loaded.getTitle()).isEqualTo("Updated Title");
        assertThat(loaded.getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
        assertThat(loaded.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(loaded.getUpdatedAt()).isEqualTo(publishedAt);
    }

    @Test
    @DisplayName("Should find by slug and verify case normalization")
    void shouldFindBySlug() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");

        ScreenTitle title = ScreenTitle.createDraft(
                id,
                "kiem-lai-slug-lookup",
                "Title For Slug Lookup",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                now
        );
        repositoryPort.save(title);

        assertThat(repositoryPort.findBySlug("kiem-lai-slug-lookup")).isPresent();
        assertThat(repositoryPort.findBySlug("KIEM-LAI-SLUG-LOOKUP")).isPresent();
        assertThat(repositoryPort.findBySlug("non-existing-slug")).isEmpty();
    }

    @Test
    @DisplayName("Should enforce V87 unique constraint on slug")
    void shouldEnforceSlugUniqueConstraint() {
        Instant now = Instant.parse("2026-10-06T10:00:00.000000Z");
        ScreenTitle title1 = ScreenTitle.createDraft(
                UUID.randomUUID(), "duplicate-title-slug", "Title 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );
        ScreenTitle title2 = ScreenTitle.createDraft(
                UUID.randomUUID(), "duplicate-title-slug", "Title 2", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        );

        repositoryPort.save(title1);

        assertThatThrownBy(() -> repositoryPort.save(title2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should find published titles by ID and slug with fail-closed semantics (DRAFT and ARCHIVED excluded)")
    void shouldFindPublishedByIdAndSlugWithFailClosedSemantics() {
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-06T11:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T12:00:00Z");

        // 1. DRAFT title
        UUID draftId = UUID.randomUUID();
        ScreenTitle draft = ScreenTitle.createDraft(
                draftId, "title-draft", "Draft Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0
        );
        repositoryPort.save(draft);

        // 2. PUBLISHED title
        UUID publishedId = UUID.randomUUID();
        ScreenTitle published = ScreenTitle.createDraft(
                publishedId, "title-published", "Published Title", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0
        );
        published.publish(t1);
        repositoryPort.save(published);

        // 3. ARCHIVED title
        UUID archivedId = UUID.randomUUID();
        ScreenTitle archived = ScreenTitle.createDraft(
                archivedId, "title-archived", "Archived Title", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, t0
        );
        archived.publish(t1);
        archived.archive(t2);
        repositoryPort.save(archived);

        // Standard identity lookup still finds all titles
        assertThat(repositoryPort.findById(draftId)).isPresent();
        assertThat(repositoryPort.findById(publishedId)).isPresent();
        assertThat(repositoryPort.findById(archivedId)).isPresent();

        // Published-only lookup: DRAFT excluded
        assertThat(repositoryPort.findPublishedById(draftId)).isEmpty();
        assertThat(repositoryPort.findPublishedBySlug("title-draft")).isEmpty();

        // Published-only lookup: PUBLISHED included
        Optional<ScreenTitle> foundPubById = repositoryPort.findPublishedById(publishedId);
        assertThat(foundPubById).isPresent();
        assertThat(foundPubById.get().getId()).isEqualTo(publishedId);
        assertThat(foundPubById.get().getStatus()).isEqualTo(ScreenPublicationStatus.PUBLISHED);

        Optional<ScreenTitle> foundPubBySlug = repositoryPort.findPublishedBySlug("title-published");
        assertThat(foundPubBySlug).isPresent();
        assertThat(foundPubBySlug.get().getId()).isEqualTo(publishedId);

        // Case-insensitive normalized slug lookup
        assertThat(repositoryPort.findPublishedBySlug("TITLE-PUBLISHED")).isPresent();

        // Published-only lookup: ARCHIVED excluded (fail-closed, not just status != DRAFT)
        assertThat(repositoryPort.findPublishedById(archivedId)).isEmpty();
        assertThat(repositoryPort.findPublishedBySlug("title-archived")).isEmpty();
    }

    @Test
    @DisplayName("Should retrieve franchise member titles in deterministic membership order")
    void shouldRetrieveFranchiseMemberTitlesInDeterministicOrder() {
        ScreenFranchise franchise = franchiseRepository.save(
                new ScreenFranchise(UUID.randomUUID(), "franchise-ordered-titles", "Franchise Ordered Titles")
        );

        Instant now = Instant.parse("2026-10-06T10:00:00Z");
        ScreenTitle titleA = repositoryPort.save(ScreenTitle.createDraft(
                UUID.randomUUID(), "title-ord-a", "Title A", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        ));
        ScreenTitle titleB = repositoryPort.save(ScreenTitle.createDraft(
                UUID.randomUUID(), "title-ord-b", "Title B", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        ));
        ScreenTitle titleC = repositoryPort.save(ScreenTitle.createDraft(
                UUID.randomUUID(), "title-ord-c", "Title C", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, now
        ));

        // Create memberships with out-of-order displayOrder
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), titleB.getId(), 2));
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), titleA.getId(), 0));
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), titleC.getId(), 1));

        List<ScreenTitle> retrievedTitles = repositoryPort.findByFranchiseId(franchise.getId());
        assertThat(retrievedTitles).hasSize(3);
        assertThat(retrievedTitles).extracting(ScreenTitle::getId)
                .containsExactly(titleA.getId(), titleC.getId(), titleB.getId());
    }

    @Test
    @DisplayName("Should retrieve published franchise member titles excluding DRAFT and ARCHIVED")
    void shouldRetrievePublishedFranchiseMemberTitlesExcludingDraftAndArchived() {
        ScreenFranchise franchise = franchiseRepository.save(
                new ScreenFranchise(UUID.randomUUID(), "franchise-pub-titles", "Franchise Pub Titles")
        );

        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-06T11:00:00Z");
        Instant t2 = Instant.parse("2026-10-06T12:00:00Z");

        // 1. PUBLISHED title (display_order 0)
        ScreenTitle pub1 = ScreenTitle.createDraft(
                UUID.randomUUID(), "pub-1", "Pub 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0
        );
        pub1.publish(t1);
        repositoryPort.save(pub1);

        // 2. DRAFT title (display_order 1)
        ScreenTitle draft = ScreenTitle.createDraft(
                UUID.randomUUID(), "draft-2", "Draft 2", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0
        );
        repositoryPort.save(draft);

        // 3. ARCHIVED title (display_order 2)
        ScreenTitle archived = ScreenTitle.createDraft(
                UUID.randomUUID(), "archived-3", "Archived 3", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0
        );
        archived.publish(t1);
        archived.archive(t2);
        repositoryPort.save(archived);

        // 4. PUBLISHED title (display_order 3)
        ScreenTitle pub2 = ScreenTitle.createDraft(
                UUID.randomUUID(), "pub-4", "Pub 4", ScreenStructure.FEATURE, ScreenPresentation.ANIMATION_3D, t0
        );
        pub2.publish(t1);
        repositoryPort.save(pub2);

        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), pub1.getId(), 0));
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), draft.getId(), 1));
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), archived.getId(), 2));
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), pub2.getId(), 3));

        // findByFranchiseId returns all 4 in displayOrder
        List<ScreenTitle> allTitles = repositoryPort.findByFranchiseId(franchise.getId());
        assertThat(allTitles).hasSize(4);
        assertThat(allTitles).extracting(ScreenTitle::getId)
                .containsExactly(pub1.getId(), draft.getId(), archived.getId(), pub2.getId());

        // findPublishedByFranchiseId excludes DRAFT and ARCHIVED, preserves displayOrder
        List<ScreenTitle> publishedTitles = repositoryPort.findPublishedByFranchiseId(franchise.getId());
        assertThat(publishedTitles).hasSize(2);
        assertThat(publishedTitles).extracting(ScreenTitle::getId)
                .containsExactly(pub1.getId(), pub2.getId());
    }

    @Test
    @DisplayName("Should order franchise member titles by membership id tie-breaker when displayOrder is equal")
    void shouldOrderFranchiseMemberTitlesByMembershipIdTieBreakerWhenDisplayOrderIsEqual() {
        ScreenFranchise franchise = franchiseRepository.save(
                new ScreenFranchise(UUID.randomUUID(), "franchise-tie-break", "Franchise Tie Break")
        );

        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = Instant.parse("2026-10-06T11:00:00Z");

        // Title for lower membership ID has HIGHER title UUID
        UUID titleHighId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        // Title for higher membership ID has LOWER title UUID
        UUID titleLowId = UUID.fromString("00000000-0000-0000-0000-000000000010");

        ScreenTitle titleAssociatedWithMLow = repositoryPort.save(
                ScreenTitle.createDraft(titleHighId, "title-high-uuid", "Title High UUID", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0)
        );
        titleAssociatedWithMLow.publish(t1);
        repositoryPort.save(titleAssociatedWithMLow);

        ScreenTitle titleAssociatedWithMHigh = repositoryPort.save(
                ScreenTitle.createDraft(titleLowId, "title-low-uuid", "Title Low UUID", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0)
        );
        titleAssociatedWithMHigh.publish(t1);
        repositoryPort.save(titleAssociatedWithMHigh);

        // Controlled membership UUIDs with identical displayOrder
        UUID mLowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mHighId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Associate mLow with titleHighId, and mHigh with titleLowId
        membershipRepository.save(new ScreenFranchiseMembership(mHighId, franchise.getId(), titleLowId, 1));
        membershipRepository.save(new ScreenFranchiseMembership(mLowId, franchise.getId(), titleHighId, 1));

        // 1. Unfiltered franchise titles: MUST follow m.id ASC, NOT t.id ASC
        List<ScreenTitle> titles = repositoryPort.findByFranchiseId(franchise.getId());
        assertThat(titles).hasSize(2);
        assertThat(titles).extracting(ScreenTitle::getId)
                .containsExactly(titleHighId, titleLowId);

        // 2. Published franchise titles: MUST follow m.id ASC after filtering
        List<ScreenTitle> pubTitles = repositoryPort.findPublishedByFranchiseId(franchise.getId());
        assertThat(pubTitles).hasSize(2);
        assertThat(pubTitles).extracting(ScreenTitle::getId)
                .containsExactly(titleHighId, titleLowId);
    }

    @Test
    @DisplayName("Should verify SQL join query plan in MySQL EXPLAIN for franchise member title retrieval (runtime statement count not measured)")
    void shouldVerifySingleQueryJoinExecutionAndExplainForFranchiseTitles() {
        ScreenFranchise franchise = franchiseRepository.save(
                new ScreenFranchise(UUID.randomUUID(), "franchise-explain-titles", "Franchise Explain Titles")
        );

        Instant now = Instant.parse("2026-10-06T10:00:00Z");
        ScreenTitle title1 = repositoryPort.save(ScreenTitle.createDraft(
                UUID.randomUUID(), "explain-t1", "Explain T1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, now
        ));
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title1.getId(), 0));

        // EXPLAIN query audit in real MySQL matching unified query shape
        List<Map<String, Object>> explainRows = jdbcTemplate.queryForList("""
                EXPLAIN SELECT t.* FROM screen_titles t
                INNER JOIN screen_franchise_memberships m ON m.title_id = t.id
                WHERE m.franchise_id = ? AND t.status = 'PUBLISHED'
                ORDER BY m.display_order ASC, m.id ASC
                """, franchise.getId().toString());

        assertThat(explainRows).isNotEmpty();
        // Verifies one SQL join shape includes membership (m) + title (t) tables without per-row repository lookups.
        // NOTE: Runtime Hibernate/JPA statement count is not measured by EXPLAIN.
        assertThat(explainRows).extracting(row -> (String) row.get("table"))
                .containsExactlyInAnyOrder("m", "t");
    }
}
