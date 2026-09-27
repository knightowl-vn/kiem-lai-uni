package com.universe.wiki.infrastructure.persistence.saved;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WikiSavedArticleFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V48 Flyway migration: Xác thực schema wiki_saved_articles, cột, khóa chính, unique, FK CASCADE, và index")
    void shouldMigrateCleanDatabaseThroughV48AndVerifySchema() {
        String dbName = "kiemlai_wiki_saved_articles_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(48);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(48);

        MigrationInfo v48Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("48".equals(mi.getVersion().getVersion())) {
                v48Info = mi;
            }
        }

        assertThat(v48Info).isNotNull();
        assertThat(v48Info.getDescription()).isEqualTo("create wiki saved articles");
        assertThat(v48Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Check table existence
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'wiki_saved_articles'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Verify column definitions
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_saved_articles'",
                String.class,
                dbName
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "user_id",
                "article_id",
                "created_at"
        );

        // 3. Verify primary key
        List<String> pkColumns = jdbc.queryForList("""
                SELECT k.column_name
                FROM information_schema.table_constraints t
                JOIN information_schema.key_column_usage k
                  ON t.constraint_name = k.constraint_name
                  AND t.table_schema = k.table_schema
                  AND t.table_name = k.table_name
                WHERE t.table_schema = ?
                  AND t.table_name = 'wiki_saved_articles'
                  AND t.constraint_type = 'PRIMARY KEY'
                ORDER BY k.ordinal_position
                """,
                String.class,
                dbName
        );
        assertThat(pkColumns).containsExactly("id");

        // 4. Verify unique constraint on (user_id, article_id)
        List<String> uniqueConstraintNames = jdbc.queryForList("""
                SELECT DISTINCT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = ?
                  AND table_name = 'wiki_saved_articles'
                  AND constraint_type = 'UNIQUE'
                """,
                String.class,
                dbName
        );
        assertThat(uniqueConstraintNames).contains("uq_wiki_saved_articles_user_article");

        // 5. Verify Foreign Key to wiki_articles(id) with ON DELETE CASCADE
        List<String> deleteRules = jdbc.queryForList("""
                SELECT delete_rule
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name = 'wiki_saved_articles'
                  AND constraint_name = 'fk_wiki_saved_articles_article'
                """,
                String.class,
                dbName
        );
        assertThat(deleteRules).containsExactly("CASCADE");

        // 6. Verify index idx_wiki_saved_articles_user_created_id
        List<String> indexNames = jdbc.queryForList("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'wiki_saved_articles'
                """,
                String.class,
                dbName
        );
        assertThat(indexNames).contains("idx_wiki_saved_articles_user_created_id");
    }
}
