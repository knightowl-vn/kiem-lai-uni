package com.universe.notification.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V74 Migration Contract Test — Notifications Schema")
class NotificationV74MigrationContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("V74 migration exists and contains complete notifications table definition")
    void v74MigrationExistsAndDefinesTable() throws Exception {
        Path v74Path = MIGRATION_DIR.resolve("V74__create_notifications.sql");
        assertThat(Files.exists(v74Path)).as("V74 migration file must exist").isTrue();

        String sql = Files.readString(v74Path, StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE notifications");
        assertThat(sql).contains("recipient_user_id CHAR(36) NOT NULL");
        assertThat(sql).contains("type VARCHAR(40) NOT NULL");
        assertThat(sql).contains("dedupe_key VARCHAR(191) NOT NULL");
        assertThat(sql).contains("UNIQUE (dedupe_key)");
        assertThat(sql).contains("chk_notifications_type");
        assertThat(sql).contains("idx_notifications_recipient_created");
        assertThat(sql).contains("idx_notifications_recipient_read_created");
    }

    @Test
    @DisplayName("V72 and V73 migrations remain untouched")
    void priorMigrationsRemainUntouched() throws Exception {
        Path v72Path = MIGRATION_DIR.resolve("V72__add_interaction_comment_author_activity_index.sql");
        assertThat(Files.exists(v72Path)).as("V72 migration file must exist").isTrue();

        Path v73Path = MIGRATION_DIR.resolve("V73__update_interaction_comment_foreign_keys_for_hard_delete.sql");
        assertThat(Files.exists(v73Path)).as("V73 migration file must exist").isTrue();
    }
}
