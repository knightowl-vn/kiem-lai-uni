package com.universe.screen.infrastructure.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict test-only database target guard for Screen and Media MySQL integration verification (MS-08E3-S4-MYSQL-C5).
 *
 * <p>Enforces that tests can NEVER execute against anything other than:
 * <ul>
 *   <li>Host: {@code localhost} or {@code 127.0.0.1}</li>
 *   <li>Port: {@code 3306} (explicitly or via default)</li>
 *   <li>Database: {@code kiemlai_test}</li>
 *   <li>User: {@code root} (established local test account)</li>
 *   <li>Credentials: isolated test credentials (password redacted in diagnostics)</li>
 * </ul>
 *
 * <p>Rejects remote hosts, non-standard ports, other local databases (even if ending with _test),
 * production database names, case variations, and malformed identifiers.
 */
public final class ScreenTestDatabaseTargetGuard {

    public static final String EXACT_DATABASE_NAME = "kiemlai_test";
    public static final int EXACT_PORT = 3306;
    public static final String DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";

    private static final Pattern STRICT_HOST_PORT_PATTERN = Pattern.compile(
            "^(localhost|127\\.0\\.0\\.1)(?::(\\d+))?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STRICT_HOST_ONLY_PATTERN = Pattern.compile(
            "^(localhost|127\\.0\\.0\\.1)$",
            Pattern.CASE_INSENSITIVE
    );

    private ScreenTestDatabaseTargetGuard() {
    }

