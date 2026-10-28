package com.universe.test;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
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

    private static final Pattern STRICT_LOOPBACK_PATTERN = Pattern.compile(
            "^(localhost|127\\.0\\.0\\.1)(?::(\\d+))?$",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Explicit allowlist of approved test database schemas permitted for destructive reset.
     * Prevents arbitrary or unauthorized local schemas ending with '_test' from being dropped.
     * Every schema name corresponds to a source-verified, dedicated legacy migration test suite.
     */
    private static final Set<String> ALLOWED_DESTRUCTIVE_RESET_DATABASES = Set.of(
            // Primary local test database
            "kiemlai_test",

            // Baseline module schema test databases
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
            "kiemlai_wiki_saved_article_schema_test",

            // Verified legacy Flyway migration & constraint test databases (MS-08 regression allowlist)
            // Identity
            "kiemlai_identity_v76_migration_test",
            "kiemlai_identity_avatar_schema_test",

            // Interaction
            "kiemlai_interaction_comments_test",
            "kiemlai_comments_backfill_test",
            "kiemlai_interaction_reports_test",
            "kiemlai_interaction_v72_test",
            "kiemlai_interaction_v73_test",
            "kiemlai_v78_migration_test",
            "kiemlai_reactions_flyway_test",

            // Media
            "kiemlai_media_client_tag_v62_test",
            "kiemlai_media_constraints_test",
            "kiemlai_media_variant_schema_test",

            // Notification
            "kiemlai_notification_v74_test",

            // Novel - Narration
            "kiemlai_narration_failure_constraints_test",
            "kiemlai_narration_failure_schema_test",
            "kiemlai_narration_audio_constraints_test",
            "kiemlai_narration_audio_schema_test",
            "kiemlai_narration_segment_constraints_test",
            "kiemlai_narration_segment_schema_test",

            // Novel - Reader
            "kiemlai_chapter_bookmarks_constraints_test",
            "kiemlai_chapter_bookmarks_schema_test",
            "kiemlai_reading_history_constraints_test",
            "kiemlai_reading_history_schema_test",
            "kiemlai_reading_progress_constraints_test",
            "kiemlai_reading_progress_schema_test",

            // Novel - Revision
            "kiemlai_backfill_test",
            "kiemlai_clean_test",
            "kiemlai_integration_test",

            // Novel - Managed Voice
            "kiemlai_managed_voice_constraints_test",
            "kiemlai_managed_voice_schema_test",

            // Wiki
            "kiemlai_wiki_appreciation_v57_v59_test",
            "kiemlai_wiki_cover_v60_test",
            "kiemlai_wiki_cover_v61_test",
            "kiemlai_wiki_cover_orphan_v63_test",
            "kiemlai_wiki_saved_articles_schema_test"
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

    /**
     * Strict guard for destructive database operations (DROP DATABASE).
     * Enforces loopback host, standard port 3306, and an explicit allowlist of authorized test schemas.
     */
    public static void validateDestructiveResetTarget(String host, String dbName) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("CRITICAL TEST DATABASE SAFETY VIOLATION: Database host cannot be null or blank");
        }
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalStateException("CRITICAL TEST DATABASE SAFETY VIOLATION: Database name cannot be null or blank");
        }

        String trimmedHost = host.trim();
        Matcher matcher = STRICT_LOOPBACK_PATTERN.matcher(trimmedHost);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "CRITICAL TEST DATABASE SAFETY VIOLATION: Refusing destructive database reset on non-loopback host: '"
                            + trimmedHost + "'. Host must be localhost or 127.0.0.1."
            );
        }

        String portGroup = matcher.group(2);
        if (portGroup != null) {
            int port;
            try {
                port = Integer.parseInt(portGroup);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("CRITICAL TEST DATABASE SAFETY VIOLATION: Malformed port in host: '" + trimmedHost + "'");
            }
            if (port != 3306) {
                throw new IllegalStateException(
                        "CRITICAL TEST DATABASE SAFETY VIOLATION: Refusing destructive database reset on non-standard port: "
                                + port + ". Port must be strictly 3306."
                );
            }
        }

        String trimmedDb = dbName.trim();
        if (!SAFE_DATABASE_PATTERN.matcher(trimmedDb).matches()) {
            throw new IllegalStateException(
                    "CRITICAL TEST DATABASE SAFETY VIOLATION: "
                            + "Refusing to execute integration test against unsafe database name: '"
                            + dbName
                            + "'. Database name must contain only letters, digits, underscores "
                            + "and end with '_test'."
            );
        }
        if (!ALLOWED_DESTRUCTIVE_RESET_DATABASES.contains(trimmedDb)) {
            throw new IllegalStateException(
                    "CRITICAL TEST DATABASE SAFETY VIOLATION: Refusing destructive database reset on unapproved database: '"
                            + trimmedDb + "'. Database must be an explicitly allowlisted test schema, not merely matching generic '*_test'."
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
        configureDynamicProperties(registry, System::getProperty, System::getenv);
    }

    public static void configureDynamicProperties(
            DynamicPropertyRegistry registry,
            Function<String, String> sysProps,
            Function<String, String> envVars
    ) {
        String host = resolveHost(sysProps, envVars);
        String dbName = resolveDatabaseName(sysProps, envVars);
        validateSafety(host, dbName);
        String password = resolvePassword(sysProps, envVars);
        String user = resolveUser(sysProps, envVars);
        String url = "jdbc:mysql://" + host + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> DRIVER_CLASS_NAME);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);

        // Pin Flyway to the exact same validated target URL and credentials as the datasource
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
        registry.add("spring.flyway.driver-class-name", () -> DRIVER_CLASS_NAME);
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
        validateDestructiveResetTarget(host, dbName);
        String trimmedDb = dbName.trim();
        org.springframework.jdbc.datasource.DriverManagerDataSource rootDs =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        rootDs.setDriverClassName(DRIVER_CLASS_NAME);
        rootDs.setUrl("jdbc:mysql://" + host + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        rootDs.setUsername(resolveUser());
        rootDs.setPassword(resolvePassword());

        org.springframework.jdbc.core.JdbcTemplate rootJdbc =
                new org.springframework.jdbc.core.JdbcTemplate(rootDs);
        rootJdbc.execute("DROP DATABASE IF EXISTS `" + trimmedDb + "`");
        rootJdbc.execute("CREATE DATABASE `" + trimmedDb + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
}
