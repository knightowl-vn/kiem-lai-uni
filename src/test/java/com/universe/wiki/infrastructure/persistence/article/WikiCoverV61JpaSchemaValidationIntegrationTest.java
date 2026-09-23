package com.universe.wiki.infrastructure.persistence.article;

import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that Hibernate EntityManagerFactory boots cleanly with {@code ddl-auto=validate}
 * against the real migrated MySQL schema containing V61 (TINYINT UNSIGNED cover positions),
 * and confirms that Java {@code byte} properly maps to JDBC {@code Types#TINYINT}.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Wiki Cover V61 JPA / Hibernate Schema-Validation Integration Test (MySQL)")
class WikiCoverV61JpaSchemaValidationIntegrationTest {

    private static final String TEST_USER_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SpringDataWikiArticleJpaRepository repository;

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Test
    @DisplayName("Hibernate validates V61 TINYINT UNSIGNED columns and persists focal positions 0..100 without type mismatch")
    void shouldValidateSchemaAndPersistFocalPositions() {
        String articleId = UUID.randomUUID().toString();
        UUID coverAssetId = UUID.randomUUID();
        Instant now = Instant.now();

        WikiArticleJpaEntity entity = new WikiArticleJpaEntity();
        entity.setId(articleId);
        entity.setTitle("V61 Validation Article");
        entity.setSlug("v61-validation-article-" + articleId.substring(0, 8));
        entity.setArticleType("CHARACTER");
        entity.setSummary("V61 Schema Validation summary");
        entity.setContent("# V61 Validation Content");
        entity.setStatus("DRAFT");
        entity.setCreatedBy(TEST_USER_ID);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setAggregateVersion(1L);
        entity.setContentVersion(1L);
        entity.setCoverMediaAssetId(coverAssetId.toString());
        entity.setCoverPositionX((byte) 0);
        entity.setCoverPositionY((byte) 100);

        repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        WikiArticleJpaEntity loaded = repository.findById(articleId).orElseThrow();
        assertThat(loaded.getCoverMediaAssetId()).isEqualTo(coverAssetId.toString());
        assertThat(loaded.getCoverPositionX()).isEqualTo((byte) 0);
        assertThat(loaded.getCoverPositionY()).isEqualTo((byte) 100);
        assertThat(Byte.toUnsignedInt(loaded.getCoverPositionX())).isEqualTo(0);
        assertThat(Byte.toUnsignedInt(loaded.getCoverPositionY())).isEqualTo(100);

        // Clean up
        repository.delete(loaded);
        entityManager.flush();
    }
}
