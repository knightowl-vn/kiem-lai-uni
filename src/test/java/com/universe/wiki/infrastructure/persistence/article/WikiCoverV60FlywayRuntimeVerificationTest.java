package com.universe.wiki.infrastructure.persistence.article;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Cover V60 Flyway Runtime Verification Tests")
class WikiCoverV60FlywayRuntimeVerificationTest {

    private static final String TEST_DATABASE_NAME = "kiemlai_wiki_cover_v60_test";
    private static final UUID ARTICLE_ID = UUID.fromString("60606060-6060-6060-6060-606060606060");
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
    @DisplayName("Kiểm chứng tiến trình migration V59 -> V60 trên schema MySQL cô lập")
    void shouldVerifyV60FlywayMigrationRuntime() {
        // 1. Migrate clean dedicated test database up to V59
        Flyway flyway59 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("59")
                .load();
        flyway59.migrate();

        // 2. Insert representative article under V59 (pre-V60 state)
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, 'Bạch Ngưng Chi V60', 'bach-ngung-chi-v60', 'CHARACTER', 'PUBLISHED', 'Tóm tắt bài', '# Nội dung',
                    ?, ?, ?, ?, ?, 1, 1)
                """,
                ARTICLE_ID.toString(), ADMIN_ID.toString(), now, now, ADMIN_ID.toString(), now
        );

        // 3. Migrate to V60
        Flyway flyway60 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("60")
                .load();
        flyway60.migrate();

        // 4. Assert Flyway schema history for V60
        Integer successV60 = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '60'",
                Integer.class
        );
        assertThat(successV60).isEqualTo(1);

        // 5. Assert column definition in information_schema
        String dataType = jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_articles'
                  AND column_name = 'cover_media_asset_id'
                """, String.class, TEST_DATABASE_NAME);
        assertThat(dataType).isEqualToIgnoringCase("char");

        Integer charLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_articles'
                  AND column_name = 'cover_media_asset_id'
                """, Integer.class, TEST_DATABASE_NAME);
        assertThat(charLength).isEqualTo(36);

        String isNullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_articles'
                  AND column_name = 'cover_media_asset_id'
                """, String.class, TEST_DATABASE_NAME);
        assertThat(isNullable).isEqualTo("YES");

        // 6. Assert index existence
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'wiki_articles'
                  AND index_name = 'idx_wiki_articles_cover_media_asset_id'
                """, Integer.class, TEST_DATABASE_NAME);
        assertThat(indexCount).isGreaterThan(0);

        // 7. Assert bounded context isolation: NO foreign key referencing media_assets
        Integer fkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE table_schema = ?
                  AND table_name = 'wiki_articles'
                  AND referenced_table_name = 'media_assets'
                """, Integer.class, TEST_DATABASE_NAME);
        assertThat(fkCount).isEqualTo(0);

        // 8. Assert existing row survived with cover_media_asset_id = NULL
        String initialCover = jdbcTemplate.queryForObject(
                "SELECT cover_media_asset_id FROM wiki_articles WHERE id = ?",
                String.class,
                ARTICLE_ID.toString()
        );
        assertThat(initialCover).isNull();

        // 9. Assert valid UUID round-trips
        UUID testCoverId = UUID.randomUUID();
        int updated = jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_media_asset_id = ? WHERE id = ?",
                testCoverId.toString(),
                ARTICLE_ID.toString()
        );
        assertThat(updated).isEqualTo(1);

        String storedCover = jdbcTemplate.queryForObject(
                "SELECT cover_media_asset_id FROM wiki_articles WHERE id = ?",
                String.class,
                ARTICLE_ID.toString()
        );
        assertThat(storedCover).isEqualTo(testCoverId.toString());

        // 10. Assert NULL value round-trips (removal)
        jdbcTemplate.update(
                "UPDATE wiki_articles SET cover_media_asset_id = NULL WHERE id = ?",
                ARTICLE_ID.toString()
        );
        String clearedCover = jdbcTemplate.queryForObject(
                "SELECT cover_media_asset_id FROM wiki_articles WHERE id = ?",
                String.class,
                ARTICLE_ID.toString()
        );
        assertThat(clearedCover).isNull();
    }
}
