package com.universe.interaction.infrastructure.persistence.reaction;

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

@DisplayName("Reaction V70 Flyway Runtime Verification Tests")
class ReactionV70FlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_reactions_flyway_test";
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
    @DisplayName("1. Migration V70 execution and table structure verification")
    void shouldVerifyMigrationV70AndTableStructure() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(70);

        MigrationInfo v70Info = null;
        for (MigrationInfo mi : info) {
            if ("70".equals(mi.getVersion().getVersion())) {
                v70Info = mi;
                break;
            }
        }

        assertThat(v70Info).isNotNull();
        assertThat(v70Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v70Info.getDescription()).isEqualTo("create interaction reactions");

        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'interaction_reactions'",
                Integer.class,
                DB_NAME
        );
        assertThat(tableCount).isEqualTo(1);

        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'interaction_reactions'",
                String.class,
                DB_NAME
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "user_id",
                "target_type",
                "target_id",
                "reaction_type",
                "created_at",
                "updated_at"
        );
    }

    @Test
    @DisplayName("2. Basic storage: insert and retrieve valid reaction row")
    void shouldInsertAndRetrieveReactionRow() {
        UUID reactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'NOVEL_CHAPTER', ?, 'LOVE', ?, ?)",
                reactionId.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        String reactionType = jdbc.queryForObject(
                "SELECT reaction_type FROM interaction_reactions WHERE id = ?",
                String.class,
                reactionId.toString()
        );
        assertThat(reactionType).isEqualTo("LOVE");
    }

    @Test
    @DisplayName("3. Unique constraint enforcement: one user + one target = at most one current reaction")
    void shouldEnforceUserTargetUniqueConstraint() {
        UUID reaction1Id = UUID.randomUUID();
        UUID reaction2Id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'NOVEL_CHAPTER', ?, 'LOVE', ?, ?)",
                reaction1Id.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Duplicate reaction for same user on same target (even with different reaction_type) must fail
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'NOVEL_CHAPTER', ?, 'FIRE', ?, ?)",
                reaction2Id.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("uq_interaction_reactions_user_target");
    }

    @Test
    @DisplayName("4. Target Type CHECK constraint: accept NOVEL_CHAPTER/COMMENT/DONGHUA_EPISODE, reject unsupported")
    void shouldEnforceTargetTypeCheckConstraint() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Accept COMMENT
        UUID commentReactionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'COMMENT', ?, 'HAHA', ?, ?)",
                commentReactionId.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 2. Accept DONGHUA_EPISODE
        UUID episodeReactionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'DONGHUA_EPISODE', ?, 'SAD', ?, ?)",
                episodeReactionId.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 3. Reject unsupported WIKI_ARTICLE or MANHUA
        UUID invalidReactionId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'WIKI_ARTICLE', ?, 'LOVE', ?, ?)",
                invalidReactionId.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reactions_target_type");
    }

    @Test
    @DisplayName("5. Reaction Type CHECK constraint: accept LOVE/FIRE/HAHA/SAD, reject unsupported")
    void shouldEnforceReactionTypeCheckConstraint() {
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID invalidTypeId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'NOVEL_CHAPTER', ?, 'DISLIKE', ?, ?)",
                invalidTypeId.toString(), userId.toString(), targetId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).hasMessageContaining("chk_interaction_reactions_type");
    }

    @Test
    @DisplayName("6. Temporal CHECK constraint: reject updated_at < created_at")
    void shouldEnforceTemporalCheckConstraint() {
        UUID reactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T11:00:00Z");

        // created_at = t2, updated_at = t1 (t1 before t2)
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'NOVEL_CHAPTER', ?, 'LOVE', ?, ?)",
                reactionId.toString(), userId.toString(), targetId.toString(), Timestamp.from(t2), Timestamp.from(t1)
        )).hasMessageContaining("chk_interaction_reactions_updated_at");
    }

    @Test
    @DisplayName("7. Microsecond precision with DATETIME(6)")
    void shouldRetainMicrosecondPrecisionInDatetime6() {
        UUID reactionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        Instant instantWithMicros = Instant.parse("2026-09-26T10:00:00.654321Z");
        Timestamp tsWithMicros = Timestamp.from(instantWithMicros);

        jdbc.update(
                "INSERT INTO interaction_reactions (id, user_id, target_type, target_id, reaction_type, created_at, updated_at) " +
                        "VALUES (?, ?, 'NOVEL_CHAPTER', ?, 'FIRE', ?, ?)",
                reactionId.toString(), userId.toString(), targetId.toString(), tsWithMicros, tsWithMicros
        );

        Timestamp retrieved = jdbc.queryForObject(
                "SELECT created_at FROM interaction_reactions WHERE id = ?",
                Timestamp.class,
                reactionId.toString()
        );

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getNanos()).isEqualTo(654321000);
        assertThat(retrieved.toInstant()).isEqualTo(instantWithMicros);
    }

    @Test
    @DisplayName("8. Verify secondary indexes and zero foreign keys")
    void shouldVerifyIndexesAndZeroForeignKeys() {
        List<String> indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'interaction_reactions'",
                String.class,
                DB_NAME
        );

        assertThat(indexes).contains(
                "PRIMARY",
                "uq_interaction_reactions_user_target",
                "idx_interaction_reactions_target_summary"
        );

        Integer fkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = ? AND table_name = 'interaction_reactions' AND constraint_type = 'FOREIGN KEY'",
                Integer.class,
                DB_NAME
        );
        assertThat(fkCount).isZero();
    }
}
