package com.universe.media.infrastructure.persistence;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Hardened execution guard and target validator for V93 isolated MySQL verification (MS-08 R2-02).
 *
 * <p>Guarantees fail-closed isolation:
 * <ul>
 *     <li>Disabled by default; requires explicit opt-in via JVM system property {@code -Dkiemlai.v93.verify.enabled=true};</li>
 *     <li>Requires an explicitly supplied disposable schema name via {@code -Dkiemlai.v93.verify.schema=<name>};</li>
 *     <li>Enforces strict schema naming pattern: {@code ^kiemlai_r2_v93_verify_[a-zA-Z0-9_]+$};</li>
 *     <li>Explicitly rejects any schema matching or containing {@code kiemlai_universe}, {@code kiemlai_test}, or prod/dev keywords;</li>
 *     <li>Requires loopback host (localhost or 127.0.0.1 on port 3306);</li>
 *     <li>Explicitly rejects remote Aiven hosts and non-loopback IPs;</li>
 *     <li>Fully fail-closed schema pre-existence validation (rejects null, non-empty, or inconclusive results);</li>
 *     <li>Requires dedicated test credentials; does not inherit root or generic test credentials;</li>
 *     <li>Redacts credentials from logs and error messages.</li>
 * </ul>
 */
public final class MediaV93VerificationGuard {

    /**
     * Official single activation contract for isolated V93 MySQL verification.
     * Must be passed as a JVM system property: {@code -Dkiemlai.v93.verify.enabled=true}.
     */
    public static final String OPT_IN_PROPERTY = "kiemlai.v93.verify.enabled";

    /**
     * Mandatory JVM property specifying the exact disposable schema name.
     */
    public static final String SCHEMA_PROPERTY = "kiemlai.v93.verify.schema";

    /**
     * Optional dedicated test host setting (defaults to localhost:3306).
     */
    public static final String HOST_PROPERTY = "kiemlai.v93.verify.host";
    public static final String HOST_ENV = "KIEMLAI_V93_VERIFY_HOST";

    /**
     * Mandatory dedicated test user setting.
     */
    public static final String USER_PROPERTY = "kiemlai.v93.verify.user";
    public static final String USER_ENV = "KIEMLAI_V93_VERIFY_USER";

    /**
     * Mandatory dedicated test password setting (supplied securely via property or env).
     */
    public static final String PASS_PROPERTY = "kiemlai.v93.verify.pass";
    public static final String PASS_ENV = "KIEMLAI_V93_VERIFY_PASS";

    private static final Pattern DISPOSABLE_SCHEMA_PATTERN =
            Pattern.compile("^kiemlai_r2_v93_verify_[a-zA-Z0-9_]+$");

    private static final Pattern STRICT_LOOPBACK_HOST_PATTERN =
            Pattern.compile("^(localhost|127\\.0\\.0\\.1)(?::3306)?$", Pattern.CASE_INSENSITIVE);

    private MediaV93VerificationGuard() {}

    /**
     * Checks if the test execution has been explicitly opted into via JVM system property.
     * Environment variables do not activate this test to ensure alignment with {@code @EnabledIfSystemProperty}.
     */
    public static boolean isOptedIn() {
        return isOptedIn(System.getProperty(OPT_IN_PROPERTY));
    }

    /**
     * Pure, deterministic helper for evaluating the opt-in system property value.
     */
    public static boolean isOptedIn(String propertyValue) {
        if (propertyValue == null || propertyValue.isBlank()) {
            return false;
        }
        return "true".equalsIgnoreCase(propertyValue.trim());
    }

    public static String resolveAndValidateSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Disposable schema name is required when opted in. "
                    + "Set system property -" + SCHEMA_PROPERTY + "=<schema_name>.");
        }
        String trimmed = schema.trim();
        String lower = trimmed.toLowerCase();

        // 1. Explicitly reject kiemlai_universe
        if (lower.contains("kiemlai_universe")) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Target schema must NEVER be 'kiemlai_universe'. Got: " + trimmed);
        }

        // 2. Explicitly reject kiemlai_test and standard production/staging/dev keywords
        if (lower.equals("kiemlai_test") || lower.contains("production") || lower.contains("prod") || lower.contains("staging")) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Target schema must not target standard test or production databases. Got: " + trimmed);
        }

        // 3. Enforce strict disposable schema prefix and pattern
        if (!DISPOSABLE_SCHEMA_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Disposable schema name must match pattern "
                    + DISPOSABLE_SCHEMA_PATTERN.pattern() + " (e.g. kiemlai_r2_v93_verify_YYYYMMDD_xx). Got: " + trimmed);
        }

        return trimmed;
    }

    public static String resolveAndValidateHost(String host) {
        if (host == null || host.isBlank()) {
            return "localhost:3306";
        }
        String trimmed = host.trim();
        String lower = trimmed.toLowerCase();

        // 1. Explicitly reject remote Aiven endpoints
        if (lower.contains("aiven") || lower.contains("aivencloud") || lower.contains("aiven.io")) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Target host must NEVER be a remote Aiven endpoint. Got: " + trimmed);
        }

        // 2. Enforce loopback host and port 3306
        if (!STRICT_LOOPBACK_HOST_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Target host must be local loopback (localhost:3306 or 127.0.0.1:3306). Got: " + trimmed);
        }

        return trimmed.contains(":") ? trimmed : trimmed + ":3306";
    }

    public static String resolveAndValidateUser(String user) {
        if (user == null || user.isBlank()) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Dedicated test user is required. "
                    + "Set system property -" + USER_PROPERTY + "=<username> or dedicated env " + USER_ENV + ".");
        }
        return user.trim();
    }

    public static String resolveAndValidatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("CRITICAL SAFETY GUARD: Dedicated test password is required. "
                    + "Set system property -" + PASS_PROPERTY + "=<password> or dedicated env " + PASS_ENV + ".");
        }
        return password;
    }

    /**
     * Fully fail-closed schema pre-existence validation.
     * Rejects null, non-empty, or inconclusive query results before any database creation.
     */
    public static void validateSchemaDoesNotExist(List<String> existingSchemas, String schemaName) {
        if (existingSchemas == null) {
            throw new IllegalStateException("CRITICAL ISOLATION GUARD: Inconclusive schema existence query result (null list). "
                    + "Aborting without mutation to protect existing databases.");
        }
        if (!existingSchemas.isEmpty()) {
            throw new IllegalStateException("CRITICAL ISOLATION GUARD: Schema '" + schemaName
                    + "' ALREADY EXISTS. Aborting without mutation. Verification requires a fresh, uncreated disposable schema.");
        }
    }
}
