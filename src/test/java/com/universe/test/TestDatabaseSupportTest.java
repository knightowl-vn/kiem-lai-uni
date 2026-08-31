package com.universe.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
}
