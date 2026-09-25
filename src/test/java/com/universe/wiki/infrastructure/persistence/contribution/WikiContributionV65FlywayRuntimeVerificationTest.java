package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@DisplayName("Wiki Contribution V65 Flyway Runtime Verification Tests")
class WikiContributionV65FlywayRuntimeVerificationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanV65BeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanV65BeforeFlyway() {
        try {
            javax.sql.DataSource ds = TestDatabaseSupport.createTestDataSource(TestDatabaseSupport.resolveDatabaseName());
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version IN ('65', '66', '67', '68')");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_workflow_events");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_sources");
            jdbc.execute("DROP TABLE IF EXISTS wiki_contributions");
            String dbName = TestDatabaseSupport.resolveDatabaseName();
            Integer revIdxExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'wiki_article_revisions' AND index_name = 'idx_wiki_article_revisions_source_contribution'",
                    Integer.class,
                    dbName
            );
            if (revIdxExists != null && revIdxExists > 0) {
                jdbc.execute("ALTER TABLE wiki_article_revisions DROP INDEX idx_wiki_article_revisions_source_contribution");
            }
            Integer revColExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_article_revisions' AND column_name = 'source_contribution_id'",
                    Integer.class,
                    dbName
            );
            if (revColExists != null && revColExists > 0) {
                jdbc.execute("ALTER TABLE wiki_article_revisions DROP COLUMN source_contribution_id");
            }
        } catch (Exception ignored) {
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify V65 migration is recorded as successful in flyway_schema_history")
    void shouldVerifyV65AppliedInFlywayHistory() {
        Integer success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '65'",
                Integer.class
        );
        assertThat(success).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify wiki_contributions table schema in information_schema: all 18 columns match specification")
    void shouldVerifyTableColumns() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        List<String> columnNames = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                ORDER BY ordinal_position
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );

        assertThat(columnNames).startsWith(
                "id",
                "article_id",
                "article_type_snapshot",
                "article_title_snapshot",
                "article_slug_snapshot",
                "article_content_version",
                "submitted_by_user_id",
                "context_type",
                "contribution_type",
                "message",
                "selected_text",
                "selected_prefix",
                "selected_suffix",
                "selected_heading_anchor",
                "status",
                "version",
                "created_at",
                "updated_at"
        );
    }

    @Test
    @DisplayName("Verify Primary Key and Secondary Indexes on wiki_contributions")
    void shouldVerifyIndexes() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        String pkColumn = jdbcTemplate.queryForObject(
                """
                SELECT column_name FROM information_schema.key_column_usage
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND constraint_name = 'PRIMARY'
                """,
                String.class,
                dbName
        );
        assertThat(pkColumn).isEqualTo("id");

        List<String> indexes = jdbcTemplate.query(
                """
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                """,
                (rs, rowNum) -> rs.getString("index_name"),
                dbName
        );

        assertThat(indexes).contains(
                "PRIMARY",
                "idx_wiki_contributions_status_created_id",
                "idx_wiki_contributions_article_created_id",
                "idx_wiki_contributions_user_created_id"
        );
    }

    @Test
    @DisplayName("Verify zero Foreign Keys on wiki_contributions table")
    void shouldVerifyNoForeignKeys() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        Integer fkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND constraint_type = 'FOREIGN KEY'
                """,
                Integer.class,
                dbName
        );
        assertThat(fkCount).isZero();
    }

    @Test
    @DisplayName("Verify exact snapshot column types and character maximum lengths in MySQL information_schema")
    void shouldVerifySnapshotColumnLengths() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        Long typeLen = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name = 'article_type_snapshot'
                """,
                Long.class,
                dbName
        );
        assertThat(typeLen).isEqualTo(30L);

        Long titleLen = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name = 'article_title_snapshot'
                """,
                Long.class,
                dbName
        );
        assertThat(titleLen).isEqualTo(200L);

        Long slugLen = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name = 'article_slug_snapshot'
                """,
                Long.class,
                dbName
        );
        assertThat(slugLen).isEqualTo(180L);
    }
}
