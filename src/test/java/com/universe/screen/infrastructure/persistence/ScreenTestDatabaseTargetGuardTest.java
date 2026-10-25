package com.universe.screen.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure offline unit test suite for ScreenTestDatabaseTargetGuard and ValidatedTarget (MS-08E3-S4-MYSQL-C5).
 *
 * <p>Verifies negative and positive validation paths, loopback/port-3306 enforcement,
 * fail-closed rejection, Flyway pinning, root test credential default, credential redaction,
 * constructor integrity, and Spring Environment property precedence without opening any database
 * or network connections.
 */
@DisplayName("Screen Test Database Target Guard Pure Unit Tests")
class ScreenTestDatabaseTargetGuardTest {

    @Test
    @DisplayName("1. Accepts valid loopback localhost:3306 and kiemlai_test")
    void shouldAcceptValidLoopbackTarget() {
        assertThatCode(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", "kiemlai_test"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenTestDatabaseTargetGuard.validateTarget("127.0.0.1:3306", "kiemlai_test"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost", "kiemlai_test"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScreenTestDatabaseTargetGuard.validateTarget("127.0.0.1", "kiemlai_test"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mysql.example.com:3306",
            "aiven-cloud.mysql.aiven.io:12345",
            "192.168.1.100:3306",
            "10.0.0.5:3306",
            "remotehost:3306"
    })
    @DisplayName("2. Rejects remote or non-loopback hosts")
    void shouldRejectRemoteHosts(String host) {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget(host, "kiemlai_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be loopback (localhost or 127.0.0.1)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost:3307",
            "localhost:3308",
            "127.0.0.1:3307",
            "127.0.0.1:8080"
    })
    @DisplayName("3. Rejects unexpected ports even on localhost")
    void shouldRejectUnexpectedPorts(String host) {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget(host, "kiemlai_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be strictly 3306");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kiemlai",
            "production_db",
            "other_test",
            "wiki_test",
            "novel_test",
            "kiemlai_dev",
            "kiemlai_prod"
    })
    @DisplayName("4. Rejects databases other than kiemlai_test (including production and other *_test databases)")
    void shouldRejectOtherDatabases(String dbName) {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", dbName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be strictly 'kiemlai_test'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "KIEMLAI_TEST",
            "KiemLai_Test",
            "kiemlai_TEST",
            "Kiemlai_test",
            "KIEMLAI_test"
    })
    @DisplayName("5. Rejects case variants of kiemlai_test (strictly case-sensitive)")
    void shouldRejectCaseVariantsOfKiemlaiTest(String dbName) {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", dbName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be strictly 'kiemlai_test'");
    }

    @Test
    @DisplayName("6. Rejects null or blank host")
    void shouldRejectNullOrBlankHost() {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget(null, "kiemlai_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database host cannot be null or blank");
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("   ", "kiemlai_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database host cannot be null or blank");
    }

    @Test
    @DisplayName("7. Rejects null or blank database name")
    void shouldRejectNullOrBlankDatabaseName() {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database name cannot be null or blank");
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database name cannot be null or blank");
    }

    // =============================================================================================
    // Single Validated Target Resolution & Flyway Pinning Contract Tests (Offline Unit Tests)
    // =============================================================================================

    @Test
    @DisplayName("8. resolveAndValidateTarget: accepts localhost:3306/kiemlai_test with root user")
    void shouldAcceptLocalhost3306InResolveAndValidate() {
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "localhost:3306",
                "test.mysql.db", "kiemlai_test",
                "test.mysql.pass", "secret_pass"
        );

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null);

        assertThat(target.host()).isEqualTo("localhost");
        assertThat(target.port()).isEqualTo(3306);
        assertThat(target.databaseName()).isEqualTo("kiemlai_test");
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.credentials().password()).isEqualTo("secret_pass");
        assertThat(target.jdbcUrl())
                .isEqualTo("jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    @Test
    @DisplayName("9. resolveAndValidateTarget: accepts 127.0.0.1:3306/kiemlai_test with root user")
    void shouldAccept1270013306InResolveAndValidate() {
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "127.0.0.1:3306",
                "test.mysql.pass", "secret_pass"
        );

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null);

        assertThat(target.host()).isEqualTo("127.0.0.1");
        assertThat(target.port()).isEqualTo(3306);
        assertThat(target.databaseName()).isEqualTo("kiemlai_test");
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.credentials().password()).isEqualTo("secret_pass");
        assertThat(target.jdbcUrl())
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    @Test
    @DisplayName("10. resolveAndValidateTarget: accepts omitted port unambiguously defaulting to 3306")
    void shouldAcceptOmittedPortDefaultingTo3306() {
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "localhost",
                "test.mysql.pass", "secret_pass"
        );

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null);

        assertThat(target.port()).isEqualTo(3306);
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.jdbcUrl())
                .isEqualTo("jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");

        Map<String, String> sysPropsIp = Map.of(
                "test.mysql.host", "127.0.0.1",
                "test.mysql.pass", "secret_pass"
        );

        ScreenTestDatabaseTargetGuard.ValidatedTarget targetIp =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysPropsIp::get, key -> null);

        assertThat(targetIp.port()).isEqualTo(3306);
        assertThat(targetIp.username()).isEqualTo("root");
        assertThat(targetIp.jdbcUrl())
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "remote.database.org:3306",
            "10.0.0.1:3306",
            "192.168.1.1:3306",
            "db-node.internal:3306"
    })
    @DisplayName("11. resolveAndValidateTarget: rejects remote hostnames")
    void shouldRejectRemoteHostnamesInResolveAndValidate(String remoteHost) {
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", remoteHost,
                "test.mysql.pass", "secret_pass"
        );

        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be loopback (localhost or 127.0.0.1)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost:3307",
            "127.0.0.1:3307",
            "localhost:8080",
            "127.0.0.1:21303"
    })
    @DisplayName("12. resolveAndValidateTarget: rejects port 3307 and non-standard ports")
    void shouldRejectNonStandardPortsInResolveAndValidate(String nonStandardHost) {
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", nonStandardHost,
                "test.mysql.pass", "secret_pass"
        );

        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be strictly 3306");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "other_test",
            "novel_test",
            "wiki_test",
            "community_test",
            "kiemlai_universe",
            "kiemlai_production",
            "production_test"
    })
    @DisplayName("13. resolveAndValidateTarget: rejects any database other than exact kiemlai_test (including other *_test)")
    void shouldRejectAnyDatabaseOtherThanExactKiemlaiTest(String otherDb) {
        Map<String, String> sysProps = Map.of(
                "test.mysql.db", otherDb,
                "test.mysql.pass", "secret_pass"
        );

        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be strictly 'kiemlai_test'");
    }

    @Test
    @DisplayName("14. resolveAndValidateTarget: allows missing user and rejects missing password or malformed port")
    void shouldRejectMalformedTargetConfiguration() {
        // Missing user: allowed and defaults to root
        Map<String, String> noUser = Map.of(
                "test.mysql.host", "localhost:3306",
                "test.mysql.pass", "secret_pass"
        );
        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(noUser::get, key -> null);
        assertThat(target.username()).isEqualTo("root");

        // Missing password
        Map<String, String> noPass = Map.of(
                "test.mysql.host", "localhost:3306"
        );
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(noPass::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: Test database password is required but missing");

        // Malformed port
        Map<String, String> malformedPort = Map.of(
                "test.mysql.host", "localhost:not_a_number",
                "test.mysql.pass", "secret_pass"
        );
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(malformedPort::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be loopback (localhost or 127.0.0.1)");
    }

    @Test
    @DisplayName("15. ValidatedTarget: proves datasource and Flyway derive from identical validated URL and credentials")
    void shouldProveDatasourceAndFlywayDeriveFromSameValidatedTarget() {
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "localhost:3306",
                "test.mysql.db", "kiemlai_test",
                "test.mysql.pass", "secure_test_password"
        );

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null);

        Map<String, Supplier<Object>> registeredProps = new HashMap<>();
        DynamicPropertyRegistry registry = registeredProps::put;

        target.configureDynamicProperties(registry);

        String expectedUrl = "jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String expectedUser = "root";
        String expectedPass = "secure_test_password";
        String expectedDriver = "com.mysql.cj.jdbc.Driver";

        // Datasource properties verification
        assertThat(registeredProps.get("spring.datasource.url").get()).isEqualTo(expectedUrl);
        assertThat(registeredProps.get("spring.datasource.username").get()).isEqualTo(expectedUser);
        assertThat(registeredProps.get("spring.datasource.password").get()).isEqualTo(expectedPass);
        assertThat(registeredProps.get("spring.datasource.driver-class-name").get()).isEqualTo(expectedDriver);

        // Flyway properties verification — MUST match datasource identically
        assertThat(registeredProps.get("spring.flyway.url").get()).isEqualTo(expectedUrl);
        assertThat(registeredProps.get("spring.flyway.user").get()).isEqualTo(expectedUser);
        assertThat(registeredProps.get("spring.flyway.password").get()).isEqualTo(expectedPass);
        assertThat(registeredProps.get("spring.flyway.driver-class-name").get()).isEqualTo(expectedDriver);

        // Direct identity verification: Flyway URL is identical to datasource URL
        assertThat(registeredProps.get("spring.flyway.url").get())
                .isEqualTo(registeredProps.get("spring.datasource.url").get());
    }

    @Test
    @DisplayName("16. ValidatedTarget: proves separate external Flyway URL cannot override validated test target")
    void shouldProveExternalFlywayUrlCannotOverrideValidatedTarget() {
        // Suppose an external environment or system property attempts to redirect Flyway
        String externalMaliciousFlywayUrl = "jdbc:mysql://malicious-host.com:3306/production_db";

        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "127.0.0.1:3306",
                "test.mysql.db", "kiemlai_test",
                "test.mysql.pass", "secure_pass",
                "spring.flyway.url", externalMaliciousFlywayUrl
        );

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null);

        Map<String, Supplier<Object>> registeredProps = new HashMap<>();
        DynamicPropertyRegistry registry = registeredProps::put;

        target.configureDynamicProperties(registry);

        // The pinned spring.flyway.url in DynamicPropertyRegistry strictly uses the validated target,
        // completely ignoring and overriding any external spring.flyway.url
        assertThat(registeredProps.get("spring.flyway.url").get())
                .isNotEqualTo(externalMaliciousFlywayUrl)
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    // =============================================================================================
    // C4-A / C5-A / C5-B: Target & Credential Safety Tests (Offline Unit Tests)
    // =============================================================================================

    @Test
    @DisplayName("A1. ValidatedTarget and TargetCredentials toString() redacts password")
    void shouldRedactPasswordInToString() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "super_secret_test_pass");
        assertThat(credentials.toString())
                .doesNotContain("super_secret_test_pass")
                .contains("password=[PROTECTED]")
                .contains("username=test_user");

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                new ScreenTestDatabaseTargetGuard.ValidatedTarget("localhost", 3306, "kiemlai_test", credentials);
        assertThat(target.toString())
                .doesNotContain("super_secret_test_pass")
                .contains("credentials=[PROTECTED]")
                .contains("host=localhost")
                .contains("port=3306")
                .contains("databaseName=kiemlai_test");
    }

    @Test
    @DisplayName("A2. ValidatedTarget constructor rejects mismatched JDBC URL")
    void shouldRejectMismatchedJdbcUrlInConstructor() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");
        String maliciousUrl = "jdbc:mysql://attacker.com:3306/prod_db";
        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "localhost", 3306, "kiemlai_test", maliciousUrl, credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: Provided jdbcUrl does not match the canonical validated target parameters");
    }

    @Test
    @DisplayName("A3. ValidatedTarget constructor rejects invalid host, port, or dbName")
    void shouldRejectInvalidParametersInValidatedTargetConstructor() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");

        // Non-standard port
        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "localhost", 3307, "kiemlai_test", credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port must be strictly 3306");

        // Non-standard database
        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "localhost", 3306, "other_db", credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("databaseName must be strictly 'kiemlai_test'");

        // Blank host
        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "   ", 3306, "kiemlai_test", credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host cannot be null or blank");
    }

    @Test
    @DisplayName("A4. ValidatedTarget does not expose raw password accessor method")
    void shouldNotExposeRawPasswordAccessor() {
        boolean hasPasswordMethod = Arrays.stream(
                ScreenTestDatabaseTargetGuard.ValidatedTarget.class.getMethods()
        ).anyMatch(m -> m.getName().equalsIgnoreCase("password") || m.getName().equalsIgnoreCase("getPassword"));
        assertThat(hasPasswordMethod).isFalse();
    }

    @Test
    @DisplayName("A5. Rejected-target exceptions do not contain sensitive password")
    void rejectedTargetExceptionsDoNotContainPassword() {
        String sensitivePass = "top_secret_sensitive_password_12345";
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "remote.host.com:3306",
                "test.mysql.pass", sensitivePass
        );
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(sensitivePass));
    }

    // =============================================================================================
    // C5-A: Direct Constructor Host Integrity Tests (Offline Unit Tests)
    // =============================================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "remote.example.com",
            "192.168.1.10",
            "127.0.0.2",
            "localhost:3306",
            "localhost:3307",
            "jdbc:mysql://localhost",
            "localhost/path",
            "localhost?query=param",
            "http://localhost",
            "127.0.0.1:3306",
            "10.0.0.1",
            "172.16.0.1"
    })
    @DisplayName("C5-A1. Direct ValidatedTarget constructor strictly rejects unauthorized host strings")
    void shouldRejectUnauthorizedHostStringsInDirectConstructor(String invalidHost) {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");

        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                invalidHost, 3306, "kiemlai_test", credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: Target host is not authorized. Must be strictly loopback (localhost or 127.0.0.1) without port, scheme, path or query");
    }

    @Test
    @DisplayName("C5-A2. Direct ValidatedTarget constructor accepts valid localhost and 127.0.0.1 host-only")
    void shouldAcceptValidLoopbackHostOnlyInConstructor() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");

        ScreenTestDatabaseTargetGuard.ValidatedTarget targetLocalhost =
                new ScreenTestDatabaseTargetGuard.ValidatedTarget("localhost", 3306, "kiemlai_test", credentials);
        assertThat(targetLocalhost.host()).isEqualTo("localhost");
        assertThat(targetLocalhost.port()).isEqualTo(3306);
        assertThat(targetLocalhost.databaseName()).isEqualTo("kiemlai_test");
        assertThat(targetLocalhost.jdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");

        ScreenTestDatabaseTargetGuard.ValidatedTarget targetIp =
                new ScreenTestDatabaseTargetGuard.ValidatedTarget("127.0.0.1", 3306, "kiemlai_test", credentials);
        assertThat(targetIp.host()).isEqualTo("127.0.0.1");
        assertThat(targetIp.port()).isEqualTo(3306);
        assertThat(targetIp.databaseName()).isEqualTo("kiemlai_test");
        assertThat(targetIp.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    }

    @Test
    @DisplayName("C5-A3. Direct ValidatedTarget constructor rejects non-3306 port and non-kiemlai_test database")
    void shouldRejectInvalidPortAndDatabaseInConstructor() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");

        // Port 3307 rejected
        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "localhost", 3307, "kiemlai_test", credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: port must be strictly 3306");

        // Database another_test rejected
        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "localhost", 3306, "another_test", credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: databaseName must be strictly 'kiemlai_test'");
    }

    @Test
    @DisplayName("C5-A4. Direct secondary constructor with identical canonical URL succeeds")
    void shouldAcceptMatchingCanonicalUrlInSecondaryConstructor() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");
        String canonicalUrl = "jdbc:mysql://localhost:3306/kiemlai_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                new ScreenTestDatabaseTargetGuard.ValidatedTarget("localhost", 3306, "kiemlai_test", canonicalUrl, credentials);

        assertThat(target.jdbcUrl()).isEqualTo(canonicalUrl);
        assertThat(target.host()).isEqualTo("localhost");
    }

    // =============================================================================================
    // C5-B: Sensitive Raw JDBC URL Redaction from Exceptions (Offline Unit Tests)
    // =============================================================================================

    @Test
    @DisplayName("C5-B. ValidatedTarget constructor rejects mismatched URL and redacts sensitive credentials from exception")
    void shouldRedactSensitiveSentinelInMismatchedJdbcUrlException() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("test_user", "secret");
        String sentinel = "sentinel-do-not-log-123";
        String sensitiveMismatchedUrl = "jdbc:mysql://attacker.com:3306/db?user=admin&password=" + sentinel;

        assertThatThrownBy(() -> new ScreenTestDatabaseTargetGuard.ValidatedTarget(
                "localhost", 3306, "kiemlai_test", sensitiveMismatchedUrl, credentials
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: Provided jdbcUrl does not match the canonical validated target parameters")
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain(sentinel);
                    assertThat(ex.getMessage()).doesNotContain("attacker.com");
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.getSuppressed()).isEmpty();
                });
    }

    // =============================================================================================
    // C4-B: Target Credential Enforcement & Redaction Tests (Offline Unit Tests)
    // =============================================================================================

    @Test
    @DisplayName("B1. Missing TEST_MYSQL_USER is allowed and defaults to root")
    void shouldAllowMissingUserAndDefaultToRoot() {
        Map<String, String> sysProps = Map.of(
                "test.mysql.pass", "secret"
        );
        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null);
        assertThat(target.username()).isEqualTo("root");
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "ROOT", "Root", "rOoT"})
    @DisplayName("B2. User root in any case is accepted by TargetCredentials")
    void shouldAcceptRootUserInTargetCredentials(String rootUser) {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials(rootUser, "secret");
        assertThat(credentials.username()).isEqualTo(rootUser);
        assertThat(credentials.password()).isEqualTo("secret");
    }

    @Test
    @DisplayName("B3. Missing password throws IllegalStateException instructing to set TEST_MYSQL_PASS")
    void shouldThrowWhenPasswordMissing() {
        Map<String, String> sysProps = Map.of();
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: Test database password is required but missing. Please set environment variable 'TEST_MYSQL_PASS'.");
    }

    @Test
    @DisplayName("B4. MYSQL_ROOT_PASSWORD is not consulted as fallback")
    void shouldNotFallBackToMysqlRootPassword() {
        Map<String, String> envVars = Map.of(
                "MYSQL_ROOT_PASSWORD", "secret_root_pass"
        );
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(key -> null, envVars::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAFETY BLOCK: Test database password is required but missing. Please set environment variable 'TEST_MYSQL_PASS'.");
    }

    @Test
    @DisplayName("B5. Target resolution with valid password succeeds with root user")
    void shouldSucceedWithValidPasswordAndDefaultRootUser() {
        Map<String, String> envVars = Map.of(
                "TEST_MYSQL_PASS", "valid_test_password"
        );
        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(key -> null, envVars::get);
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.credentials().password()).isEqualTo("valid_test_password");
    }

    @Test
    @DisplayName("B6. DB_* variables are completely ignored")
    void shouldIgnoreProductionDbVariables() {
        Map<String, String> envVars = Map.of(
                "DB_HOST", "prod.mysql.server:3306",
                "DB_NAME", "kiemlai_prod",
                "DB_USERNAME", "prod_user",
                "DB_PASSWORD", "prod_secret",
                "TEST_MYSQL_PASS", "screen_pass"
        );
        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(key -> null, envVars::get);
        assertThat(target.host()).isEqualTo("localhost");
        assertThat(target.databaseName()).isEqualTo("kiemlai_test");
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.credentials().password()).isEqualTo("screen_pass");
    }

    @Test
    @DisplayName("B7. Error diagnostics during target resolution do not leak password")
    void errorDiagnosticsDoNotLeakPassword() {
        String sensitivePass = "ultra_sensitive_secret_password_abc";
        Map<String, String> sysProps = Map.of(
                "test.mysql.host", "localhost:9999",
                "test.mysql.pass", sensitivePass
        );
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.resolveAndValidateTarget(sysProps::get, key -> null))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(sensitivePass));
    }

    // =============================================================================================
    // C4-E: Spring Environment Property Precedence (Pure Offline Unit Test)
    // =============================================================================================

    @Test
    @DisplayName("E1. Spring Environment Property Precedence: Dynamic properties override system/environment properties")
    void shouldProveDynamicPropertiesTakePrecedenceInSpringEnvironment() {
        ScreenTestDatabaseTargetGuard.TargetCredentials credentials =
                new ScreenTestDatabaseTargetGuard.TargetCredentials("screen_test_user", "super_secret_test_password");
        ScreenTestDatabaseTargetGuard.ValidatedTarget target =
                new ScreenTestDatabaseTargetGuard.ValidatedTarget("localhost", 3306, "kiemlai_test", credentials);

        Map<String, Supplier<Object>> registeredProps = new HashMap<>();
        DynamicPropertyRegistry registry = registeredProps::put;
        target.configureDynamicProperties(registry);

        // Create Spring StandardEnvironment with competing lower-precedence property sources
        StandardEnvironment environment = new StandardEnvironment();

        // Simulate competing lower-precedence environment / property sources
        Map<String, Object> lowerPrecedenceProps = Map.of(
                "spring.datasource.url", "jdbc:mysql://attacker-host:3306/production_db",
                "spring.datasource.username", "prod_user",
                "spring.datasource.password", "prod_pass",
                "spring.flyway.url", "jdbc:mysql://malicious-host:3306/prod_schema",
                "spring.flyway.user", "root",
                "spring.flyway.password", "root_pass"
        );
        environment.getPropertySources().addLast(
                new MapPropertySource("lowerPrecedenceSource", lowerPrecedenceProps)
        );

        // Add dynamic properties at highest precedence (front), exactly as Spring Test runner does
        Map<String, Object> dynamicValues = new HashMap<>();
        registeredProps.forEach((key, supplier) -> dynamicValues.put(key, supplier.get()));
        environment.getPropertySources().addFirst(
                new MapPropertySource("dynamicProperties", dynamicValues)
        );

        // Verify that the environment resolves to the validated target properties, overriding lower-precedence sources
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo(target.jdbcUrl())
                .isNotEqualTo("jdbc:mysql://attacker-host:3306/production_db");
        assertThat(environment.getProperty("spring.datasource.username"))
                .isEqualTo("screen_test_user");
        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo("super_secret_test_password");
        assertThat(environment.getProperty("spring.flyway.url"))
                .isEqualTo(target.jdbcUrl())
                .isNotEqualTo("jdbc:mysql://malicious-host:3306/prod_schema");
        assertThat(environment.getProperty("spring.flyway.user"))
                .isEqualTo("screen_test_user")
                .isNotEqualTo("root");
        assertThat(environment.getProperty("spring.flyway.password"))
                .isEqualTo("super_secret_test_password")
                .isNotEqualTo("root_pass");
    }
}
