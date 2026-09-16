package com.universe.interaction.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentFlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_interaction_comments_test";
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
    @DisplayName("1. Migration V49 execution and table structure verification")
    void shouldVerifyMigrationV49AndTableStructure() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(49);

        MigrationInfo v49Info = null;
        for (MigrationInfo mi : info) {
            if ("49".equals(mi.getVersion().getVersion())) {
                v49Info = mi;
                break;
            }
        }

        assertThat(v49Info).isNotNull();
        assertThat(v49Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v49Info.getDescription()).isEqualTo("create interaction comments");

        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'interaction_comments'",
                Integer.class,
                DB_NAME
        );
        assertThat(tableCount).isEqualTo(1);

        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'interaction_comments'",
                String.class,
                DB_NAME
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "target_type",
                "target_id",
                "author_user_id",
                "parent_comment_id",
                "body",
                "status",
                "created_at",
                "updated_at",
                "deleted_at"
        );
    }

    @Test
    @DisplayName("2. Basic storage: insert and retrieve ACTIVE root and reply rows")
    void shouldInsertAndRetrieveActiveRootAndReplyRows() {
        UUID rootId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        UUID replyAuthorId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Insert ACTIVE root row
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Root comment body', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Insert ACTIVE reply row
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, 'Reply comment body', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), replyAuthorId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        String rootBody = jdbc.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                rootId.toString()
        );
        assertThat(rootBody).isEqualTo("Root comment body");

        String replyParentId = jdbc.queryForObject(
                "SELECT parent_comment_id FROM interaction_comments WHERE id = ?",
                String.class,
                replyId.toString()
        );
        assertThat(replyParentId).isEqualTo(rootId.toString());
    }

    @Test
    @DisplayName("3. Foreign Key enforcement: reject invalid parent and prevent cascade delete")
    void shouldEnforceSelfReferentialForeignKeyConstraints() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        UUID nonExistentParentId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Reject reply with non-existent parent_comment_id
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, 'Invalid reply', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), authorId.toString(), nonExistentParentId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("fk_interaction_comments_parent");

        // 2. Insert valid root and reply
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Root to protect', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, 'Child reply', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), authorId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Physical delete on root with child replies must be RESTRICTED (rejected)
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM interaction_comments WHERE id = ?",
                rootId.toString()
        )).hasMessageContaining("fk_interaction_comments_parent");

        // 4. Verify no cascade deletion occurred; reply is still intact
        Integer replyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_comments WHERE id = ?",
                Integer.class,
                replyId.toString()
        );
        assertThat(replyCount).isEqualTo(1);
    }

    @Test
    @DisplayName("4. Target Type CHECK constraint: accept NOVEL_CHAPTER/WIKI_ARTICLE, reject unsupported values")
    void shouldEnforceTargetTypeCheckConstraint() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Accept WIKI_ARTICLE
        UUID wikiCommentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'WIKI_ARTICLE', ?, ?, NULL, 'Wiki comment', 'ACTIVE', ?, ?, NULL)",
                wikiCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Reject unsupported target_type DONGHUA_EPISODE
        UUID invalidTargetCommentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'DONGHUA_EPISODE', ?, ?, NULL, 'Donghua comment', 'ACTIVE', ?, ?, NULL)",
                invalidTargetCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_target_type");
    }

    @Test
    @DisplayName("5. Status CHECK constraint: accept ACTIVE/DELETED, reject unsupported values")
    void shouldEnforceStatusCheckConstraint() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID invalidStatusCommentId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Body', 'MODERATED', ?, ?, NULL)",
                invalidStatusCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_status");
    }

    @Test
    @DisplayName("6. ACTIVE tombstone contract: require non-null body and null deleted_at")
    void shouldEnforceActiveTombstoneContract() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Reject ACTIVE with null body
        UUID nullBodyCommentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'ACTIVE', ?, ?, NULL)",
                nullBodyCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_active");

        // 2. Reject ACTIVE with non-null deleted_at
        UUID nonNullDeletedAtCommentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Body', 'ACTIVE', ?, ?, ?)",
                nonNullDeletedAtCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_active");
    }

    @Test
    @DisplayName("7. DELETED tombstone contract: require null body, non-null deleted_at, and updated_at = deleted_at")
    void shouldEnforceDeletedTombstoneContract() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-16T11:00:00Z");
        Instant t3 = Instant.parse("2026-09-16T12:00:00Z");

        // 1. Valid DELETED row succeeds
        UUID validDeletedId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'DELETED', ?, ?, ?)",
                validDeletedId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t2), Timestamp.from(t2)
        );

        // 2. Reject DELETED with non-null body
        UUID nonNullBodyDeletedId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Retained body', 'DELETED', ?, ?, ?)",
                nonNullBodyDeletedId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t2), Timestamp.from(t2)
        )).hasMessageContaining("chk_interaction_comments_deleted");

        // 3. Reject DELETED with null deleted_at
        UUID nullDeletedAtDeletedId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'DELETED', ?, ?, NULL)",
                nullDeletedAtDeletedId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t2)
        )).hasMessageContaining("chk_interaction_comments_deleted");

        // 4. Reject DELETED with updated_at != deleted_at
        UUID mismatchedTimestampsId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'DELETED', ?, ?, ?)",
                mismatchedTimestampsId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t3), Timestamp.from(t2)
        )).hasMessageContaining("chk_interaction_comments_deleted");
    }

    @Test
    @DisplayName("8. Temporal CHECK constraint: reject updated_at < created_at")
    void shouldEnforceTemporalCheckConstraint() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID temporalViolationId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-16T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-16T11:00:00Z");

        // created_at = t2, updated_at = t1 (t1 is before t2)
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Temporal violation', 'ACTIVE', ?, ?, NULL)",
                temporalViolationId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t2), Timestamp.from(t1)
        )).hasMessageContaining("chk_interaction_comments_updated_at");
    }

    @Test
    @DisplayName("9. Self-parent CHECK constraint: reject parent_comment_id = id")
    void shouldEnforceNoSelfParentCheckConstraint() {
        UUID commentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, 'Self parent comment', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), commentId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_no_self_parent");
    }

    @Test
    @DisplayName("10. DATETIME(6) precision: retain microsecond precision upon storage and retrieval")
    void shouldRetainMicrosecondPrecisionInDatetime6() {
        UUID commentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        // 2026-09-16 10:00:00.123456 UTC
        Instant instantWithMicros = Instant.parse("2026-09-16T10:00:00.123456Z");
        Timestamp tsWithMicros = Timestamp.from(instantWithMicros);

        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'Microsecond precision test', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), tsWithMicros, tsWithMicros
        );

        Timestamp retrieved = jdbc.queryForObject(
                "SELECT created_at FROM interaction_comments WHERE id = ?",
                Timestamp.class,
                commentId.toString()
        );

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getNanos()).isEqualTo(123456000);
        assertThat(retrieved.toInstant()).isEqualTo(instantWithMicros);
    }

    @Test
    @DisplayName("11. Index presence: verify required composite indexes exist in MySQL metadata")
    void shouldVerifyRequiredIndexPresence() {
        List<String> indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'interaction_comments'",
                String.class,
                DB_NAME
        );

        assertThat(indexes).contains(
                "idx_interaction_comments_target_parent_created_id",
                "idx_interaction_comments_parent_created_id"
        );
    }
}
