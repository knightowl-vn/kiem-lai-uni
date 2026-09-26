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
@DisplayName("Wiki Contribution V68 Flyway Runtime Verification Tests")
class WikiContributionV68FlywayRuntimeVerificationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanV68BeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanV68BeforeFlyway() {
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
            for (String col : java.util.List.of("assigned_to_user_id", "assigned_at", "review_started_by_user_id", "review_started_at", "review_started_article_content_version", "resolution_outcome")) {
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
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version = '68'");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clean V68 test baseline before Flyway in WikiContributionV68FlywayRuntimeVerificationTest", e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify V68 migration is recorded as successful in flyway_schema_history")
    void shouldVerifyV68AppliedInFlywayHistory() {
        Integer success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '68'",
                Integer.class
        );
        assertThat(success).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify newly added columns in wiki_contributions table are present and nullable")
    void shouldVerifyContributionAccountabilityColumns() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        List<String> columnNames = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contributions'
                  AND column_name IN ('assigned_to_user_id', 'assigned_at', 'review_started_by_user_id', 'review_started_at', 'review_started_article_content_version', 'resolution_outcome')
                ORDER BY ordinal_position
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );

        assertThat(columnNames).containsExactlyInAnyOrder(
                "assigned_to_user_id",
                "assigned_at",
                "review_started_by_user_id",
                "review_started_at",
                "review_started_article_content_version",
                "resolution_outcome"
        );
    }

    @Test
    @DisplayName("Verify source_contribution_id in wiki_article_revisions is present and indexed")
    void shouldVerifyArticleRevisionLinkageColumn() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_article_revisions'
                  AND column_name = 'source_contribution_id'
                """,
                Integer.class,
                dbName
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify wiki_contribution_workflow_events table is present with correct schema")
    void shouldVerifyWorkflowEventsTable() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        List<String> eventColumns = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_workflow_events'
                ORDER BY ordinal_position
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );

        assertThat(eventColumns).contains(
                "id",
                "contribution_id",
                "event_type",
                "actor_user_id",
                "target_user_id",
                "from_status",
                "to_status",
                "article_content_version",
                "resolution_outcome",
                "note",
                "created_at"
        );
    }
}
