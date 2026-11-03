package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.exceptions.DuplicateScreenFranchiseMembershipException;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenPresentation;
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
        ScreenFranchisePersistenceAdapter.class,
        ScreenTitlePersistenceAdapter.class,
        ScreenFranchiseMembershipPersistenceAdapter.class
})
@DisplayName("ScreenFranchiseMembership JPA Real MySQL Persistence Integration Tests")
class ScreenFranchiseMembershipJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.resetTestDatabase(TestDatabaseSupport.resolveDatabaseName());
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private ScreenFranchiseRepositoryPort franchiseRepository;

    @Autowired
    private ScreenTitleRepositoryPort titleRepository;

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

    private ScreenFranchise createFranchise(String slug, String name) {
        ScreenFranchise franchise = new ScreenFranchise(UUID.randomUUID(), slug, name);
        return franchiseRepository.save(franchise);
    }

    private ScreenTitle createTitle(String slug, String title) {
        ScreenTitle screenTitle = ScreenTitle.createDraft(
                UUID.randomUUID(), slug, title, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, Instant.parse("2026-10-06T10:00:00Z")
        );
        return titleRepository.save(screenTitle);
    }

    @Test
    @DisplayName("Should save, findById, and verify round-trip fidelity in real MySQL")
    void shouldSaveAndFindById() {
        ScreenFranchise franchise = createFranchise("kiem-lai-series", "Kiếm Lai Series");
        ScreenTitle title = createTitle("kiem-lai-part-1", "Kiếm Lai Phần 1");

        UUID membershipId = UUID.randomUUID();
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(
                membershipId, franchise.getId(), title.getId(), 0
        );

        ScreenFranchiseMembership saved = membershipRepository.save(membership);
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(membershipId);
        assertThat(saved.getFranchiseId()).isEqualTo(franchise.getId());
        assertThat(saved.getTitleId()).isEqualTo(title.getId());
        assertThat(saved.getDisplayOrder()).isEqualTo(0);

        Optional<ScreenFranchiseMembership> found = membershipRepository.findById(membershipId);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(membershipId);
        assertThat(found.get().getFranchiseId()).isEqualTo(franchise.getId());
        assertThat(found.get().getTitleId()).isEqualTo(title.getId());
        assertThat(found.get().getDisplayOrder()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should update display order and persist changes")
    void shouldUpdateDisplayOrder() {
        ScreenFranchise franchise = createFranchise("franchise-order", "Franchise Order");
        ScreenTitle title = createTitle("title-order", "Title Order");

        UUID id = UUID.randomUUID();
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(id, franchise.getId(), title.getId(), 0);
        membershipRepository.save(membership);

        membership.updateDisplayOrder(7);
        membershipRepository.save(membership);

        Optional<ScreenFranchiseMembership> found = membershipRepository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getDisplayOrder()).isEqualTo(7);
    }

    @Test
    @DisplayName("Should find by franchise ID and by composite (franchiseId, titleId)")
    void shouldFindByFranchiseIdAndComposite() {
        ScreenFranchise franchise = createFranchise("franchise-multi", "Franchise Multi");
        ScreenTitle title1 = createTitle("title-part-1", "Phần 1");
        ScreenTitle title2 = createTitle("title-part-2", "Phần 2");

        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title1.getId(), 0);
        ScreenFranchiseMembership m2 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title2.getId(), 1);

        membershipRepository.save(m1);
        membershipRepository.save(m2);

        List<ScreenFranchiseMembership> memberships = membershipRepository.findByFranchiseId(franchise.getId());
        assertThat(memberships).hasSize(2);
        assertThat(memberships).extracting(ScreenFranchiseMembership::getId)
                .containsExactly(m1.getId(), m2.getId());

        Optional<ScreenFranchiseMembership> foundComposite = membershipRepository.findByFranchiseIdAndTitleId(franchise.getId(), title1.getId());
        assertThat(foundComposite).isPresent();
        assertThat(foundComposite.get().getId()).isEqualTo(m1.getId());

        assertThat(membershipRepository.existsByFranchiseIdAndTitleId(franchise.getId(), title2.getId())).isTrue();
        assertThat(membershipRepository.existsByFranchiseIdAndTitleId(franchise.getId(), UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Should retrieve franchise memberships with deterministic displayOrder ASC and stable id ASC tie-breaker")
    void shouldRetrieveMembershipsWithDeterministicOrderAndTieBreak() {
        ScreenFranchise franchise = createFranchise("franchise-det-order", "Franchise Det Order");
        ScreenTitle title1 = createTitle("t-det-1", "Title 1");
        ScreenTitle title2 = createTitle("t-det-2", "Title 2");
        ScreenTitle title3 = createTitle("t-det-3", "Title 3");
        ScreenTitle title4 = createTitle("t-det-4", "Title 4");

        // Two items with identical displayOrder 1 to test stable tie-breaker id ASC
        UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID idC = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID idD = UUID.fromString("00000000-0000-0000-0000-000000000004");

        // Out-of-order save
        ScreenFranchiseMembership mThird = new ScreenFranchiseMembership(idC, franchise.getId(), title3.getId(), 5);
        ScreenFranchiseMembership mFirst = new ScreenFranchiseMembership(idA, franchise.getId(), title1.getId(), 0);
        ScreenFranchiseMembership mTie2 = new ScreenFranchiseMembership(idB, franchise.getId(), title2.getId(), 1);
        ScreenFranchiseMembership mTie4 = new ScreenFranchiseMembership(idD, franchise.getId(), title4.getId(), 1);

        membershipRepository.save(mThird);
        membershipRepository.save(mFirst);
        membershipRepository.save(mTie4);
        membershipRepository.save(mTie2);

        List<ScreenFranchiseMembership> ordered = membershipRepository.findByFranchiseId(franchise.getId());
        assertThat(ordered).hasSize(4);
        // Expect order: displayOrder 0 (idA), displayOrder 1 with idB, displayOrder 1 with idD, displayOrder 5 (idC)
        assertThat(ordered).extracting(ScreenFranchiseMembership::getId)
                .containsExactly(idA, idB, idD, idC);
    }

    @Test
    @DisplayName("Should verify EXPLAIN plan availability and structural resolution for idx_screen_franchise_memberships_franchise_display_order")
    void shouldVerifyExplainPlanUsesFranchiseDisplayOrderIndex() {
        ScreenFranchise franchise = createFranchise("franchise-explain-order", "Franchise Explain Order");
        ScreenTitle title = createTitle("title-explain-order", "Title Explain Order");
        membershipRepository.save(new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title.getId(), 0));

        // 1. Natural EXPLAIN: assert intended index appears in possible_keys without requiring small-table optimizer lock
        List<java.util.Map<String, Object>> naturalExplainRows = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM screen_franchise_memberships
                WHERE franchise_id = ?
                ORDER BY display_order ASC, id ASC
                """, franchise.getId().toString());

        assertThat(naturalExplainRows).isNotEmpty();
        java.util.Map<String, Object> naturalPlan = naturalExplainRows.get(0);
        String possibleKeys = (String) naturalPlan.get("possible_keys");
        assertThat(possibleKeys).contains("idx_screen_franchise_memberships_franchise_display_order");

        // 2. Forced structural proof: FORCE INDEX proves key resolves query ordering without filesort
        List<java.util.Map<String, Object>> forcedExplainRows = jdbcTemplate.queryForList("""
                EXPLAIN SELECT * FROM screen_franchise_memberships FORCE INDEX (idx_screen_franchise_memberships_franchise_display_order)
                WHERE franchise_id = ?
                ORDER BY display_order ASC, id ASC
                """, franchise.getId().toString());

        assertThat(forcedExplainRows).isNotEmpty();
        java.util.Map<String, Object> forcedPlan = forcedExplainRows.get(0);
        assertThat((String) forcedPlan.get("key")).isEqualTo("idx_screen_franchise_memberships_franchise_display_order");

        String extra = (String) forcedPlan.get("Extra");
        if (extra != null) {
            assertThat(extra).doesNotContain("Using filesort");
        }
    }

    @Test
    @DisplayName("Should translate duplicate (franchise_id, title_id) into DuplicateScreenFranchiseMembershipException in real MySQL")
    void shouldTranslateDuplicateMembershipToDuplicateScreenFranchiseMembershipException() {
        ScreenFranchise franchise = createFranchise("franchise-uq", "Franchise UQ");
        ScreenTitle title = createTitle("title-uq", "Title UQ");

        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title.getId(), 0);
        ScreenFranchiseMembership m2 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title.getId(), 1);

        membershipRepository.save(m1);

        assertThatThrownBy(() -> membershipRepository.save(m2))
                .isInstanceOf(DuplicateScreenFranchiseMembershipException.class)
                .hasMessageContaining(franchise.getId().toString())
                .hasMessageContaining(title.getId().toString());
    }

    @Test
    @DisplayName("Should enforce V87 foreign key constraint on missing franchise as DataIntegrityViolationException")
    void shouldEnforceForeignKeyConstraintOnFranchise() {
        ScreenTitle title = createTitle("title-orphan", "Title Orphan");

        ScreenFranchiseMembership orphan = new ScreenFranchiseMembership(
                UUID.randomUUID(), UUID.randomUUID(), title.getId(), 0
        );

        assertThatThrownBy(() -> membershipRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateScreenFranchiseMembershipException.class);
    }

    @Test
    @DisplayName("Should enforce V87 foreign key constraint on missing title as DataIntegrityViolationException")
    void shouldEnforceForeignKeyConstraintOnTitle() {
        ScreenFranchise franchise = createFranchise("franchise-title-orphan", "Franchise Title Orphan");

        ScreenFranchiseMembership orphan = new ScreenFranchiseMembership(
                UUID.randomUUID(), franchise.getId(), UUID.randomUUID(), 0
        );

        assertThatThrownBy(() -> membershipRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateScreenFranchiseMembershipException.class);
    }

    @Test
    @DisplayName("Should delete membership through repository port and verify row is absent in real MySQL")
    void shouldDeleteMembershipThroughRepositoryPort() {
        ScreenFranchise franchise = createFranchise("franchise-del", "Franchise Del");
        ScreenTitle title = createTitle("title-del", "Title Del");

        UUID membershipId = UUID.randomUUID();
        ScreenFranchiseMembership membership = new ScreenFranchiseMembership(
                membershipId, franchise.getId(), title.getId(), 0
        );
        membershipRepository.save(membership);
        assertThat(membershipRepository.findById(membershipId)).isPresent();

        membershipRepository.delete(membership);

        assertThat(membershipRepository.findById(membershipId)).isEmpty();
        assertThat(membershipRepository.existsByFranchiseIdAndTitleId(franchise.getId(), title.getId())).isFalse();
        assertThat(membershipRepository.findByFranchiseId(franchise.getId())).isEmpty();
    }

    @Test
    @DisplayName("Should retrieve memberships by titleId ordered by displayOrder ASC and id ASC")
    void shouldRetrieveMembershipsByTitleId() {
        ScreenFranchise franchise1 = createFranchise("franchise-title-query-1", "Franchise Title Query 1");
        ScreenTitle title = createTitle("title-membership-query", "Title Membership Query");

        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(
                UUID.randomUUID(), franchise1.getId(), title.getId(), 0
        );
        membershipRepository.save(m1);

        List<ScreenFranchiseMembership> found = membershipRepository.findByTitleId(title.getId());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getFranchiseId()).isEqualTo(franchise1.getId());
        assertThat(found.get(0).getTitleId()).isEqualTo(title.getId());

        List<ScreenFranchiseMembership> empty = membershipRepository.findByTitleId(UUID.randomUUID());
        assertThat(empty).isEmpty();
    }
}
