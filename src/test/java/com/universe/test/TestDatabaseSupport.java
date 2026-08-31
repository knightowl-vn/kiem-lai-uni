package com.universe.test;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Hardened database configuration support for integration tests.
 * <p>
 * Guarantees that integration tests can NEVER accidentally connect to or modify
 * real/production databases (e.g. via inherited DB_* environment variables).
 */
public final class TestDatabaseSupport {

    private static final String DEFAULT_HOST = "localhost:3306";
    private static final String DEFAULT_DB = "kiemlai_test";
    private static final String DEFAULT_USER = "root";
    private static final String DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";
    private static final Pattern SAFE_DATABASE_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_]+_test$");

    private static final Pattern SAFE_HOST_PATTERN = Pattern.compile(
            "^(localhost|127\\.0\\.0\\.1)(:\\d+)?$",
            Pattern.CASE_INSENSITIVE
    );

    private TestDatabaseSupport() {
    }

    public static String resolveHost() {
        return resolveHost(System::getProperty, System::getenv);
    }

    static String resolveHost(Function<String, String> sysProps, Function<String, String> envVars) {
        String host = sysProps.apply("test.mysql.host");
        if (host != null && !host.isBlank()) {
            return host.trim();
        }
        String envHost = envVars.apply("TEST_MYSQL_HOST");
        if (envHost != null && !envHost.isBlank()) {
            return envHost.trim();
        }
        return DEFAULT_HOST;
    }

    public static String resolveDatabaseName() {
        return resolveDatabaseName(System::getProperty, System::getenv);
    }

    static String resolveDatabaseName(Function<String, String> sysProps, Function<String, String> envVars) {
        String db = sysProps.apply("test.mysql.db");
        if (db != null && !db.isBlank()) {
            return db.trim();
        }
        String envDb = envVars.apply("TEST_MYSQL_DB");
        if (envDb != null && !envDb.isBlank()) {
            return envDb.trim();
        }
        return DEFAULT_DB;
    }

    public static String resolveUser() {
        return resolveUser(System::getProperty, System::getenv);
    }

    static String resolveUser(Function<String, String> sysProps, Function<String, String> envVars) {
        String user = sysProps.apply("test.mysql.user");
        if (user != null && !user.isBlank()) {
            return user.trim();
        }
        String envUser = envVars.apply("TEST_MYSQL_USER");
        if (envUser != null && !envUser.isBlank()) {
            return envUser.trim();
        }
        return DEFAULT_USER;
    }

    public static String resolvePassword() {
        return resolvePassword(System::getProperty, System::getenv);
    }

    static String resolvePassword(Function<String, String> sysProps, Function<String, String> envVars) {
        String pass = sysProps.apply("test.mysql.pass");
        if (pass != null && !pass.isBlank()) {
            return pass;
        }
        String envPass = envVars.apply("TEST_MYSQL_PASS");
        if (envPass != null && !envPass.isBlank()) {
            return envPass;
        }
        String rootPass = envVars.apply("MYSQL_ROOT_PASSWORD");
        if (rootPass != null && !rootPass.isBlank()) {
            return rootPass;
        }
        throw new IllegalStateException(
                "CRITICAL TEST DATABASE SAFETY VIOLATION: MySQL test password is required but missing. "
                        + "Please set system property 'test.mysql.pass' or environment variable 'TEST_MYSQL_PASS' / 'MYSQL_ROOT_PASSWORD'."
        );
    }

    public static void validateSafety(String host, String dbName) {
        if (host == null || host.isBlank() || !SAFE_HOST_PATTERN.matcher(host.trim()).matches()) {
            throw new IllegalStateException(
                    "CRITICAL TEST DATABASE SAFETY VIOLATION: Refusing to execute integration test against non-local host: '"
                            + host + "'. Tests may only target localhost or 127.0.0.1."
            );
        }

        if (dbName == null || dbName.isBlank()) {
            throw new IllegalStateException(
                    "CRITICAL TEST DATABASE SAFETY VIOLATION: Test database name cannot be empty."
            );
        }

        String normalizedDb = dbName.trim();

        if (!SAFE_DATABASE_PATTERN.matcher(normalizedDb).matches()) {
            throw new IllegalStateException(
                    "CRITICAL TEST DATABASE SAFETY VIOLATION: "
                            + "Refusing to execute integration test against unsafe database name: '"
                            + dbName
                            + "'. Database name must contain only letters, digits, underscores "
                            + "and end with '_test'."
            );
        }
    }

    public static String resolveJdbcUrl() {
        String host = resolveHost();
        String dbName = resolveDatabaseName();
        validateSafety(host, dbName);
        return "jdbc:mysql://" + host + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    public static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        String host = resolveHost();
        String dbName = resolveDatabaseName();
        validateSafety(host, dbName);
        String password = resolvePassword();
        String user = resolveUser();
        String url = "jdbc:mysql://" + host + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> DRIVER_CLASS_NAME);
    }

    public static javax.sql.DataSource createTestDataSource(String dbName) {
        String host = resolveHost();
        validateSafety(host, dbName);
        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ds.setDriverClassName(DRIVER_CLASS_NAME);
        ds.setUrl("jdbc:mysql://" + host + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        ds.setUsername(resolveUser());
        ds.setPassword(resolvePassword());
        return ds;
    }

    public static void resetTestDatabase(String dbName) {
        String host = resolveHost();
        validateSafety(host, dbName);
        org.springframework.jdbc.datasource.DriverManagerDataSource rootDs =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        rootDs.setDriverClassName(DRIVER_CLASS_NAME);
        rootDs.setUrl("jdbc:mysql://" + host + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        rootDs.setUsername(resolveUser());
        rootDs.setPassword(resolvePassword());

        org.springframework.jdbc.core.JdbcTemplate rootJdbc =
                new org.springframework.jdbc.core.JdbcTemplate(rootDs);
        rootJdbc.execute("DROP DATABASE IF EXISTS " + dbName);
        rootJdbc.execute("CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
}
