package com.universe.wiki.infrastructure.persistence.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Contribution V66 Migration Script Tests")
class WikiContributionV66MigrationScriptTest {

    @Test
    @DisplayName("Verify V66 migration script creates wiki_contribution_sources table with correct schema, constraints and foreign key")
    void shouldVerifyV66MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V66__create_wiki_contribution_sources.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Table creation
        assertThat(sql).contains("CREATE TABLE wiki_contribution_sources");

        // Primary key
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("PRIMARY KEY (id)");

        // Columns
        assertThat(sql).contains("contribution_id CHAR(36) NOT NULL");
        assertThat(sql).contains("source_order TINYINT NOT NULL");
        assertThat(sql).contains("source_type VARCHAR(20) NOT NULL");
        assertThat(sql).contains("url VARCHAR(2000) NOT NULL");
        assertThat(sql).contains("created_at DATETIME(6) NOT NULL");

        // Foreign key to wiki_contributions with ON DELETE CASCADE
        assertThat(sql).contains("CONSTRAINT fk_wiki_contribution_sources_contribution");
        assertThat(sql).contains("FOREIGN KEY (contribution_id)");
        assertThat(sql).contains("REFERENCES wiki_contributions (id)");
        assertThat(sql).contains("ON DELETE CASCADE");

        // Unique constraint on (contribution_id, source_order)
        assertThat(sql).contains("CONSTRAINT uq_wiki_contribution_sources_contribution_order");
        assertThat(sql).contains("UNIQUE (contribution_id, source_order)");

        // Check constraints
        assertThat(sql).contains("CONSTRAINT chk_wiki_contribution_sources_order");
        assertThat(sql).contains("CHECK (source_order BETWEEN 0 AND 4)");

        assertThat(sql).contains("CONSTRAINT chk_wiki_contribution_sources_type");
        assertThat(sql).contains("CHECK (source_type IN ('INTERNAL', 'EXTERNAL'))");
    }

    @Test
    @DisplayName("Verify milestone migrations exist in classpath")
    void shouldVerifyMilestoneMigrationsExist() {
        assertThat(new ClassPathResource("db/migration/V65__create_wiki_contributions.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V64__add_wiki_cover_orphan_deleting_state.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V63__create_wiki_cover_orphans.sql").exists()).isTrue();
        assertThat(new ClassPathResource("db/migration/V11__create_wiki_articles.sql").exists()).isTrue();
    }
}
