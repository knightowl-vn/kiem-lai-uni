package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.out-of-order=true"
})
@DisplayName("Wiki Contribution V66 Flyway Runtime Verification Tests")
class WikiContributionV66FlywayRuntimeVerificationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        cleanV66BeforeFlyway();
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    private static void cleanV66BeforeFlyway() {
        try {
            javax.sql.DataSource ds = TestDatabaseSupport.createTestDataSource(TestDatabaseSupport.resolveDatabaseName());
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
            jdbc.execute("DROP TABLE IF EXISTS wiki_contribution_sources");
            jdbc.execute("DELETE FROM flyway_schema_history WHERE version = '66'");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clean V66 test baseline before Flyway in WikiContributionV66FlywayRuntimeVerificationTest", e);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify V66 migration is recorded as successful in flyway_schema_history")
    void shouldVerifyV66AppliedInFlywayHistory() {
        Integer success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '66'",
                Integer.class
        );
        assertThat(success).isEqualTo(1);
    }

    @Test
    @DisplayName("Verify wiki_contribution_sources table columns and nullability in information_schema")
    void shouldVerifyTableColumns() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        List<String> columnNames = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                ORDER BY ordinal_position
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );

        assertThat(columnNames).containsExactly(
                "id",
                "contribution_id",
                "source_order",
                "source_type",
                "url",
                "created_at"
        );

        // Verify all 6 columns are NOT NULL
        List<String> nullableColumns = jdbcTemplate.query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                  AND is_nullable = 'YES'
                """,
                (rs, rowNum) -> rs.getString("column_name"),
                dbName
        );
        assertThat(nullableColumns).isEmpty();

        // Verify URL length is 2000
        Long urlLength = jdbcTemplate.queryForObject(
                """
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                  AND column_name = 'url'
                """,
                Long.class,
                dbName
        );
        assertThat(urlLength).isEqualTo(2000L);
    }

    @Test
    @DisplayName("Verify Foreign Key to wiki_contributions with ON DELETE CASCADE")
    void shouldVerifyForeignKeyAndCascade() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        String referencedTable = jdbcTemplate.queryForObject(
                """
                SELECT referenced_table_name FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                  AND constraint_name = 'fk_wiki_contribution_sources_contribution'
                """,
                String.class,
                dbName
        );
        assertThat(referencedTable).isEqualTo("wiki_contributions");

        String deleteRule = jdbcTemplate.queryForObject(
                """
                SELECT delete_rule FROM information_schema.referential_constraints
                WHERE constraint_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                  AND constraint_name = 'fk_wiki_contribution_sources_contribution'
                """,
                String.class,
                dbName
        );
        assertThat(deleteRule).isEqualTo("CASCADE");
    }

    @Test
    @DisplayName("Verify Primary Key and Unique Composite Index")
    void shouldVerifyIndexes() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        String pkColumn = jdbcTemplate.queryForObject(
                """
                SELECT column_name FROM information_schema.key_column_usage
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                  AND constraint_name = 'PRIMARY'
                """,
                String.class,
                dbName
        );
        assertThat(pkColumn).isEqualTo("id");

        List<String> indexes = jdbcTemplate.query(
                """
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                """,
                (rs, rowNum) -> rs.getString("index_name"),
                dbName
        );

        assertThat(indexes).containsExactlyInAnyOrder(
                "PRIMARY",
                "uq_wiki_contribution_sources_contribution_order"
        );
    }

    @Test
    @DisplayName("Verify Check Constraints exist for source_order and source_type")
    void shouldVerifyCheckConstraints() {
        String dbName = TestDatabaseSupport.resolveDatabaseName();

        List<String> checks = jdbcTemplate.query(
                """
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = ?
                  AND table_name = 'wiki_contribution_sources'
                  AND constraint_type = 'CHECK'
                """,
                (rs, rowNum) -> rs.getString("constraint_name"),
                dbName
        );

        assertThat(checks).contains(
                "chk_wiki_contribution_sources_order",
                "chk_wiki_contribution_sources_type"
        );
    }
}
