package com.universe.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDatabaseSupportTest {

	@Test
	@DisplayName("1. Safe local host and default kiemlai_test database are accepted")
	void shouldAcceptLocalhostAndKiemlaiTest() {
		assertThatCode(() -> TestDatabaseSupport.validateSafety("localhost", "kiemlai_test"))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateSafety("localhost:3306", "kiemlai_test"))
				.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "custom_test", "feature_module_test", "novel_unit_test", "kiemlai_test" })
	@DisplayName("2. 127.0.0.1 and any *_test database are accepted")
	void shouldAcceptLoopbackAndTestSuffixDatabases(String safeDbName) {
		assertThatCode(() -> TestDatabaseSupport.validateSafety("127.0.0.1", safeDbName)).doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateSafety("127.0.0.1:3306", safeDbName))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateSafety("127.0.0.1:3307", safeDbName))
				.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "kiemlai_universe", "kiemlai_production", "universe_prod", "kiemlai", "main", "prod_db",
			"test_database_not_suffix" })
	@DisplayName("3. kiemlai_universe and non-test databases are strictly rejected")
	void shouldRejectNonTestDatabases(String dangerousDbName) {
		assertThatThrownBy(() -> TestDatabaseSupport.validateSafety("localhost:3306", dangerousDbName))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION")
				.hasMessageContaining(
						"Refusing to execute integration test against unsafe database name: '" + dangerousDbName + "'")
				.hasMessageContaining("end with '_test'");
	}

	@ParameterizedTest
	@ValueSource(strings = { "mysql-386f6dca-kiemlai.l.aivencloud.com:21303", "db.universe.internal",
			"192.168.1.50:3306", "10.0.0.1:3306", "remote.database.server:3306" })
	@DisplayName("4. Non-local and Aiven-style remote hosts are strictly rejected")
	void shouldRejectNonLocalHosts(String dangerousHost) {
		assertThatThrownBy(() -> TestDatabaseSupport.validateSafety(dangerousHost, "kiemlai_test"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION").hasMessageContaining(
						"Refusing to execute integration test against non-local host: '" + dangerousHost + "'");
	}

	@Test
	@DisplayName("5. DB_* environment variables are never consulted by resolvers")
	void shouldIgnoreDbEnvironmentVariables() {
		Map<String, String> dangerousEnv = new HashMap<>();
		dangerousEnv.put("DB_HOST", "mysql-386f6dca-kiemlai.l.aivencloud.com:21303");
		dangerousEnv.put("DB_NAME", "kiemlai_universe");
		dangerousEnv.put("DB_USERNAME", "avnadmin");
		dangerousEnv.put("DB_PASSWORD", "secret-production-password");
		dangerousEnv.put("DB_URL", "jdbc:mysql://mysql-386f6dca-kiemlai.l.aivencloud.com:21303/kiemlai_universe");

		// When resolving with ONLY dangerous DB_* present in env and no TEST_MYSQL_* /
		// test.mysql.*:
		String resolvedHost = TestDatabaseSupport.resolveHost(key -> null, dangerousEnv::get);
		String resolvedDb = TestDatabaseSupport.resolveDatabaseName(key -> null, dangerousEnv::get);
		String resolvedUser = TestDatabaseSupport.resolveUser(key -> null, dangerousEnv::get);

		// Host, DB, User must fall back to safe local defaults, NOT dangerous DB_*
		assertThat(resolvedHost).isEqualTo("localhost:3306");
		assertThat(resolvedDb).isEqualTo("kiemlai_test");
		assertThat(resolvedUser).isEqualTo("root");

		// Password resolver must fail fast rather than returning DB_PASSWORD
		assertThatThrownBy(() -> TestDatabaseSupport.resolvePassword(key -> null, dangerousEnv::get))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION")
				.hasMessageContaining("MySQL test password is required but missing");
	}

	@Test
	@DisplayName("6. Missing password fails fast with descriptive exception")
	void shouldFailFastWhenPasswordMissing() {
		assertThatThrownBy(() -> TestDatabaseSupport.resolvePassword(key -> null, key -> null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining(
						"CRITICAL TEST DATABASE SAFETY VIOLATION: MySQL test password is required but missing");
	}

	@Test
	@DisplayName("7. System properties and TEST_MYSQL_* environment variables are correctly prioritized")
	void shouldPrioritizeTestPropertiesAndTestEnvVars() {
		Map<String, String> sysProps = new HashMap<>();
		sysProps.put("test.mysql.host", "localhost:3307");
		sysProps.put("test.mysql.db", "custom_module_test");
		sysProps.put("test.mysql.user", "test_runner");
		sysProps.put("test.mysql.pass", "secret_test_pass");

		Map<String, String> envVars = new HashMap<>();
		envVars.put("TEST_MYSQL_HOST", "127.0.0.1:3306");
		envVars.put("TEST_MYSQL_DB", "env_test");
		envVars.put("TEST_MYSQL_USER", "env_user");
		envVars.put("TEST_MYSQL_PASS", "env_pass");

		// System properties take priority over environment variables
		assertThat(TestDatabaseSupport.resolveHost(sysProps::get, envVars::get)).isEqualTo("localhost:3307");
		assertThat(TestDatabaseSupport.resolveDatabaseName(sysProps::get, envVars::get))
				.isEqualTo("custom_module_test");
		assertThat(TestDatabaseSupport.resolveUser(sysProps::get, envVars::get)).isEqualTo("test_runner");
		assertThat(TestDatabaseSupport.resolvePassword(sysProps::get, envVars::get)).isEqualTo("secret_test_pass");

		// When system properties are absent, environment variables are used
		assertThat(TestDatabaseSupport.resolveHost(key -> null, envVars::get)).isEqualTo("127.0.0.1:3306");
		assertThat(TestDatabaseSupport.resolveDatabaseName(key -> null, envVars::get)).isEqualTo("env_test");
		assertThat(TestDatabaseSupport.resolveUser(key -> null, envVars::get)).isEqualTo("env_user");
		assertThat(TestDatabaseSupport.resolvePassword(key -> null, envVars::get)).isEqualTo("env_pass");
	}

	@Test
	@DisplayName("8. createTestDataSource and resetTestDatabase fail fast against non-test databases")
	void shouldRejectDangerousDatabaseInDirectDataSourceHelpers() {
		assertThatThrownBy(() -> TestDatabaseSupport.createTestDataSource("kiemlai_universe"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION").hasMessageContaining(
						"Refusing to execute integration test against unsafe database name: 'kiemlai_universe'");

		assertThatThrownBy(() -> TestDatabaseSupport.resetTestDatabase("kiemlai_universe"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION").hasMessageContaining(
						"Refusing to execute integration test against unsafe database name: 'kiemlai_universe'");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"arbitrary_test",
			"unapproved_module_test",
			"customer_data_test",
			"prod_backup_test",
			"random_test",
			"other_test",
			"my_special_test",
			"kiemlai_unknown_backfill_test",
			"arbitrary_backfill_test"
	})
	@DisplayName("9. validateDestructiveResetTarget rejects unapproved databases even if ending in _test")
	void shouldRejectUnapprovedDatabasesInDestructiveReset(String unapprovedDb) {
		assertThatThrownBy(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", unapprovedDb))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION")
				.hasMessageContaining("Refusing destructive database reset on unapproved database: '" + unapprovedDb + "'")
				.hasMessageContaining("must be an explicitly allowlisted test schema");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"db; DROP TABLE users;--_test",
			"db`DROP`_test",
			"db\\_test",
			"test db_test",
			"db'--_test",
			"db/*comment*/_test"
	})
	@DisplayName("10. validateDestructiveResetTarget rejects SQL injection and invalid characters")
	void shouldRejectSqlInjectionInDestructiveReset(String maliciousDb) {
		assertThatThrownBy(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", maliciousDb))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION")
				.hasMessageContaining("Refusing to execute integration test against unsafe database name");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"localhost:3307",
			"127.0.0.1:3307",
			"localhost:8080",
			"127.0.0.1:21303"
	})
	@DisplayName("11. validateDestructiveResetTarget rejects non-standard ports for destructive reset")
	void shouldRejectNonStandardPortInDestructiveReset(String hostWithNonStandardPort) {
		assertThatThrownBy(() -> TestDatabaseSupport.validateDestructiveResetTarget(hostWithNonStandardPort, "kiemlai_test"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION")
				.hasMessageContaining("Port must be strictly 3306");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"remote.mysql.internal:3306",
			"192.168.1.100:3306",
			"10.0.0.1:3306"
	})
	@DisplayName("12. validateDestructiveResetTarget rejects remote hosts for destructive reset")
	void shouldRejectRemoteHostsInDestructiveReset(String remoteHost) {
		assertThatThrownBy(() -> TestDatabaseSupport.validateDestructiveResetTarget(remoteHost, "kiemlai_test"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CRITICAL TEST DATABASE SAFETY VIOLATION")
				.hasMessageContaining("Refusing destructive database reset on non-loopback host");
	}

	@Test
	@DisplayName("13. validateDestructiveResetTarget accepts allowlisted test databases on loopback:3306")
	void shouldAcceptAllowlistedTestDatabasesInDestructiveReset() {
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", "kiemlai_test"))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("127.0.0.1:3306", "kiemlai_test"))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost", "kiemlai_test"))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", "kiemlai_screen_schema_test"))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("127.0.0.1:3306", "kiemlai_community_posts_schema_test"))
				.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"kiemlai_test",
			"kiemlai_community_posts_schema_test",
			"kiemlai_identity_schema_test",
			"kiemlai_interaction_comment_schema_test",
			"kiemlai_interaction_report_schema_test",
			"kiemlai_interaction_schema_test",
			"kiemlai_media_client_tag_schema_test",
			"kiemlai_media_schema_test",
			"kiemlai_media_image_variant_schema_test",
			"kiemlai_media_v89_schema_test",
			"kiemlai_notification_schema_test",
			"kiemlai_novel_narration_audio_failure_schema_test",
			"kiemlai_novel_narration_audio_schema_test",
			"kiemlai_novel_narration_segment_schema_test",
			"kiemlai_novel_chapter_bookmark_schema_test",
			"kiemlai_novel_reading_history_schema_test",
			"kiemlai_novel_reading_progress_schema_test",
			"kiemlai_novel_chapter_revision_schema_test",
			"kiemlai_novel_managed_voice_schema_test",
			"kiemlai_screen_schema_test",
			"kiemlai_wiki_appreciation_schema_test",
			"kiemlai_wiki_cover_schema_test",
			"kiemlai_wiki_image_schema_test",
			"kiemlai_wiki_saved_article_schema_test"
	})
	@DisplayName("13a. validateDestructiveResetTarget accepts all 24 baseline approved test databases on loopback:3306")
	void shouldAcceptAll24BaselineApprovedTestDatabasesInDestructiveReset(String baselineSchema) {
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", baselineSchema))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("127.0.0.1:3306", baselineSchema))
				.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"kiemlai_identity_v76_migration_test",
			"kiemlai_identity_avatar_schema_test",
			"kiemlai_interaction_comments_test",
			"kiemlai_comments_backfill_test",
			"kiemlai_interaction_reports_test",
			"kiemlai_interaction_v72_test",
			"kiemlai_interaction_v73_test",
			"kiemlai_v78_migration_test",
			"kiemlai_reactions_flyway_test",
			"kiemlai_media_client_tag_v62_test",
			"kiemlai_media_constraints_test",
			"kiemlai_media_variant_schema_test",
			"kiemlai_notification_v74_test",
			"kiemlai_narration_failure_constraints_test",
			"kiemlai_narration_failure_schema_test",
			"kiemlai_narration_audio_constraints_test",
			"kiemlai_narration_audio_schema_test",
			"kiemlai_narration_segment_constraints_test",
			"kiemlai_narration_segment_schema_test",
			"kiemlai_chapter_bookmarks_constraints_test",
			"kiemlai_chapter_bookmarks_schema_test",
			"kiemlai_reading_history_constraints_test",
			"kiemlai_reading_history_schema_test",
			"kiemlai_reading_progress_constraints_test",
			"kiemlai_reading_progress_schema_test",
			"kiemlai_backfill_test",
			"kiemlai_clean_test",
			"kiemlai_integration_test",
			"kiemlai_managed_voice_constraints_test",
			"kiemlai_managed_voice_schema_test",
			"kiemlai_wiki_appreciation_v57_v59_test",
			"kiemlai_wiki_cover_v60_test",
			"kiemlai_wiki_cover_v61_test",
			"kiemlai_wiki_cover_orphan_v63_test",
			"kiemlai_wiki_saved_articles_schema_test"
	})
	@DisplayName("13b. validateDestructiveResetTarget accepts all 35 approved migration test databases on loopback:3306")
	void shouldAcceptAll35ApprovedMigrationTestDatabasesInDestructiveReset(String migrationSchema) {
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", migrationSchema))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("127.0.0.1:3306", migrationSchema))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("13c. validateDestructiveResetTarget accepts kiemlai_comments_backfill_test specifically on loopback:3306")
	void shouldAcceptCommentsBackfillTestDatabaseSpecifically() {
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("localhost:3306", "kiemlai_comments_backfill_test"))
				.doesNotThrowAnyException();
		assertThatCode(() -> TestDatabaseSupport.validateDestructiveResetTarget("127.0.0.1:3306", "kiemlai_comments_backfill_test"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("14. configureDynamicProperties with functional lookups pins Flyway without mutating System properties")
	void shouldPinFlywayPropertiesWithoutMutatingSystemProperties() {
		Map<String, Supplier<Object>> props = new HashMap<>();
		DynamicPropertyRegistry registry = props::put;

		Map<String, String> sysProps = Map.of(
				"test.mysql.host", "localhost:3306",
				"test.mysql.db", "kiemlai_test",
				"test.mysql.user", "custom_user",
				"test.mysql.pass", "custom_pass"
		);

		TestDatabaseSupport.configureDynamicProperties(registry, sysProps::get, key -> null);

		String dsUrl = (String) props.get("spring.datasource.url").get();
		String flywayUrl = (String) props.get("spring.flyway.url").get();
		String dsUser = (String) props.get("spring.datasource.username").get();
		String flywayUser = (String) props.get("spring.flyway.user").get();
		String dsPass = (String) props.get("spring.datasource.password").get();
		String flywayPass = (String) props.get("spring.flyway.password").get();

		assertThat(flywayUrl).isEqualTo(dsUrl).contains("localhost:3306/kiemlai_test");
		assertThat(flywayUser).isEqualTo(dsUser).isEqualTo("custom_user");
		assertThat(flywayPass).isEqualTo(dsPass).isEqualTo("custom_pass");
		assertThat(props.get("spring.flyway.driver-class-name").get()).isEqualTo("com.mysql.cj.jdbc.Driver");
	}
}
