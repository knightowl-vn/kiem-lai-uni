package com.universe.media.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Media V91 Flyway Migration Script Static Audit Test")
class MediaV91MigrationScriptTest {

    @Test
    @DisplayName("Verify V91 migration script exists and properly extends cleanup_status CHECK constraint via a single atomic ALTER TABLE")
    void shouldVerifyV91MigrationScriptInvariants() throws Exception {
        ClassPathResource resource = new ClassPathResource("db/migration/V91__allow_quarantined_media_upload_session_cleanup_status.sql");
        assertThat(resource.exists()).isTrue();

        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 1. Single ALTER TABLE statement (avoids separate implicit-commit DDL steps)
        String executableSql = sql.replaceAll("(?m)--.*$", "").replaceAll("/\\*[\\s\\S]*?\\*/", "").trim();
        int alterTableCount = executableSql.split("(?i)ALTER\\s+TABLE").length - 1;
        assertThat(alterTableCount).isEqualTo(1);

        String normalizedSql = executableSql.replaceAll("\\s+", " ").trim();
        assertThat(normalizedSql).contains("ALTER TABLE media_upload_sessions DROP CHECK chk_media_upload_sessions_cleanup_status, ADD CONSTRAINT chk_media_upload_sessions_cleanup_status");

        // 2. Exact constraint name preserved
        assertThat(sql).contains("chk_media_upload_sessions_cleanup_status");

        // 3. All six approved cleanup status enum values present
        assertThat(sql).contains("'NONE'");
        assertThat(sql).contains("'PENDING'");
        assertThat(sql).contains("'IN_PROGRESS'");
        assertThat(sql).contains("'COMPLETED'");
        assertThat(sql).contains("'FAILED'");
        assertThat(sql).contains("'QUARANTINED'");

        // 4. Verify no unrelated table modifications or data deletions
        assertThat(sql).doesNotContain("DROP TABLE");
        assertThat(sql).doesNotContain("DELETE FROM");
        assertThat(sql).doesNotContain("UPDATE ");
        assertThat(sql).doesNotContain("TRUNCATE");
        assertThat(sql).doesNotContain("CREATE TABLE");
        assertThat(sql).doesNotContain("DROP COLUMN");
        assertThat(sql).doesNotContain("ADD COLUMN");
    }
}
