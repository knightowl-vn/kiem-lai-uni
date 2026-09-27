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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V72 Flyway Runtime Migration Verification Tests")
class V72FlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_interaction_v72_test";
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
    @DisplayName("V72 applies with SUCCESS state")
    void shouldApplyV72Successfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        MigrationInfo v72Info = null;
        for (MigrationInfo mi : info) {
            if ("72".equals(mi.getVersion().getVersion())) {
                v72Info = mi;
                break;
            }
        }

        assertThat(v72Info).isNotNull();
        assertThat(v72Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v72Info.getDescription()).isEqualTo("add interaction comment author activity index");
    }

    @Test
    @DisplayName("idx_interaction_comments_author_status_created_id exists with correct column sequence (author_user_id, status, created_at, id)")
    void shouldVerifyIndexColumnsInOrder() {
        List<String> columns = jdbc.query(
                """
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'interaction_comments'
                  AND index_name = 'idx_interaction_comments_author_status_created_id'
                ORDER BY seq_in_index ASC
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                DB_NAME
        );

        assertThat(columns).containsExactly(
                "author_user_id",
                "status",
                "created_at",
                "id"
        );
    }
}
