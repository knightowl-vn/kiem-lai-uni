package com.universe.wiki.infrastructure.persistence.appreciation;

import com.universe.test.TestDatabaseSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
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

@DisplayName("WikiAppreciationRatings Flyway Runtime Verification Tests")
class WikiAppreciationFlywayRuntimeVerificationTest {

    private static DataSource createDataSource(String dbName) {
        return TestDatabaseSupport.createTestDataSource(dbName);
    }

    private static void resetDatabase(String dbName) {
        TestDatabaseSupport.resetTestDatabase(dbName);
    }

    @Test
    @DisplayName("V56 Flyway migration: Xác thực schema wiki_appreciation_ratings, các cột, PK, unique, CHECK, và FK ON DELETE CASCADE")
    void shouldMigrateThroughV56AndVerifySchemaAndConstraints() {
        String dbName = "kiemlai_wiki_appreciation_schema_test";
        resetDatabase(dbName);
        DataSource ds = createDataSource(dbName);

        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();

        int migrationsApplied = flyway.migrate().migrationsExecuted;
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(56);

        MigrationInfo[] info = flyway.info().all();
        assertThat(info).hasSizeGreaterThanOrEqualTo(56);

        MigrationInfo v56Info = null;
        for (MigrationInfo mi : info) {
            assertThat(mi.getState()).isEqualTo(MigrationState.SUCCESS);
            if ("56".equals(mi.getVersion().getVersion())) {
                v56Info = mi;
            }
        }

        assertThat(v56Info).isNotNull();
        assertThat(v56Info.getDescription()).isEqualTo("create wiki appreciation ratings");
        assertThat(v56Info.getChecksum()).isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. Kiểm tra sự tồn tại của bảng
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'wiki_appreciation_ratings'",
                Integer.class,
                dbName
        );
        assertThat(tableCount).isEqualTo(1);

        // 2. Kiểm tra danh sách cột trong cấu trúc bảng hiện hành (live schema)
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'wiki_appreciation_ratings'",
                String.class,
                dbName
        );
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "wiki_article_id",
                "user_id",
                "value",
                "created_at",
                "updated_at"
        );

        // 3. Kiểm tra khóa chính (PRIMARY KEY)
        List<String> pkColumns = jdbc.queryForList("""
                SELECT k.column_name
                FROM information_schema.table_constraints t
                JOIN information_schema.key_column_usage k
                  ON t.constraint_name = k.constraint_name
                  AND t.table_schema = k.table_schema
                  AND t.table_name = k.table_name
                WHERE t.table_schema = ?
                  AND t.table_name = 'wiki_appreciation_ratings'
                  AND t.constraint_type = 'PRIMARY KEY'
                ORDER BY k.ordinal_position
                """,
                String.class,
                dbName
        );
        assertThat(pkColumns).containsExactly("id");

        // 4. Kiểm tra ràng buộc duy nhất uq_wiki_appreciation_ratings_article_user
        List<String> uniqueConstraintNames = jdbc.queryForList("""
                SELECT DISTINCT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = ?
                  AND table_name = 'wiki_appreciation_ratings'
                  AND constraint_type = 'UNIQUE'
                """,
                String.class,
                dbName
        );
        assertThat(uniqueConstraintNames).contains("uq_wiki_appreciation_ratings_article_user");

        // 5. Kiểm tra Foreign Key đến wiki_articles(id) với ON DELETE CASCADE
        List<String> deleteRules = jdbc.queryForList("""
                SELECT delete_rule
                FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name = 'wiki_appreciation_ratings'
                  AND constraint_name = 'fk_wiki_appreciation_ratings_article'
                """,
                String.class,
                dbName
        );
        assertThat(deleteRules).containsExactly("CASCADE");

        // 6. Kiểm tra hành vi thực thi CHECK constraint và FK CASCADE ở runtime DB
        UUID adminId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        // Tạo bài viết cha
        jdbc.update("""
                INSERT INTO wiki_articles (
                    id, title, slug, article_type, status, summary, content,
                    created_by, created_at, updated_at, published_by, published_at,
                    aggregate_version, content_version
                ) VALUES (?, 'Trần Bình An', 'tran-binh-an', 'CHARACTER', 'PUBLISHED', 'Tóm tắt', '# Nội dung',
                    ?, ?, ?, ?, ?, 1, 1)
                """,
                articleId.toString(), adminId.toString(), now, now, adminId.toString(), now
        );

        UUID user1 = UUID.randomUUID();
        UUID rating1 = UUID.randomUUID();

        // Ghi hợp lệ value = 1
        jdbc.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """,
                rating1.toString(), articleId.toString(), user1.toString(), now, now
        );

        // Từ chối ghi value = 0 (CHECK constraint)
        UUID user2 = UUID.randomUUID();
        UUID rating2 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                """,
                rating2.toString(), articleId.toString(), user2.toString(), now, now
        )).hasMessageContaining("chk_wiki_appreciation_ratings_value");

        // Từ chối ghi value = 6 (CHECK constraint)
        UUID rating3 = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 6, ?, ?)
                """,
                rating3.toString(), articleId.toString(), user2.toString(), now, now
        )).hasMessageContaining("chk_wiki_appreciation_ratings_value");

        // Từ chối ghi trùng (wiki_article_id, user_id)
        UUID ratingDuplicate = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wiki_appreciation_ratings (id, wiki_article_id, user_id, value, created_at, updated_at)
                VALUES (?, ?, ?, 5, ?, ?)
                """,
                ratingDuplicate.toString(), articleId.toString(), user1.toString(), now, now
        )).isInstanceOf(Exception.class);

        // Kiểm tra ON DELETE CASCADE: xóa wiki_article cha -> xóa tự động bản ghi appreciation
        jdbc.update("DELETE FROM wiki_articles WHERE id = ?", articleId.toString());
        Integer remainingRatings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wiki_appreciation_ratings WHERE id = ?",
                Integer.class,
                rating1.toString()
        );
        assertThat(remainingRatings).isEqualTo(0);
    }
}
