package com.universe.interaction.infrastructure.persistence.reaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reaction V70 Migration Script Tests")
class ReactionV70MigrationScriptTest {

    @Test
    @DisplayName("Verify V70 migration script creates interaction_reactions table with correct schema, constraints, and indexes")
    void shouldVerifyV70MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V70__create_interaction_reactions.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Table creation
        assertThat(sql).contains("CREATE TABLE interaction_reactions");

        // Primary key column
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("CONSTRAINT pk_interaction_reactions");
        assertThat(sql).contains("PRIMARY KEY (id)");

        // Columns
        assertThat(sql).contains("user_id CHAR(36) NOT NULL");
        assertThat(sql).contains("target_type VARCHAR(32) NOT NULL");
        assertThat(sql).contains("target_id CHAR(36) NOT NULL");
        assertThat(sql).contains("reaction_type VARCHAR(20) NOT NULL");
        assertThat(sql).contains("created_at DATETIME(6) NOT NULL");
        assertThat(sql).contains("updated_at DATETIME(6) NOT NULL");

        // Unique constraint
        assertThat(sql).contains("CONSTRAINT uq_interaction_reactions_user_target");
        assertThat(sql).contains("UNIQUE (user_id, target_type, target_id)");

        // Check constraints
        assertThat(sql).contains("CONSTRAINT chk_interaction_reactions_target_type");
        assertThat(sql).contains("CHECK (target_type IN ('NOVEL_CHAPTER', 'COMMENT', 'DONGHUA_EPISODE'))");

        assertThat(sql).contains("CONSTRAINT chk_interaction_reactions_type");
        assertThat(sql).contains("CHECK (reaction_type IN ('LOVE', 'FIRE', 'HAHA', 'SAD'))");

        assertThat(sql).contains("CONSTRAINT chk_interaction_reactions_updated_at");
        assertThat(sql).contains("CHECK (updated_at >= created_at)");

        // Secondary index
        assertThat(sql).contains("CREATE INDEX idx_interaction_reactions_target_summary");
        assertThat(sql).contains("ON interaction_reactions (target_type, target_id, reaction_type)");

        // Engine and collation
        assertThat(sql).contains("ENGINE = InnoDB");
        assertThat(sql).contains("DEFAULT CHARACTER SET = utf8mb4");
        assertThat(sql).contains("COLLATE = utf8mb4_unicode_ci");

        // Bounded-context independence: No foreign key to novel_chapters, interaction_comments, or identity_users
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }
}
