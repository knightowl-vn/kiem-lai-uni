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
@DisplayName("Wiki Contribution V67 Flyway Runtime Verification Tests")
class WikiContributionV67FlywayRuntimeVerificationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanV67BeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanV67BeforeFlyway() {
        try {
            javax.sql.DataSource ds = TestDatabaseSupport.createTestDataSource(TestDatabaseSupport.resolveDatabaseName());
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_workflow_events");
            String dbName = TestDatabaseSupport.resolveDatabaseName();
            Integer idxExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'wiki_contributions' AND index_name = 'idx_wiki_contributions_assignee'",
                    Integer.class,
                    dbName
            );
            if (idxExists != null && idxExists > 0) {
                jdbc.execute("ALTER TABLE wiki_contributions DROP INDEX idx_wiki_contributions_assignee");
            }
            Integer chkExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'wiki_contributions' AND constraint_name = 'chk_wiki_contributions_resolution_outcome'",
                    Integer.class,
                    dbName
            );
            if (chkExists != null && chkExists > 0) {
                jdbc.execute("ALTER TABLE wiki_contributions DROP CHECK chk_wiki_contributions_resolution_outcome");
            }
            for (String col : java.util.List.of("resolution_note", "resolved_by_user_id", "resolved_at", "resolved_article_content_version",
                    "assigned_to_user_id", "assigned_at", "review_started_by_user_id", "review_started_at", "review_started_article_content_version", "resolution_outcome")) {
                Integer exists = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_contributions' AND column_name = ?",
                        Integer.class,
                        dbName,
                        col
                );
                if (exists != null && exists > 0) {
                    jdbc.execute("ALTER TABLE wiki_contributions DROP COLUMN " + col);
                }
            }
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
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version IN ('67', '68')");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clean V67 test baseline before Flyway in WikiContributionV67FlywayRuntimeVerificationTest", e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify V67 migration is recorded as successful in flyway_schema_history")
    void shouldVerifyV67AppliedInFlywayHistory() {
        Integer success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '67'",
                Integer.class
        );
        assertThat(success).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify newly added columns in wiki_contributions table are present and nullable")
    void shouldVerifyAddedColumns() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        List<String> columnNames = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name IN ('resolution_note', 'resolved_by_user_id', 'resolved_at', 'resolved_article_content_version')
                ORDER BY ordinal_position
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );

        assertThat(columnNames).containsExactlyInAnyOrder(
                "resolution_note",
                "resolved_by_user_id",
                "resolved_at",
                "resolved_article_content_version"
        );

        // Verify all 4 new columns are nullable
        List<String> nonNullableAddedColumns = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name IN ('resolution_note', 'resolved_by_user_id', 'resolved_at', 'resolved_article_content_version')
                  AND is_nullable = 'NO'
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );
        assertThat(nonNullableAddedColumns).isEmpty();

        // Verify resolution_note length is 2000
        Long noteLength = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name = 'resolution_note'
                """,
                Long.class,
                dbName
        );
        assertThat(noteLength).isEqualTo(2000L);

        // Verify resolved_by_user_id length is 36
        Long userIdLength = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name = 'resolved_by_user_id'
                """,
                Long.class,
                dbName
        );
        assertThat(userIdLength).isEqualTo(36L);
    }
}
