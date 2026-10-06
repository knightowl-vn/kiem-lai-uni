package com.universe.screen.infrastructure.persistence;

import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
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
@Import(ScreenTitlePersistenceAdapter.class)
@DisplayName("ScreenTitle JPA Real MySQL Persistence Integration Tests")
class ScreenTitleJpaPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private ScreenTitleRepositoryPort repositoryPort;

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
}
