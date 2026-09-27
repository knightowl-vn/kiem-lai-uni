package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Wiki Appreciation V57, V58, V59 Flyway Migration Verification Tests")
class WikiAppreciationV57V58V59MigrationVerificationTest {

    private static final String TEST_DB_NAME = "kiemlai_wiki_appreciation_v57_v59_test";
    private static final UUID ARTICLE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Test
    @DisplayName("Kiểm chứng tiến trình migration V56 -> V57 -> V58 -> V59 và các bất biến trung gian")
    void shouldVerifyStepByStepMigrationProgression() {
        TestDatabaseSupport.resetTestDatabase(TEST_DB_NAME);
        DataSource dataSource = TestDatabaseSupport.createTestDataSource(TEST_DB_NAME);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Flyway flyway56 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("56")
                .load();
        flyway56.migrate();

        // Seed 1 test article
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", ARTICLE_ID.toString());
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, 'Bạch Ngưng Chi', 'bach-ngung-chi', 'CHARACTER', 'PUBLISHED', 'Tóm tắt', '# Nội dung',
                    ?, ?, ?, ?, ?, 1, 1)
                """,
                ARTICLE_ID.toString(), ADMIN_ID.toString(), now, now, ADMIN_ID.toString(), now
        );

        // Seed 5 legacy ratings (values 1, 2, 3, 4, 5 representing 1.0, 2.0, 3.0, 4.0, 5.0 stars)
        UUID[] userIds = new UUID[5];
        for (int i = 1; i <= 5; i++) {
            userIds[i - 1] = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), ARTICLE_ID.toString(), userIds[i - 1].toString(), i, now, now
            );
        }

        // =========================================================
        // TARGET 57: Shadow column added, initial state NULL
        // =========================================================
        Flyway flyway57 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("57")
                .load();
        flyway57.migrate();

        Integer shadowColumnExists = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_appreciation_ratings'
                  AND column_name = 'half_star_units'
                """, Integer.class, TEST_DB_NAME);
        assertThat(shadowColumnExists).isEqualTo(1);

        List<Map<String, Object>> rowsV57 = jdbcTemplate.queryForList(
                "SELECT value, half_star_units FROM wiki_appreciation_ratings ORDER BY value ASC"
        );
        assertThat(rowsV57).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(((Number) rowsV57.get(i).get("value")).intValue()).isEqualTo(i + 1);
            assertThat(rowsV57.get(i).get("half_star_units")).isNull();
        }

        // =========================================================
        // TARGET 58: Shadow column backfilled from untouched source (value * 2)
        // =========================================================
        Flyway flyway58 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("58")
                .load();
        flyway58.migrate();

        List<Map<String, Object>> rowsV58 = jdbcTemplate.queryForList(
                "SELECT value, half_star_units FROM wiki_appreciation_ratings ORDER BY value ASC"
        );
        assertThat(rowsV58).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(((Number) rowsV58.get(i).get("value")).intValue()).isEqualTo(i + 1);
            assertThat(((Number) rowsV58.get(i).get("half_star_units")).intValue()).isEqualTo((i + 1) * 2);
        }

        // =========================================================
        // TARGET 59: Finalize schema swap (drop old value & check, rename shadow to value, enforce CHECK 2..10)
        // =========================================================
        Flyway flyway59 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("59")
                .load();
        flyway59.migrate();

        Integer shadowColumnAfterV59 = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_appreciation_ratings'
                  AND column_name = 'half_star_units'
                """, Integer.class, TEST_DB_NAME);
        assertThat(shadowColumnAfterV59).isEqualTo(0);

        List<Integer> valuesV59 = jdbcTemplate.queryForList(
                "SELECT value FROM wiki_appreciation_ratings ORDER BY value ASC",
                Integer.class
        );
        assertThat(valuesV59).containsExactly(2, 4, 6, 8, 10);

        String isNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_appreciation_ratings'
                  AND column_name = 'value'
                """, String.class, TEST_DB_NAME);
        assertThat(isNullable).isEqualTo("NO");

        // Verify CHECK constraint (chk_wiki_appreciation_ratings_value):
        // 1 rejected (below min 2)
        UUID userReject1 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """, UUID.randomUUID().toString(), ARTICLE_ID.toString(), userReject1.toString(), now, now))
                .hasMessageContaining("chk_wiki_appreciation_ratings_value");

        // 11 rejected (above max 10)
        UUID userReject11 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 11, ?, ?)
                """, UUID.randomUUID().toString(), ARTICLE_ID.toString(), userReject11.toString(), now, now))
                .hasMessageContaining("chk_wiki_appreciation_ratings_value");

        // 3 accepted (1.5 stars)
        UUID userAccept3 = UUID.randomUUID();
        int inserted3 = jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 3, ?, ?)
                """, UUID.randomUUID().toString(), ARTICLE_ID.toString(), userAccept3.toString(), now, now);
        assertThat(inserted3).isEqualTo(1);

        // 9 accepted (4.5 stars)
        UUID userAccept9 = UUID.randomUUID();
        int inserted9 = jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 9, ?, ?)
                """, UUID.randomUUID().toString(), ARTICLE_ID.toString(), userAccept9.toString(), now, now);
        assertThat(inserted9).isEqualTo(1);

        // Verify row count: 5 seeded + 2 new accepted = 7
        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE wiki_article_id = ?",
                Integer.class,
                ARTICLE_ID.toString()
        );
        assertThat(totalRows).isEqualTo(7);

        // Verify unique constraint: duplicate user insert rejected
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 8, ?, ?)
                """, UUID.randomUUID().toString(), ARTICLE_ID.toString(), userAccept3.toString(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Teardown cleanup
        jdbcTemplate.update("DELETE FROM wiki_articles WHERE id = ?", ARTICLE_ID.toString());
    }
}
