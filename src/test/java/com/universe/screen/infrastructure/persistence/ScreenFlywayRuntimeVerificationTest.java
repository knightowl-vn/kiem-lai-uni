package com.universe.screen.infrastructure.persistence;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Screen Flyway Runtime & Schema Verification (MS-08B1)")
class ScreenFlywayRuntimeVerificationTest {

    private static final String DB_NAME = "kiemlai_screen_schema_test";
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
    @DisplayName("1. Migration V87 execution and description verification")
    void shouldVerifyV87MigrationExecutedSuccessfully() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(87);

        MigrationInfo v87Info = null;
        for (MigrationInfo mi : info) {
            if ("87".equals(mi.getVersion().getVersion())) {
                v87Info = mi;
                break;
            }
        }

        assertThat(v87Info).isNotNull();
        assertThat(v87Info.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(v87Info.getDescription()).isEqualTo("create screen schema");
    }

    @Test
    @DisplayName("2. Verify required Screen tables exist in information_schema (future-additive safe)")
    void shouldVerifyRequiredScreenTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? AND table_name LIKE 'screen_%'",
                String.class,
                DB_NAME
        );

        assertThat(tables).contains(
                "screen_franchises",
                "screen_titles",
                "screen_franchise_memberships",
                "screen_entries"
        );
    }

    @Test
    @DisplayName("3. Verify screen_franchises table structure, columns and types")
    void shouldVerifyScreenFranchisesTableStructure() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_franchises'",
                String.class,
                DB_NAME
        );

        assertThat(columns).contains("id", "slug", "name");

        Map<String, Object> idCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_franchises' AND column_name = 'id'",
                DB_NAME
        );
        assertThat(idCol.get("is_nullable")).isEqualTo("NO");
        assertThat(idCol.get("data_type")).isEqualTo("char");
        assertThat(((Number) idCol.get("character_maximum_length")).longValue()).isEqualTo(36L);

        Map<String, Object> slugCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type, character_maximum_length FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_franchises' AND column_name = 'slug'",
                DB_NAME
        );
        assertThat(slugCol.get("is_nullable")).isEqualTo("NO");
        assertThat(slugCol.get("data_type")).isEqualTo("varchar");
        assertThat(((Number) slugCol.get("character_maximum_length")).longValue()).isEqualTo(180L);

        Map<String, Object> nameCol = jdbc.queryForMap(
                "SELECT is_nullable, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_franchises' AND column_name = 'name'",
                DB_NAME
        );
        assertThat(nameCol.get("is_nullable")).isEqualTo("NO");
        assertThat(nameCol.get("data_type")).isEqualTo("text");
    }

    @Test
    @DisplayName("4. Verify screen_titles table structure, columns, types and nullability")
    void shouldVerifyScreenTitlesTableStructure() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_titles'",
                String.class,
                DB_NAME
        );

        assertThat(columns).contains(
                "id",
                "slug",
                "title",
                "original_title",
                "synopsis",
                "structure",
                "presentation",
                "poster_media_asset_id",
                "backdrop_media_asset_id",
                "status",
                "published_at",
                "created_at",
                "updated_at"
        );

        // Required text
        assertThat(queryColumnProperty("screen_titles", "id", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "slug", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "title", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "title", "data_type")).isEqualTo("text");
        assertThat(queryColumnProperty("screen_titles", "structure", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "presentation", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "status", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "created_at", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_titles", "updated_at", "is_nullable")).isEqualTo("NO");

        // Optional text / media / publication
        assertThat(queryColumnProperty("screen_titles", "original_title", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_titles", "original_title", "data_type")).isEqualTo("text");
        assertThat(queryColumnProperty("screen_titles", "synopsis", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_titles", "poster_media_asset_id", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_titles", "backdrop_media_asset_id", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_titles", "published_at", "is_nullable")).isEqualTo("YES");

        // Specific scalar lengths
        assertThat(((Number) queryColumnProperty("screen_titles", "slug", "character_maximum_length")).longValue()).isEqualTo(180L);
        assertThat(((Number) queryColumnProperty("screen_titles", "poster_media_asset_id", "character_maximum_length")).longValue()).isEqualTo(36L);
        assertThat(((Number) queryColumnProperty("screen_titles", "backdrop_media_asset_id", "character_maximum_length")).longValue()).isEqualTo(36L);
    }

    @Test
    @DisplayName("5. Verify screen_franchise_memberships table structure")
    void shouldVerifyScreenFranchiseMembershipsTableStructure() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_franchise_memberships'",
                String.class,
                DB_NAME
        );

        assertThat(columns).contains("id", "franchise_id", "title_id", "display_order");

        assertThat(queryColumnProperty("screen_franchise_memberships", "id", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_franchise_memberships", "franchise_id", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_franchise_memberships", "title_id", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_franchise_memberships", "display_order", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_franchise_memberships", "display_order", "data_type")).isEqualTo("int");
    }

    @Test
    @DisplayName("6. Verify screen_entries table structure, types and FEATURE-compatible nullable number")
    void shouldVerifyScreenEntriesTableStructure() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'screen_entries'",
                String.class,
                DB_NAME
        );

        assertThat(columns).contains(
                "id",
                "title_id",
                "slug",
                "type",
                "number",
                "title",
                "synopsis",
                "video_media_asset_id",
                "thumbnail_media_asset_id",
                "duration_seconds",
                "status",
                "published_at",
                "created_at",
                "updated_at"
        );

        // Required columns
        assertThat(queryColumnProperty("screen_entries", "id", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "title_id", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "slug", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "type", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "title", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "title", "data_type")).isEqualTo("text");
        assertThat(queryColumnProperty("screen_entries", "status", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "created_at", "is_nullable")).isEqualTo("NO");
        assertThat(queryColumnProperty("screen_entries", "updated_at", "is_nullable")).isEqualTo("NO");

        // CRITICAL: number must be nullable (FEATURE movies are not fake Episode 1)
        assertThat(queryColumnProperty("screen_entries", "number", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_entries", "number", "column_default")).isNull();

        // Optional columns
        assertThat(queryColumnProperty("screen_entries", "synopsis", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_entries", "video_media_asset_id", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_entries", "thumbnail_media_asset_id", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_entries", "duration_seconds", "is_nullable")).isEqualTo("YES");
        assertThat(queryColumnProperty("screen_entries", "duration_seconds", "data_type")).isEqualTo("bigint");
        assertThat(queryColumnProperty("screen_entries", "published_at", "is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("7. Verify Screen-internal foreign keys: membership CASCADE, entry RESTRICT")
    void shouldVerifyScreenInternalForeignKeys() {
        List<Map<String, Object>> membershipFks = jdbc.queryForList(
                """
                SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, r.DELETE_RULE
                FROM information_schema.KEY_COLUMN_USAGE k
                JOIN information_schema.REFERENTIAL_CONSTRAINTS r
                  ON k.CONSTRAINT_NAME = r.CONSTRAINT_NAME AND k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA
                WHERE k.TABLE_SCHEMA = ?
                  AND k.TABLE_NAME = 'screen_franchise_memberships'
                  AND k.REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY k.COLUMN_NAME
                """,
                DB_NAME
        );

        assertThat(membershipFks).hasSize(2);

        Map<String, Object> franchiseFk = membershipFks.get(0);
        assertThat(franchiseFk.get("COLUMN_NAME")).isEqualTo("franchise_id");
        assertThat(franchiseFk.get("REFERENCED_TABLE_NAME")).isEqualTo("screen_franchises");
        assertThat(franchiseFk.get("REFERENCED_COLUMN_NAME")).isEqualTo("id");
        assertThat(franchiseFk.get("DELETE_RULE")).isEqualTo("CASCADE");

        Map<String, Object> titleFk = membershipFks.get(1);
        assertThat(titleFk.get("COLUMN_NAME")).isEqualTo("title_id");
        assertThat(titleFk.get("REFERENCED_TABLE_NAME")).isEqualTo("screen_titles");
        assertThat(titleFk.get("REFERENCED_COLUMN_NAME")).isEqualTo("id");
        assertThat(titleFk.get("DELETE_RULE")).isEqualTo("CASCADE");

        List<Map<String, Object>> entryFks = jdbc.queryForList(
                """
                SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, r.DELETE_RULE
                FROM information_schema.KEY_COLUMN_USAGE k
                JOIN information_schema.REFERENTIAL_CONSTRAINTS r
                  ON k.CONSTRAINT_NAME = r.CONSTRAINT_NAME AND k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA
                WHERE k.TABLE_SCHEMA = ?
                  AND k.TABLE_NAME = 'screen_entries'
                  AND k.REFERENCED_TABLE_NAME IS NOT NULL
                """,
                DB_NAME
        );

        assertThat(entryFks).hasSize(1);
        Map<String, Object> entryTitleFk = entryFks.get(0);
        assertThat(entryTitleFk.get("COLUMN_NAME")).isEqualTo("title_id");
        assertThat(entryTitleFk.get("REFERENCED_TABLE_NAME")).isEqualTo("screen_titles");
        assertThat(entryTitleFk.get("REFERENCED_COLUMN_NAME")).isEqualTo("id");
        assertThat(entryTitleFk.get("DELETE_RULE")).isEqualTo("RESTRICT");
    }

    @Test
    @DisplayName("8. Verify zero cross-context foreign keys on Media scalar columns")
    void shouldVerifyZeroCrossContextForeignKeysToMedia() {
        List<Map<String, Object>> mediaFks = jdbc.queryForList(
                """
                SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME IN ('screen_titles', 'screen_entries')
                  AND COLUMN_NAME IN ('poster_media_asset_id', 'backdrop_media_asset_id', 'video_media_asset_id', 'thumbnail_media_asset_id')
                  AND REFERENCED_TABLE_NAME IS NOT NULL
                """,
                DB_NAME
        );

        assertThat(mediaFks).isEmpty();
    }

    @Test
    @DisplayName("9. Verify indexes on Screen tables")
    void shouldVerifyIndexes() {
        List<String> franchiseIndexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'screen_franchises'",
                String.class,
                DB_NAME
        );
        assertThat(franchiseIndexes).contains("PRIMARY", "uq_screen_franchises_slug");

        List<String> titleIndexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'screen_titles'",
                String.class,
                DB_NAME
        );
        assertThat(titleIndexes).contains("PRIMARY", "uq_screen_titles_slug");

        List<String> membershipIndexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'screen_franchise_memberships'",
                String.class,
                DB_NAME
        );
        assertThat(membershipIndexes).contains("PRIMARY", "uq_screen_franchise_memberships_franchise_title", "idx_screen_franchise_memberships_title");

        List<String> entryIndexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = 'screen_entries'",
                String.class,
                DB_NAME
        );
        assertThat(entryIndexes).contains("PRIMARY", "uq_screen_entries_title_slug");
    }

    @Test
    @DisplayName("10. Enforces slug uniqueness on franchises and titles")
    void shouldEnforceSlugUniqueness() {
        UUID fId1 = UUID.randomUUID();
        UUID fId2 = UUID.randomUUID();

        jdbc.update("INSERT INTO screen_franchises (id, slug, name) VALUES (?, 'kiem-lai', 'Kiếm Lai')", fId1.toString());

        // Duplicate franchise slug rejected
        assertThatThrownBy(() -> jdbc.update("INSERT INTO screen_franchises (id, slug, name) VALUES (?, 'kiem-lai', 'Kiếm Lai 2')", fId2.toString()))
                .isInstanceOf(DataAccessException.class);

        UUID tId1 = UUID.randomUUID();
        UUID tId2 = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 'kiem-lai-phan-1', 'Kiếm Lai Phần 1', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                tId1.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Duplicate title slug rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 'kiem-lai-phan-1', 'Kiếm Lai Phần 1 (Khác)', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                tId2.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("11. Enforces membership integrity: unique (franchise, title) pair and non-negative displayOrder")
    void shouldEnforceMembershipConstraints() {
        UUID franchiseId = UUID.randomUUID();
        UUID titleId1 = UUID.randomUUID();
        UUID titleId2 = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("INSERT INTO screen_franchises (id, slug, name) VALUES (?, 'f-membership-test', 'Test Franchise')", franchiseId.toString());
        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 't-member-1', 'Title 1', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId1.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 't-member-2', 'Title 2', 'FEATURE', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId2.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Valid membership with order 0
        jdbc.update(
                "INSERT INTO screen_franchise_memberships (id, franchise_id, title_id, display_order) VALUES (?, ?, ?, 0)",
                UUID.randomUUID().toString(), franchiseId.toString(), titleId1.toString()
        );

        // Duplicate (franchise, title) rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_franchise_memberships (id, franchise_id, title_id, display_order) VALUES (?, ?, ?, 1)",
                UUID.randomUUID().toString(), franchiseId.toString(), titleId1.toString()
        )).isInstanceOf(DataAccessException.class);

        // Negative display_order rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_franchise_memberships (id, franchise_id, title_id, display_order) VALUES (?, ?, ?, -1)",
                UUID.randomUUID().toString(), franchiseId.toString(), titleId2.toString()
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("12. Enforces publication state constraints on titles and entries")
    void shouldEnforcePublicationStateConstraints() {
        UUID titleId = UUID.randomUUID();
        Instant now = Instant.now();

        // DRAFT with non-null published_at rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, published_at, created_at, updated_at) " +
                        "VALUES (?, 'draft-invalid-published', 'Title', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?, ?)",
                titleId.toString(), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // PUBLISHED with null published_at rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, published_at, created_at, updated_at) " +
                        "VALUES (?, 'published-invalid-null', 'Title', 'SERIES', 'ANIMATION_3D', 'PUBLISHED', NULL, ?, ?)",
                titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("13. Enforces entry numbering rules: EPISODE requires > 0, FEATURE must be null, SPECIAL/TRAILER nullable > 0")
    void shouldEnforceEntryNumberingRules() {
        UUID titleId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 'title-for-entries', 'Title For Entries', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 1. EPISODE with number null -> rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'ep-null', 'EPISODE', NULL, 'Episode Null', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // 2. EPISODE with number 0 -> rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'ep-zero', 'EPISODE', 0, 'Episode Zero', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // 3. EPISODE with number 1 -> valid
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'ep-1', 'EPISODE', 1, 'Episode 1', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 4. FEATURE with number 1 -> rejected (movie invariant: never fake Episode 1)
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'feat-fake-ep1', 'FEATURE', 1, 'Movie 1', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // 5. FEATURE with number null -> valid
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'feat-play', 'FEATURE', NULL, 'Movie Feature', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 6. SPECIAL with number null -> valid
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'spec-null', 'SPECIAL', NULL, 'Special Null', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 7. SPECIAL with number 0 -> rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'spec-zero', 'SPECIAL', 0, 'Special Zero', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // 8. Duration <= 0 -> rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, duration_seconds, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'ep-dur-neg', 'EPISODE', 2, 'Episode 2', -10, 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // 9. Duration > 0 -> valid
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, duration_seconds, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'ep-dur-pos', 'EPISODE', 2, 'Episode 2', 1200, 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("14. Enforces entry slug uniqueness scoped to title_id")
    void shouldEnforceEntrySlugScopedToTitle() {
        UUID titleId1 = UUID.randomUUID();
        UUID titleId2 = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 'title-scope-1', 'Title Scope 1', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId1.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 'title-scope-2', 'Title Scope 2', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId2.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Same slug 'tap-1' under title 1
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'tap-1', 'EPISODE', 1, 'Tập 1', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId1.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // Duplicate slug 'tap-1' under SAME title 1 -> rejected
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'tap-1', 'EPISODE', 2, 'Tập 1 Trùng', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId1.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        // Same slug 'tap-1' under DIFFERENT title 2 -> accepted!
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'tap-1', 'EPISODE', 1, 'Tập 1', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId2.toString(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    @Test
    @DisplayName("15. Enforces fail-closed RESTRICT delete on titles with child entries and CASCADE on memberships")
    void shouldEnforceFailClosedDeleteOnTitleWithEntriesAndCascadeOnMemberships() {
        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("INSERT INTO screen_franchises (id, slug, name) VALUES (?, 'f-fail-closed', 'Franchise Fail Closed')", franchiseId.toString());
        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 't-fail-closed', 'Title Fail Closed', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                "INSERT INTO screen_franchise_memberships (id, franchise_id, title_id, display_order) VALUES (?, ?, ?, 0)",
                membershipId.toString(), franchiseId.toString(), titleId.toString()
        );
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'ep-fail-closed', 'EPISODE', 1, 'Ep Fail Closed', 'DRAFT', ?, ?)",
                entryId.toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        );

        // 1. Deleting title while entry exists MUST BE REJECTED by ON DELETE RESTRICT
        assertThatThrownBy(() -> jdbc.update("DELETE FROM screen_titles WHERE id = ?", titleId.toString()))
                .isInstanceOf(DataAccessException.class);

        // 2. Both title, entry, and membership MUST REMAIN INTACT after rejected deletion
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_titles WHERE id = ?", Integer.class, titleId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_entries WHERE id = ?", Integer.class, entryId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_franchise_memberships WHERE id = ?", Integer.class, membershipId.toString())).isEqualTo(1);

        // 3. After explicit deletion of child entry, deleting title succeeds and cascades to membership
        jdbc.update("DELETE FROM screen_entries WHERE id = ?", entryId.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_entries WHERE id = ?", Integer.class, entryId.toString())).isEqualTo(0);

        jdbc.update("DELETE FROM screen_titles WHERE id = ?", titleId.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_titles WHERE id = ?", Integer.class, titleId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_franchise_memberships WHERE id = ?", Integer.class, membershipId.toString())).isEqualTo(0);

        // 4. Franchise deletion cascades to its association memberships
        UUID titleId2 = UUID.randomUUID();
        UUID membershipId2 = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 't-cascade-mem', 'Title Mem', 'FEATURE', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId2.toString(), Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                "INSERT INTO screen_franchise_memberships (id, franchise_id, title_id, display_order) VALUES (?, ?, ?, 1)",
                membershipId2.toString(), franchiseId.toString(), titleId2.toString()
        );

        jdbc.update("DELETE FROM screen_franchises WHERE id = ?", franchiseId.toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_franchises WHERE id = ?", Integer.class, franchiseId.toString())).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_franchise_memberships WHERE id = ?", Integer.class, membershipId2.toString())).isEqualTo(0);
        // The title itself remains intact
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screen_titles WHERE id = ?", Integer.class, titleId2.toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("16. Proves schema/domain parity by persisting representative text exceeding 255 characters")
    void shouldPersistDomainValidTextExceeding255Characters() {
        String longText300 = "Trường Thiên Kiếm Lai - ".repeat(15); // > 300 characters
        assertThat(longText300.length()).isGreaterThan(255);

        UUID franchiseId = UUID.randomUUID();
        UUID titleId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Franchise name > 255 chars succeeds
        jdbc.update(
                "INSERT INTO screen_franchises (id, slug, name) VALUES (?, 'franchise-long-name', ?)",
                franchiseId.toString(), longText300
        );

        String savedFranchiseName = jdbc.queryForObject(
                "SELECT name FROM screen_franchises WHERE id = ?",
                String.class, franchiseId.toString()
        );
        assertThat(savedFranchiseName).isEqualTo(longText300);

        // 2. Title title & original_title > 255 chars succeeds
        jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, original_title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 'title-long-text', ?, ?, 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                titleId.toString(), longText300, longText300, Timestamp.from(now), Timestamp.from(now)
        );

        Map<String, Object> titleMap = jdbc.queryForMap(
                "SELECT title, original_title FROM screen_titles WHERE id = ?",
                titleId.toString()
        );
        assertThat(titleMap.get("title")).isEqualTo(longText300);
        assertThat(titleMap.get("original_title")).isEqualTo(longText300);

        // 3. Entry title > 255 chars succeeds
        jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'entry-long-title', 'EPISODE', 1, ?, 'DRAFT', ?, ?)",
                entryId.toString(), titleId.toString(), longText300, Timestamp.from(now), Timestamp.from(now)
        );

        String savedEntryTitle = jdbc.queryForObject(
                "SELECT title FROM screen_entries WHERE id = ?",
                String.class, entryId.toString()
        );
        assertThat(savedEntryTitle).isEqualTo(longText300);

        // 4. Blank/empty names and titles are still rejected by domain CHECK constraints
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_franchises (id, slug, name) VALUES (?, 'f-blank', '   ')",
                UUID.randomUUID().toString()
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 't-blank', '   ', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_titles (id, slug, title, original_title, structure, presentation, status, created_at, updated_at) " +
                        "VALUES (?, 't-blank-orig', 'Title', '   ', 'SERIES', 'ANIMATION_3D', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO screen_entries (id, title_id, slug, type, number, title, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'e-blank', 'EPISODE', 2, '   ', 'DRAFT', ?, ?)",
                UUID.randomUUID().toString(), titleId.toString(), Timestamp.from(now), Timestamp.from(now)
        )).isInstanceOf(DataAccessException.class);
    }

    private Object queryColumnProperty(String tableName, String columnName, String property) {
        Map<String, Object> map = jdbc.queryForMap(
                "SELECT " + property + " FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                DB_NAME, tableName, columnName
        );
        return map.get(property);
    }
}
