package com.universe.interaction.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V72 Migration Contract Test — Author Activity Index")
class V72MigrationContractTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("V72 migration exists and correctly defines the author activity index")
    void v72MigrationExistsAndDeclaresAuthorActivityIndex() throws Exception {
        Path v72Path = MIGRATION_DIR.resolve("V72__add_interaction_comment_author_activity_index.sql");
        assertThat(Files.exists(v72Path)).as("V72 migration file must exist").isTrue();

        String sql = Files.readString(v72Path, StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE INDEX idx_interaction_comments_author_status_created_id");
        assertThat(sql).contains("ON interaction_comments (author_user_id, status, created_at, id);");
    }

    @Test
    @DisplayName("V49 migration remains untouched and preserves its original index definitions")
    void v49MigrationRemainsUntouched() throws Exception {
        Path v49Path = MIGRATION_DIR.resolve("V49__create_interaction_comments.sql");
        assertThat(Files.exists(v49Path)).as("V49 migration file must exist").isTrue();

        String sql = Files.readString(v49Path, StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE interaction_comments");
        assertThat(sql).contains("CREATE INDEX idx_interaction_comments_target_parent_created_id");
        assertThat(sql).contains("CREATE INDEX idx_interaction_comments_parent_created_id");
        assertThat(sql).doesNotContain("idx_interaction_comments_author_status_created_id");
    }
}
