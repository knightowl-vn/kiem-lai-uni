package com.universe.interaction.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    @DisplayName("1. Migration V49 & V50 execution and table structure verification")
    void shouldVerifyMigrationV49AndV50AndTableStructure() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(50);

        MigrationInfo v49Info = null;
        MigrationInfo v50Info = null;
        for (MigrationInfo mi : info) {
            if ("49".equals(mi.getVersion().getVersion())) {
                v49Info = mi;
            } else if ("50".equals(mi.getVersion().getVersion())) {
                v50Info = mi;
            }
        }

        assertThat(v49Info).isNotNull();
        assertThat(v49Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v49Info.getDescription()).isEqualTo("create interaction comments");

        assertThat(v50Info).isNotNull();
        assertThat(v50Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v50Info.getDescription()).isEqualTo("add comment thread root");

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
                "thread_root_comment_id",
                "body",
                "status",
                "created_at",
                "updated_at",
                "deleted_at"
        );
    }

    @Test
    @DisplayName("2. Basic storage: insert and retrieve ACTIVE root, direct reply, and nested reply rows")
    void shouldInsertAndRetrieveActiveRootAndReplyRows() {
        UUID rootId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        UUID replyAuthorId = UUID.randomUUID();
        UUID nestedReplyId = UUID.randomUUID();
        UUID nestedAuthorId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Insert ACTIVE root row
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Root comment body', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Insert ACTIVE direct reply row
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Reply comment body', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), replyAuthorId.toString(), rootId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Insert ACTIVE nested reply row (parent is replyId, thread root is rootId)
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Nested reply body', 'ACTIVE', ?, ?, NULL)",
                nestedReplyId.toString(), targetId.toString(), nestedAuthorId.toString(), replyId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        String rootBody = jdbc.queryForObject(
                "SELECT body FROM interaction_comments WHERE id = ?",
                String.class,
                rootId.toString()
        );
        assertThat(rootBody).isEqualTo("Root comment body");

        String rootThreadRoot = jdbc.queryForObject(
                "SELECT thread_root_comment_id FROM interaction_comments WHERE id = ?",
                String.class,
                rootId.toString()
        );
        assertThat(rootThreadRoot).isNull();

        String directReplyThreadRoot = jdbc.queryForObject(
                "SELECT thread_root_comment_id FROM interaction_comments WHERE id = ?",
                String.class,
                replyId.toString()
        );
        assertThat(directReplyThreadRoot).isEqualTo(rootId.toString());

        String nestedReplyThreadRoot = jdbc.queryForObject(
                "SELECT thread_root_comment_id FROM interaction_comments WHERE id = ?",
                String.class,
                nestedReplyId.toString()
        );
        assertThat(nestedReplyThreadRoot).isEqualTo(rootId.toString());
    }

    @Test
    @DisplayName("3. Foreign Key enforcement: reject invalid parent/thread root and restrict cascade delete")
    void shouldEnforceSelfReferentialForeignKeyConstraints() {
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        UUID nonExistentParentId = UUID.randomUUID();
        UUID nonExistentThreadRootId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Reject reply with non-existent parent_comment_id
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Invalid reply', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), authorId.toString(), nonExistentParentId.toString(), nonExistentParentId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("fk_interaction_comments_parent");

        // 2. Reject reply with valid parent but non-existent thread_root_comment_id
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Root to protect', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Invalid thread root reply', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), authorId.toString(), rootId.toString(), nonExistentThreadRootId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("fk_interaction_comments_thread_root");

        // 3. Insert valid reply
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Child reply', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), authorId.toString(), rootId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 4. Physical delete on root with child replies must be RESTRICTED (rejected)
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM interaction_comments WHERE id = ?",
                rootId.toString()
        )).satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("fk_interaction_comments_parent", "fk_interaction_comments_thread_root"));

        // 5. Verify no cascade deletion occurred; reply is still intact
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'WIKI_ARTICLE', ?, ?, NULL, NULL, 'Wiki comment', 'ACTIVE', ?, ?, NULL)",
                wikiCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Reject unsupported target_type DONGHUA_EPISODE
        UUID invalidTargetCommentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'DONGHUA_EPISODE', ?, ?, NULL, NULL, 'Donghua comment', 'ACTIVE', ?, ?, NULL)",
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Body', 'MODERATED', ?, ?, NULL)",
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, NULL, 'ACTIVE', ?, ?, NULL)",
                nullBodyCommentId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_active");

        // 2. Reject ACTIVE with non-null deleted_at
        UUID nonNullDeletedAtCommentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Body', 'ACTIVE', ?, ?, ?)",
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, NULL, 'DELETED', ?, ?, ?)",
                validDeletedId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t2), Timestamp.from(t2)
        );

        // 2. Reject DELETED with non-null body
        UUID nonNullBodyDeletedId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Retained body', 'DELETED', ?, ?, ?)",
                nonNullBodyDeletedId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t2), Timestamp.from(t2)
        )).hasMessageContaining("chk_interaction_comments_deleted");

        // 3. Reject DELETED with null deleted_at
        UUID nullDeletedAtDeletedId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, NULL, 'DELETED', ?, ?, NULL)",
                nullDeletedAtDeletedId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(t1), Timestamp.from(t2)
        )).hasMessageContaining("chk_interaction_comments_deleted");

        // 4. Reject DELETED with updated_at != deleted_at
        UUID mismatchedTimestampsId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, NULL, 'DELETED', ?, ?, ?)",
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Temporal violation', 'ACTIVE', ?, ?, NULL)",
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Self parent comment', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), commentId.toString(), commentId.toString(), Timestamp.from(now), Timestamp.from(now)
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
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Microsecond precision test', 'ACTIVE', ?, ?, NULL)",
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
                "idx_interaction_comments_parent_created_id",
                "idx_interaction_comments_thread_root_created_id"
        );
    }

    @Test
    @DisplayName("12. Thread hierarchy CHECK constraint: require both parent and thread_root or neither")
    void shouldEnforceThreadHierarchyCheckConstraint() {
        UUID commentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        Instant now = Instant.now();

        // Insert valid root
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Root comment', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 1. parent_comment_id is non-null, thread_root_comment_id is NULL -> rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, NULL, 'Mismatch 1', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_thread_hierarchy");

        // 2. parent_comment_id is NULL, thread_root_comment_id is non-null -> rejected
        UUID commentId2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, ?, 'Mismatch 2', 'ACTIVE', ?, ?, NULL)",
                commentId2.toString(), targetId.toString(), authorId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_thread_hierarchy");
    }

    @Test
    @DisplayName("13. Self thread root CHECK constraint: reject thread_root_comment_id = id")
    void shouldEnforceNoSelfThreadRootCheckConstraint() {
        UUID commentId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        Instant now = Instant.now();

        // Insert valid root
        jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, NULL, 'Root comment', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Reject row where thread_root_comment_id = id
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, thread_root_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, ?, 'Self thread root comment', 'ACTIVE', ?, ?, NULL)",
                commentId.toString(), targetId.toString(), authorId.toString(), rootId.toString(), commentId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_comments_no_self_thread_root");
    }

    @Test
    @DisplayName("14. V49 to V50 backfill: existing V49 comments have thread_root_comment_id backfilled")
    void shouldBackfillThreadRootCommentIdFromV49ToV50() {
        String backfillDbName = "kiemlai_comments_backfill_test";
        TestDatabaseSupport.resetTestDatabase(backfillDbName);
        DataSource backfillDs = TestDatabaseSupport.createTestDataSource(backfillDbName);

        // 1. Migrate only up to V49
        Flyway flywayV49 = Flyway.configure()
                .dataSource(backfillDs)
                .locations("classpath:db/migration")
                .target("49")
                .load();
        flywayV49.migrate();

        JdbcTemplate backfillJdbc = new JdbcTemplate(backfillDs);

        UUID rootId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        // Insert root and direct reply under V49 schema (no thread_root_comment_id column exists yet)
        backfillJdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, NULL, 'V49 root', 'ACTIVE', ?, ?, NULL)",
                rootId.toString(), targetId.toString(), authorId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        backfillJdbc.update(
                "INSERT INTO interaction_comments (id, target_type, target_id, author_user_id, parent_comment_id, body, status, created_at, updated_at, deleted_at) " +
                        "VALUES (?, 'NOVEL_CHAPTER', ?, ?, ?, 'V49 reply', 'ACTIVE', ?, ?, NULL)",
                replyId.toString(), targetId.toString(), authorId.toString(), rootId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Now migrate to V50 (latest)
        Flyway flywayV50 = Flyway.configure()
                .dataSource(backfillDs)
                .locations("classpath:db/migration")
                .load();
        flywayV50.migrate();

        // 3. Verify V50 backfill results
        String backfilledThreadRoot = backfillJdbc.queryForObject(
                "SELECT thread_root_comment_id FROM interaction_comments WHERE id = ?",
                String.class,
                replyId.toString()
        );
        assertThat(backfilledThreadRoot).isEqualTo(rootId.toString());

        String rootThreadRoot = backfillJdbc.queryForObject(
                "SELECT thread_root_comment_id FROM interaction_comments WHERE id = ?",
                String.class,
                rootId.toString()
        );
        assertThat(rootThreadRoot).isNull();
    }
}
