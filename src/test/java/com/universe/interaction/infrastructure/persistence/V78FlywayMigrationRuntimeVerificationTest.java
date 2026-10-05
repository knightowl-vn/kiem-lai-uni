package com.universe.interaction.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V78 Flyway Migration Runtime Verification Test")
class V78FlywayMigrationRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_v78_migration_test";

    @Test
    @DisplayName("Should successfully migrate from V77 to V78 and backfill report data")
    void shouldMigrateFromV77ToV78AndBackfillData() {
        TestDatabaseSupport.resetTestDatabase(DB_NAME);
        DataSource dataSource = TestDatabaseSupport.createTestDataSource(DB_NAME);

        // 1. Migrate up to V77
        Flyway flywayV77 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("77")
                .load();
        flywayV77.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 2. Insert pre-V78 comment and report using V77 schema
        UUID commentId = UUID.randomUUID();
        UUID targetChapterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        String originalSnapshot = "Pre-V78 comment body snapshot for moderation audit";

        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Active comment', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetChapterId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO interaction_reports (id, comment_id, reporter_user_id, reason, description, reported_body_snapshot, status, created_at, resolved_by_user_id, resolved_at) " +
                        "VALUES (?, ?, ?, 'SPAM', 'V77 report test', ?, 'PENDING', ?, NULL, NULL)",
                reportId.toString(), commentId.toString(), reporterId.toString(), originalSnapshot, Timestamp.from(now)
        );

        // 3. Migrate from V77 to V78
        Flyway flywayV78 = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("78")
                .load();
        flywayV78.migrate();

        MigrationInfo v78Info = flywayV78.info().current();
        assertThat(v78Info).isNotNull();
        assertThat(v78Info.getVersion().getVersion()).isEqualTo("78");
        assertThat(v78Info.getState()).isEqualTo(MigrationState.SUCCESS);

        // 4. Verify backfill of report columns
        Map<String, Object> reportRow = jdbc.queryForMap(
                "SELECT id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, pending_slot " +
                        "FROM interaction_reports WHERE id = ?",
                reportId.toString()
        );

        assertThat(reportRow.get("id")).isEqualTo(reportId.toString());
        assertThat(reportRow.get("target_type")).isEqualTo("COMMENT");
        assertThat(reportRow.get("target_id")).isEqualTo(commentId.toString());
        assertThat(reportRow.get("reporter_user_id")).isEqualTo(reporterId.toString());
        assertThat(reportRow.get("reason")).isEqualTo("SPAM");
        assertThat(reportRow.get("description")).isEqualTo("V77 report test");
        assertThat(reportRow.get("content_snapshot")).isEqualTo(originalSnapshot);
        assertThat(reportRow.get("status")).isEqualTo("PENDING");
        assertThat(reportRow.get("pending_slot")).isEqualTo(1);

        // 5. Verify insertion of COMMUNITY_POST in interaction_comments
        UUID communityPostId = UUID.randomUUID();
        UUID communityCommentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'COMMUNITY_POST', ?, ?, NULL, NULL, 'Community post comment', 'ACTIVE', ?, ?, NULL)",
                communityCommentId.toString(), communityPostId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 6. Verify insertion of COMMUNITY_POST in interaction_reactions
        UUID reactionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reactions (id, target_type, target_id, user_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, 'COMMUNITY_POST', ?, ?, 'LIKE', ?, ?)",
                reactionId.toString(), communityPostId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 7. Verify insertion of COMMUNITY_POST in interaction_reports
        UUID postReportId = UUID.randomUUID();
        UUID otherReporterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reports (id, target_type, target_id, reporter_user_id, reason, description, content_snapshot, status, created_at) " +
                        "VALUES (?, 'COMMUNITY_POST', ?, ?, 'HARASSMENT', 'Inappropriate post', 'Post caption snapshot', 'PENDING', ?)",
                postReportId.toString(), communityPostId.toString(), otherReporterId.toString(), Timestamp.from(now)
        );

        Map<String, Object> postReportRow = jdbc.queryForMap(
                "SELECT id, target_type, target_id, content_snapshot FROM interaction_reports WHERE id = ?",
                postReportId.toString()
        );
        assertThat(postReportRow.get("target_type")).isEqualTo("COMMUNITY_POST");
        assertThat(postReportRow.get("target_id")).isEqualTo(communityPostId.toString());
        assertThat(postReportRow.get("content_snapshot")).isEqualTo("Post caption snapshot");

        // 8. Verify idx_interaction_comments_target_id index exists
        Integer indexCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_schema = ? AND table_name = 'interaction_comments' AND index_name = 'idx_interaction_comments_target_id'",
                Integer.class,
                DB_NAME
        );
        assertThat(indexCount).isGreaterThan(0);
    }
}
