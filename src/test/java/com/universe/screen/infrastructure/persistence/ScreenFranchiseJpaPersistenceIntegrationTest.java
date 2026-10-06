package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.domain.ScreenFranchise;
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
@Import(ScreenFranchisePersistenceAdapter.class)
@DisplayName("ScreenFranchise JPA Real MySQL Persistence Integration Tests")
class ScreenFranchiseJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private ScreenFranchiseRepositoryPort repositoryPort;

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
    @DisplayName("Should save, findById, and verify round-trip fidelity in real MySQL")
    void shouldSaveAndFindById() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai-universe", "Kiếm Lai Vũ Trụ");

        ScreenFranchise saved = repositoryPort.save(franchise);
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getSlug()).isEqualTo("kiem-lai-universe");
        assertThat(saved.getName()).isEqualTo("Kiếm Lai Vũ Trụ");

        Optional<ScreenFranchise> found = repositoryPort.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(id);
        assertThat(found.get().getSlug()).isEqualTo("kiem-lai-universe");
        assertThat(found.get().getName()).isEqualTo("Kiếm Lai Vũ Trụ");
    }

    @Test
    @DisplayName("Should update existing franchise and persist changes to real MySQL")
    void shouldUpdateExistingFranchise() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai-orig", "Kiếm Lai Gốc");
        repositoryPort.save(franchise);

        franchise.update("kiem-lai-renamed", "Kiếm Lai Đổi Tên");
        ScreenFranchise updated = repositoryPort.save(franchise);

        assertThat(updated.getSlug()).isEqualTo("kiem-lai-renamed");
        assertThat(updated.getName()).isEqualTo("Kiếm Lai Đổi Tên");

        Optional<ScreenFranchise> found = repositoryPort.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("kiem-lai-renamed");
        assertThat(found.get().getName()).isEqualTo("Kiếm Lai Đổi Tên");
    }

    @Test
    @DisplayName("Should find franchise by slug and verify case-normalization behavior")
    void shouldFindBySlug() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai-slug-test", "Kiếm Lai Slug Test");
        repositoryPort.save(franchise);

        Optional<ScreenFranchise> foundExact = repositoryPort.findBySlug("kiem-lai-slug-test");
        assertThat(foundExact).isPresent();
        assertThat(foundExact.get().getId()).isEqualTo(id);

        Optional<ScreenFranchise> foundUpper = repositoryPort.findBySlug("KIEM-LAI-SLUG-TEST");
        assertThat(foundUpper).isPresent();
        assertThat(foundUpper.get().getId()).isEqualTo(id);

        Optional<ScreenFranchise> missing = repositoryPort.findBySlug("non-existent-slug");
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("Should verify existsById and existsBySlug")
    void shouldVerifyExistsQueries() {
        UUID id = UUID.randomUUID();
        ScreenFranchise franchise = new ScreenFranchise(id, "kiem-lai-exists", "Kiếm Lai Exists");
        repositoryPort.save(franchise);

        assertThat(repositoryPort.existsById(id)).isTrue();
        assertThat(repositoryPort.existsById(UUID.randomUUID())).isFalse();

        assertThat(repositoryPort.existsBySlug("kiem-lai-exists")).isTrue();
        assertThat(repositoryPort.existsBySlug("not-exists")).isFalse();
    }

    @Test
    @DisplayName("Should enforce V87 unique constraint on slug in real MySQL")
    void shouldEnforceSlugUniqueConstraint() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        ScreenFranchise franchise1 = new ScreenFranchise(id1, "duplicate-slug", "Franchise 1");
        ScreenFranchise franchise2 = new ScreenFranchise(id2, "duplicate-slug", "Franchise 2");

        repositoryPort.save(franchise1);

        assertThatThrownBy(() -> repositoryPort.save(franchise2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
