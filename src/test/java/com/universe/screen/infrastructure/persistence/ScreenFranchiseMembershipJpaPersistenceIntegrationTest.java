package com.universe.screen.infrastructure.persistence;

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
                .containsExactlyInAnyOrder(m1.getId(), m2.getId());

        Optional<ScreenFranchiseMembership> foundComposite = membershipRepository.findByFranchiseIdAndTitleId(franchise.getId(), title1.getId());
        assertThat(foundComposite).isPresent();
        assertThat(foundComposite.get().getId()).isEqualTo(m1.getId());

        assertThat(membershipRepository.existsByFranchiseIdAndTitleId(franchise.getId(), title2.getId())).isTrue();
        assertThat(membershipRepository.existsByFranchiseIdAndTitleId(franchise.getId(), UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Should enforce V87 unique constraint on (franchise_id, title_id)")
    void shouldEnforceCompositeUniqueConstraint() {
        ScreenFranchise franchise = createFranchise("franchise-uq", "Franchise UQ");
        ScreenTitle title = createTitle("title-uq", "Title UQ");

        ScreenFranchiseMembership m1 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title.getId(), 0);
        ScreenFranchiseMembership m2 = new ScreenFranchiseMembership(UUID.randomUUID(), franchise.getId(), title.getId(), 1);

        membershipRepository.save(m1);

        assertThatThrownBy(() -> membershipRepository.save(m2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should enforce V87 foreign key constraint when parent franchise does not exist")
    void shouldEnforceForeignKeyConstraintOnFranchise() {
        ScreenTitle title = createTitle("title-orphan", "Title Orphan");

        ScreenFranchiseMembership orphan = new ScreenFranchiseMembership(
                UUID.randomUUID(), UUID.randomUUID(), title.getId(), 0
        );

        assertThatThrownBy(() -> membershipRepository.save(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
