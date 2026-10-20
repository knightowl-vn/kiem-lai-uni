package com.universe.screen.infrastructure.persistence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict test-only database target guard for Screen guarded discard MySQL concurrency verification.
 *
 * <p>Enforces that tests can NEVER execute against anything other than:
 * <ul>
 *   <li>Host: {@code localhost} or {@code 127.0.0.1}</li>
 *   <li>Port: {@code 3306} (explicitly or via default)</li>
 *   <li>Database: {@code kiemlai_test}</li>
 * </ul>
 *
 * <p>Rejects remote hosts, non-standard ports, other local databases (even if ending with _test),
 * production database names, and malformed identifiers.
 */
public final class ScreenTestDatabaseTargetGuard {

    public static final String EXACT_DATABASE_NAME = "kiemlai_test";
    public static final int EXACT_PORT = 3306;

    private static final Pattern STRICT_HOST_PORT_PATTERN = Pattern.compile(
            "^(localhost|127\\.0\\.0\\.1)(?::(\\d+))?$",
            Pattern.CASE_INSENSITIVE
    );

    private ScreenTestDatabaseTargetGuard() {
    }

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
                    "SAFETY BLOCK: Target host [" + trimmedHost + "] is not authorized. Must be loopback (localhost or 127.0.0.1)"
            );
        }

        String portGroup = matcher.group(2);
        if (portGroup != null) {
            int port = Integer.parseInt(portGroup);
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
}
