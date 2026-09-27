package com.universe.notification.infrastructure.persistence;

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

@DisplayName("V74 Flyway Runtime Migration Verification Tests")
class NotificationV74FlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_notification_v74_test";
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
    @DisplayName("V74 applies with SUCCESS state")
    void shouldApplyV74Successfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        MigrationInfo v74Info = null;
        for (MigrationInfo mi : info) {
            if ("74".equals(mi.getVersion().getVersion())) {
                v74Info = mi;
                break;
            }
        }

        assertThat(v74Info).isNotNull();
        assertThat(v74Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v74Info.getDescription()).isEqualTo("create notifications");
    }

    @Test
    @DisplayName("notifications table exists with all required columns and nullability")
    void shouldVerifyNotificationsTableStructure() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                """
                SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = 'notifications'
                ORDER BY ORDINAL_POSITION ASC
                """,
                DB_NAME
        );

        assertThat(columns).isNotEmpty();

        Map<String, Map<String, Object>> colMap = columns.stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> (String) c.get("COLUMN_NAME"),
                        c -> c
                ));

        assertThat(colMap).containsKeys(
                "id",
                "recipient_user_id",
                "type",
                "actor_user_id",
                "actor_display_name_snapshot",
                "target_type",
                "target_id",
                "target_title_snapshot",
                "comment_id",
                "thread_root_id",
                "detail_snapshot",
                "dedupe_key",
                "read_at",
                "created_at"
        );

        assertThat(colMap.get("id").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(colMap.get("recipient_user_id").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(colMap.get("type").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(colMap.get("dedupe_key").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(colMap.get("created_at").get("IS_NULLABLE")).isEqualTo("NO");
        assertThat(colMap.get("read_at").get("IS_NULLABLE")).isEqualTo("YES");
    }

    @Test
    @DisplayName("notifications table has indexes on dedupe_key and recipient composite keys")
    void shouldVerifyNotificationsIndexes() {
        List<Map<String, Object>> indexes = jdbc.queryForList(
                """
                SELECT DISTINCT INDEX_NAME, NON_UNIQUE
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = 'notifications'
                """,
                DB_NAME
        );

        Map<String, Object> indexMap = indexes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        idx -> (String) idx.get("INDEX_NAME"),
                        idx -> idx.get("NON_UNIQUE")
                ));

        assertThat(indexMap).containsKey("PRIMARY");
        assertThat(indexMap).containsKey("uq_notifications_dedupe_key");
        assertThat(indexMap.get("uq_notifications_dedupe_key").toString()).isEqualTo("0"); // Unique
        assertThat(indexMap).containsKey("idx_notifications_recipient_created");
        assertThat(indexMap).containsKey("idx_notifications_recipient_read_created");
    }
}
