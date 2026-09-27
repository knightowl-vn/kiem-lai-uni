package com.universe.wiki.infrastructure.persistence.article;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Wiki Cover V61 Flyway Runtime Verification Tests")
class WikiCoverV61FlywayRuntimeVerificationTest {

    private static final String TEST_DATABASE_NAME = "kiemlai_wiki_cover_v61_test";
    private static final UUID ARTICLE_ID = UUID.fromString("61616161-6161-6161-6161-616161616161");
    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        TestDatabaseSupport.resetTestDatabase(TEST_DATABASE_NAME);
        this.dataSource = TestDatabaseSupport.createTestDataSource(TEST_DATABASE_NAME);
        this.jdbcTemplate = new JdbcTemplate(this.dataSource);
    }

    @Test
    @DisplayName("Kiểm chứng tiến trình migration V60 -> V61 trên schema MySQL cô lập")
    void shouldVerifyV61FlywayMigrationRuntime() {
        // 1. Migrate clean dedicated test database up to V60
        Flyway flyway60 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("60")
                .load();
        flyway60.migrate();

        // 2. Insert representative article under V60 (pre-V61 state, no focal position columns)
        Timestamp now = Timestamp.from(Instant.now());
        UUID coverAssetId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content, cover_media_asset_id,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, 'Bạch Ngưng Chi V61', 'bach-ngung-chi-v61', 'CHARACTER', 'PUBLISHED', 'Tóm tắt bài', '# Nội dung', ?,
                    ?, ?, ?, ?, ?, 1, 1)
                """,
                ARTICLE_ID.toString(), coverAssetId.toString(), ADMIN_ID.toString(), now, now, ADMIN_ID.toString(), now
        );

        // 3. Migrate to V61
        Flyway flyway61 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("61")
                .load();
        flyway61.migrate();

        // 4. Assert Flyway schema history for V61
        Integer successV61 = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '61'",
                Integer.class
        );
        assertThat(successV61).isEqualTo(1);

        // 5. Assert column definitions in information_schema
        for (String col : new String[]{"cover_position_x", "cover_position_y"}) {
            String dataType = jdbcTemplate.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'wiki_articles'
                      AND column_name = ?
                    """, String.class, TEST_DATABASE_NAME, col);
            assertThat(dataType).isEqualToIgnoringCase("tinyint");

            String columnType = jdbcTemplate.queryForObject("""
                    SELECT column_type FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'wiki_articles'
                      AND column_name = ?
                    """, String.class, TEST_DATABASE_NAME, col);
            assertThat(columnType.toLowerCase()).contains("unsigned");

            String isNullable = jdbcTemplate.queryForObject("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'wiki_articles'
                      AND column_name = ?
                    """, String.class, TEST_DATABASE_NAME, col);
            assertThat(isNullable).isEqualTo("NO");

            String defaultVal = jdbcTemplate.queryForObject("""
                    SELECT column_default FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'wiki_articles'
                      AND column_name = ?
                    """, String.class, TEST_DATABASE_NAME, col);
            assertThat(defaultVal).isEqualTo("50");
        }

        // 6. Assert existing pre-V60 row survived and received 50/50 default
        Integer posX = jdbcTemplate.queryForObject(
                "SELECT cover_position_x FROM wiki_articles WHERE id = ?",
                Integer.class,
                ARTICLE_ID.toString()
        );
        Integer posY = jdbcTemplate.queryForObject(
                "SELECT cover_position_y FROM wiki_articles WHERE id = ?",
                Integer.class,
                ARTICLE_ID.toString()
        );
        assertThat(posX).isEqualTo(50);
        assertThat(posY).isEqualTo(50);

        // 7. Assert 0 and 100 boundary coordinates are accepted
        int updatedBoundary = jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_position_x = 0, cover_position_y = 100 WHERE id = ?",
                ARTICLE_ID.toString()
        );
        assertThat(updatedBoundary).isEqualTo(1);

        int updatedBoundary2 = jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_position_x = 100, cover_position_y = 0 WHERE id = ?",
                ARTICLE_ID.toString()
        );
        assertThat(updatedBoundary2).isEqualTo(1);

        // 8. Assert values > 100 are rejected by CHECK constraint
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_position_x = 101 WHERE id = ?",
                ARTICLE_ID.toString()
        )).hasMessageContaining("chk_wiki_articles_cover_position_x");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_position_y = 101 WHERE id = ?",
                ARTICLE_ID.toString()
        )).hasMessageContaining("chk_wiki_articles_cover_position_y");

        // 9. Assert values < 0 are rejected (out of range for unsigned TINYINT)
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_position_x = -1 WHERE id = ?",
                ARTICLE_ID.toString()
        )).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_position_y = -1 WHERE id = ?",
                ARTICLE_ID.toString()
        )).isInstanceOf(Exception.class);
    }
}
