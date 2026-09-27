package com.universe.wiki.infrastructure.persistence.contribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Wiki Contribution V69 Migration Script Tests")
class WikiContributionV69MigrationScriptTest {

    @Test
    @DisplayName("Xác thực migration script V69 tạo bảng wiki_contribution_credits với đầy đủ ràng buộc")
    void shouldVerifyV69MigrationScript() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V69__add_wiki_contribution_credit_attribution.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Table creation
        assertThat(sql).contains("CREATE TABLE wiki_contribution_credits");
        assertThat(sql).contains("id CHAR(36) NOT NULL");
        assertThat(sql).contains("contribution_id CHAR(36) NOT NULL");
        assertThat(sql).contains("credit_status VARCHAR(20) NOT NULL");
        assertThat(sql).contains("credited_by_user_id CHAR(36) NOT NULL");
        assertThat(sql).contains("credited_at DATETIME(6) NOT NULL");
        assertThat(sql).contains("credit_note VARCHAR(1000) NULL");
        assertThat(sql).contains("revoked_by_user_id CHAR(36) NULL");
        assertThat(sql).contains("revoked_at DATETIME(6) NULL");
        assertThat(sql).contains("revocation_reason VARCHAR(1000) NULL");
        assertThat(sql).contains("persistence_version BIGINT NOT NULL DEFAULT 0");

        // Primary key & constraints
        assertThat(sql).contains("CONSTRAINT pk_wiki_contribution_credits");
        assertThat(sql).contains("PRIMARY KEY (id)");
        assertThat(sql).contains("CONSTRAINT uq_wiki_contribution_credits_contribution");
        assertThat(sql).contains("UNIQUE (contribution_id)");
        assertThat(sql).contains("CONSTRAINT fk_wiki_contribution_credits_contribution");
        assertThat(sql).contains("REFERENCES wiki_contributions (id)");
        assertThat(sql).contains("ON DELETE RESTRICT");
        assertThat(sql).contains("CONSTRAINT chk_wiki_contribution_credits_status");
        assertThat(sql).contains("CHECK (credit_status IN ('ACTIVE', 'REVOKED'))");
        assertThat(sql).contains("CONSTRAINT chk_wiki_contribution_credits_status_consistency");
        assertThat(sql).contains("CONSTRAINT chk_wiki_contribution_credits_persistence_version");

        // Ensure no redundant or speculative fields
        assertThat(sql).doesNotContain("article_id");
        assertThat(sql).doesNotContain("contributor_user_id");

        // Ensure no cross-context foreign keys to identity
        assertThat(sql).doesNotContain("FOREIGN KEY (credited_by_user_id)");
        assertThat(sql).doesNotContain("FOREIGN KEY (revoked_by_user_id)");
        assertThat(sql).doesNotContain("REFERENCES identity_users");
    }
}