    /**
     * Validates that the provided host and database name strictly comply with loopback, port 3306,
     * and exact database name 'kiemlai_test'.
     *
     * @param host host string, optionally with port (e.g. "localhost:3306" or "127.0.0.1")
     * @param dbName target database name (must be exact "kiemlai_test")
     * @throws IllegalStateException if the target is invalid, non-local, non-standard port, or not kiemlai_test
     */
    public static void validateTarget(String host, String dbName) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("SAFETY BLOCK: Database host cannot be null or blank");
        }
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalStateException("SAFETY BLOCK: Database name cannot be null or blank");
        }

        String trimmedHost = host.trim();
        Matcher matcher = STRICT_HOST_PORT_PATTERN.matcher(trimmedHost);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "SAFETY BLOCK: Target host is not authorized. Must be loopback (localhost or 127.0.0.1)"
            );
        }

        String portGroup = matcher.group(2);
        if (portGroup != null) {
            int port;
            try {
                port = Integer.parseInt(portGroup);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("SAFETY BLOCK: Malformed port in target host. Must be loopback (localhost or 127.0.0.1)");
            }
            if (port != EXACT_PORT) {
                throw new IllegalStateException(
                        "SAFETY BLOCK: Target port [" + port + "] is not authorized. Must be strictly " + EXACT_PORT
                );
            }
        }

        String trimmedDb = dbName.trim();
        if (!EXACT_DATABASE_NAME.equals(trimmedDb)) {
            throw new IllegalStateException(
                    "SAFETY BLOCK: Target database [" + trimmedDb + "] is not authorized. Must be strictly '" + EXACT_DATABASE_NAME + "'"
            );
        }
    }

    /**
     * Isolated confidential credentials wrapper with guaranteed password redaction in toString() and diagnostics.
     */
    public record TargetCredentials(String username, String password) {
        public TargetCredentials {
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("SAFETY BLOCK: Test database user cannot be null or blank");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("SAFETY BLOCK: Test database password cannot be null or blank");
            }
        }

        @Override
        public String toString() {
            return "TargetCredentials[username=" + username + ", password=[PROTECTED]]";
        }
    }

    /**
     * Immutable validated target encapsulation for safe integration test execution.
     * Enforces constructor integrity so JDBC URL cannot diverge from validated parameters.
     */
    public static final class ValidatedTarget {
        private final String host;
        private final int port;
        private final String databaseName;
        private final String jdbcUrl;
        private final TargetCredentials credentials;

        private static String validateHostOnly(String host) {
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("SAFETY BLOCK: host cannot be null or blank");
            }
            String trimmed = host.trim();
            if (!STRICT_HOST_ONLY_PATTERN.matcher(trimmed).matches()) {
                throw new IllegalStateException(
                        "SAFETY BLOCK: Target host is not authorized. Must be strictly loopback (localhost or 127.0.0.1) without port, scheme, path or query"
                );
            }
            return trimmed.toLowerCase();
        }

        public ValidatedTarget(String host, int port, String databaseName, TargetCredentials credentials) {
            this.host = validateHostOnly(host);
            if (port != EXACT_PORT) {
                throw new IllegalStateException("SAFETY BLOCK: port must be strictly " + EXACT_PORT);
            }
            if (databaseName == null || !EXACT_DATABASE_NAME.equals(databaseName.trim())) {
                throw new IllegalStateException("SAFETY BLOCK: databaseName must be strictly '" + EXACT_DATABASE_NAME + "'");
            }
            this.port = port;
            this.databaseName = EXACT_DATABASE_NAME;
            this.credentials = Objects.requireNonNull(credentials, "credentials cannot be null");
            this.jdbcUrl = "jdbc:mysql://" + this.host + ":" + this.port + "/" + this.databaseName
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        }

        public ValidatedTarget(String host, int port, String databaseName, String jdbcUrl, TargetCredentials credentials) {
            this(host, port, databaseName, credentials);
            if (!this.jdbcUrl.equals(jdbcUrl)) {
                throw new IllegalStateException(
                        "SAFETY BLOCK: Provided jdbcUrl does not match the canonical validated target parameters"
                );
            }
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public String databaseName() {
            return databaseName;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String username() {
            return credentials.username();
        }

        public TargetCredentials credentials() {
            return credentials;
        }

        @Override
        public String toString() {
            return "ValidatedTarget[host=" + host + ", port=" + port + ", databaseName=" + databaseName
                    + ", jdbcUrl=" + jdbcUrl + ", username=" + credentials.username() + ", credentials=[PROTECTED]]";
        }

        /**
         * Configures dynamic properties ensuring BOTH datasource and Flyway are pinned to the
         * exact same validated URL and credentials.
         */
        public void configureDynamicProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", () -> jdbcUrl);
            registry.add("spring.datasource.username", () -> credentials.username());
            registry.add("spring.datasource.password", () -> credentials.password());
            registry.add("spring.datasource.driver-class-name", () -> DRIVER_CLASS_NAME);
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
            registry.add("spring.datasource.hikari.minimum-idle", () -> 1);

            // Flyway MUST be pinned to the exact same validated target URL and credentials
            registry.add("spring.flyway.url", () -> jdbcUrl);
            registry.add("spring.flyway.user", () -> credentials.username());
            registry.add("spring.flyway.password", () -> credentials.password());
            registry.add("spring.flyway.driver-class-name", () -> DRIVER_CLASS_NAME);
        }
    }

    /**
     * Resolves target configuration from system properties / environment variables once, validates all
     * constraints fail-closed, and returns an immutable {@link ValidatedTarget}.
     */
    public static ValidatedTarget resolveAndValidateTarget() {
        return resolveAndValidateTarget(System::getProperty, System::getenv);
    }

    /**
     * Resolver accepting custom property/environment lookups for pure offline testing.
     * Enforces strict target validation, established root test user, and no fallback to MYSQL_ROOT_PASSWORD or DB_*.
     */
    public static ValidatedTarget resolveAndValidateTarget(
            Function<String, String> sysProps,
            Function<String, String> envVars
    ) {
        String rawHost = sysProps.apply("test.mysql.host");
        if (rawHost == null || rawHost.isBlank()) {
            rawHost = envVars.apply("TEST_MYSQL_HOST");
        }
        if (rawHost == null || rawHost.isBlank()) {
            rawHost = "localhost:3306";
        }

        String rawDb = sysProps.apply("test.mysql.db");
        if (rawDb == null || rawDb.isBlank()) {
            rawDb = envVars.apply("TEST_MYSQL_DB");
        }
        if (rawDb == null || rawDb.isBlank()) {
            rawDb = EXACT_DATABASE_NAME;
        }

        // Validate host, port, and dbName in one pass
        validateTarget(rawHost, rawDb);

        String trimmedHost = rawHost.trim();
        Matcher matcher = STRICT_HOST_PORT_PATTERN.matcher(trimmedHost);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "SAFETY BLOCK: Target host is not authorized. Must be loopback (localhost or 127.0.0.1)"
            );
        }
        String hostOnly = matcher.group(1).toLowerCase();
        int port = EXACT_PORT;

        String trimmedDb = rawDb.trim();

        // Resolve credentials: local integration testing uses established root account
        String user = "root";

        // Resolve password: NO fallback to MYSQL_ROOT_PASSWORD or production DB_*
        String pass = envVars.apply("TEST_MYSQL_PASS");
        if (pass == null || pass.isBlank()) {
            pass = sysProps.apply("test.mysql.pass");
        }
        if (pass == null || pass.isBlank()) {
            throw new IllegalStateException(
                    "SAFETY BLOCK: Test database password is required but missing. Please set environment variable 'TEST_MYSQL_PASS'."
            );
        }

        TargetCredentials credentials = new TargetCredentials(user, pass);
        return new ValidatedTarget(hostOnly, port, trimmedDb, credentials);
    }
}
