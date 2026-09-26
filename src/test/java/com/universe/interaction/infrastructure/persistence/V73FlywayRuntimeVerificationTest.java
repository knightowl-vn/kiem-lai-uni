package com.universe.interaction.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V73 Flyway Runtime Migration Verification Tests")
class V73FlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_interaction_v73_test";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpDatabase() {
        TestDatabaseSupport.resetTestDatabase(DB_NAME);
        dataSource = TestDatabaseSupport.createTestDataSource(DB_NAME);
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("V73 applies with SUCCESS state")
    void shouldApplyV73Successfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        MigrationInfo v73Info = null;
        for (MigrationInfo mi : info) {
            if ("73".equals(mi.getVersion().getVersion())) {
                v73Info = mi;
                break;
            }
        }

        assertThat(v73Info).isNotNull();
        assertThat(v73Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v73Info.getDescription()).isEqualTo("update interaction comment foreign keys for hard delete");
    }

    @Test
    @DisplayName("interaction_comments foreign keys have ON DELETE CASCADE for parent and thread_root")
    void shouldVerifyCommentsCascadeDeleteConstraints() {
        List<Map<String, Object>> fkRules = jdbc.queryForList(
                """
                SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE
                FROM information_schema.REFERENTIAL_CONSTRAINTS rc
                WHERE rc.CONSTRAINT_SCHEMA = ?
                  AND rc.TABLE_NAME = 'interaction_comments'
                ORDER BY rc.CONSTRAINT_NAME ASC
                """,
                DB_NAME
        );

        assertThat(fkRules).hasSize(2);
        for (Map<String, Object> rule : fkRules) {
            assertThat(rule.get("DELETE_RULE")).isEqualTo("CASCADE");
        }
    }

    @Test
    @DisplayName("interaction_reports no longer has restrictive FK to interaction_comments")
    void shouldVerifyReportsNoLongerRestrictsCommentDeletion() {
        List<Map<String, Object>> fkRules = jdbc.queryForList(
                """
                SELECT rc.CONSTRAINT_NAME, rc.DELETE_RULE
                FROM information_schema.REFERENTIAL_CONSTRAINTS rc
                WHERE rc.CONSTRAINT_SCHEMA = ?
                  AND rc.TABLE_NAME = 'interaction_reports'
                """,
                DB_NAME
        );

        assertThat(fkRules).isEmpty();
    }
}
