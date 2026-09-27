package com.universe.interaction.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V73 Migration Contract Test — Comment Foreign Keys for Hard Deletion")
class V73MigrationContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("V73 migration exists and updates foreign keys for true hard deletion")
    void v73MigrationExistsAndUpdatesForeignKeys() throws Exception {
        Path v73Path = MIGRATION_DIR.resolve("V73__update_interaction_comment_foreign_keys_for_hard_delete.sql");
        assertThat(Files.exists(v73Path)).as("V73 migration file must exist").isTrue();

        String sql = Files.readString(v73Path, StandardCharsets.UTF_8);
        assertThat(sql).contains("ALTER TABLE interaction_comments");
        assertThat(sql).contains("DROP FOREIGN KEY fk_interaction_comments_parent");
        assertThat(sql).contains("DROP FOREIGN KEY fk_interaction_comments_thread_root");
        assertThat(sql).contains("ON DELETE CASCADE");
        assertThat(sql).contains("ALTER TABLE interaction_reports");
        assertThat(sql).contains("DROP FOREIGN KEY fk_interaction_reports_comment");
    }

    @Test
    @DisplayName("V49, V50, V53 migrations remain untouched")
    void priorMigrationsRemainUntouched() throws Exception {
        Path v49Path = MIGRATION_DIR.resolve("V49__create_interaction_comments.sql");
        assertThat(Files.exists(v49Path)).as("V49 migration file must exist").isTrue();

        Path v50Path = MIGRATION_DIR.resolve("V50__add_comment_thread_root.sql");
        assertThat(Files.exists(v50Path)).as("V50 migration file must exist").isTrue();

        Path v53Path = MIGRATION_DIR.resolve("V53__create_interaction_reports.sql");
        assertThat(Files.exists(v53Path)).as("V53 migration file must exist").isTrue();
    }
}
