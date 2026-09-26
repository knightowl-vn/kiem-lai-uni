package com.universe.interaction.infrastructure.persistence.reaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Reaction V71 Migration Script Tests")
class ReactionV71MigrationScriptTest {

    @Test
    @DisplayName("Verify V71 migration script updates reaction_type check constraint to include LIKE")
    void shouldVerifyV71MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V71__allow_like_reaction_type.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Drop previous constraint
        assertThat(sql).contains("ALTER TABLE interaction_reactions");
        assertThat(sql).contains("DROP CHECK chk_interaction_reactions_type");

        // Add extended constraint with LIKE
        assertThat(sql).contains("ADD CONSTRAINT chk_interaction_reactions_type");
        assertThat(sql).contains("CHECK (reaction_type IN ('LIKE', 'LOVE', 'FIRE', 'HAHA', 'SAD'))");
    }
}
