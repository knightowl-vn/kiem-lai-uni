package com.universe.screen.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure offline unit test for ScreenTestDatabaseTargetGuard.
 *
 * <p>Verifies negative and positive validation paths without opening any database or network connections.
 */
@DisplayName("Screen Test Database Target Guard Pure Unit Tests")
class ScreenTestDatabaseTargetGuardTest {

    @Test
    @DisplayName("Accepts valid loopback localhost:3306 and kiemlai_test")
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
    @DisplayName("Rejects remote or non-loopback hosts")
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
    @DisplayName("Rejects unexpected ports even on localhost")
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
    @DisplayName("Rejects databases other than kiemlai_test (including production and other *_test databases)")
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
    @DisplayName("Rejects case variants of kiemlai_test (strictly case-sensitive)")
    void shouldRejectCaseVariantsOfKiemlaiTest(String dbName) {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", dbName))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Must be strictly 'kiemlai_test'");
    }

    @Test
    @DisplayName("Rejects null or blank host")
    void shouldRejectNullOrBlankHost() {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget(null, "kiemlai_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database host cannot be null or blank");
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("   ", "kiemlai_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database host cannot be null or blank");
    }

    @Test
    @DisplayName("Rejects null or blank database name")
    void shouldRejectNullOrBlankDatabaseName() {
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database name cannot be null or blank");
        assertThatThrownBy(() -> ScreenTestDatabaseTargetGuard.validateTarget("localhost:3306", "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database name cannot be null or blank");
    }
}
