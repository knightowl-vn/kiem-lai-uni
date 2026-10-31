package com.universe.media.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure offline unit test suite for {@link MediaV93VerificationGuard} and the opt-in configuration
 * of {@link MediaV93IsolatedMySQLVerificationTest} (MS-08 R2-02 Gate 2 Safety Preflight).
 *
 * <p>Verifies deterministically without database connection:
 * <ul>
 *     <li>Single activation contract via JVM system property {@code -Dkiemlai.v93.verify.enabled=true};</li>
 *     <li>Deterministic evaluation of missing, false, and true opt-in property values without conditional skips;</li>
 *     <li>Class annotation {@link EnabledIfSystemProperty} is pinned to the JVM system property contract;</li>
 *     <li>Environment-only variables cannot bypass JUnit test exclusion;</li>
 *     <li>Strict rejection of target schemas named or containing {@code kiemlai_universe};</li>
 *     <li>Strict rejection of {@code kiemlai_test} and production/staging/dev keywords;</li>
 *     <li>Enforcement of disposable naming pattern {@code ^kiemlai_r2_v93_verify_[a-zA-Z0-9_]+$};</li>
 *     <li>Strict rejection of remote Aiven hosts, non-loopback IPs, and unauthorized ports;</li>
 *     <li>Fully fail-closed schema pre-existence validation (null, non-empty, and empty result sets);</li>
 *     <li>Dedicated test credentials enforcement (rejects null or blank user/password without root fallbacks).</li>
 * </ul>
 */
@DisplayName("MS-08 R2-02: V93 Isolated Verification Opt-In Guard Unit Tests")
class MediaV93OptInGuardTest {

    @Test
    @DisplayName("1. Verification test class must have @EnabledIfSystemProperty pinned to JVM system property contract")
    void verificationClassMustHaveEnabledIfSystemPropertyAnnotation() {
        EnabledIfSystemProperty annotation =
                MediaV93IsolatedMySQLVerificationTest.class.getAnnotation(EnabledIfSystemProperty.class);

        assertThat(annotation)
                .as("MediaV93IsolatedMySQLVerificationTest must be annotated with @EnabledIfSystemProperty")
                .isNotNull();
        assertThat(annotation.named())
                .as("Annotation must check exact system property: kiemlai.v93.verify.enabled")
                .isEqualTo(MediaV93VerificationGuard.OPT_IN_PROPERTY);
        assertThat(annotation.matches())
                .as("Annotation must require value 'true'")
                .isEqualTo("true");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "null, false",
            "'', false",
            "'   ', false",
            "'false', false",
            "'FALSE', false",
            "'0', false",
            "'enabled', false",
            "'random', false",
            "'true', true",
            "'TRUE', true",
            "' True ', true"
    }, nullValues = {"null"})
    @DisplayName("2. Deterministic helper test: isOptedIn evaluates values correctly without global JVM mutation")
    void deterministicOptInEvaluation(String input, boolean expectedOutcome) {
        assertThat(MediaV93VerificationGuard.isOptedIn(input))
                .as("Evaluating isOptedIn('%s')", input)
                .isEqualTo(expectedOutcome);
    }

    @Test
    @DisplayName("3. Environment-only activation cannot bypass JUnit system property evaluation")
    void environmentCannotBypassJunitSystemPropertyEvaluation() {
        // JUnit @EnabledIfSystemProperty inspects System.getProperty, NOT System.getenv.
        // Even if an environment variable existed, System.getProperty("kiemlai.v93.verify.enabled")
        // remains null in standard test execution.
        String jvmProp = System.getProperty(MediaV93VerificationGuard.OPT_IN_PROPERTY);
        boolean isJvmEnabled = MediaV93VerificationGuard.isOptedIn(jvmProp);

        assertThat(isJvmEnabled)
                .as("Default JVM system property must not be enabled without explicit -D flag")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kiemlai_universe",
            "KIEMLAI_UNIVERSE",
            "kiemlai_universe_backup",
            "remote_kiemlai_universe",
            "kiemlai_test",
            "production_db",
            "prod_schema",
            "staging_kiemlai",
            "arbitrary_db",
            "kiemlai_r2_v93", // missing _verify_ prefix
            "kiemlai_v93_verify", // missing _r2_ prefix
            "test_verify_20261031"
    })
    @DisplayName("4. Schema validator strictly rejects kiemlai_universe, kiemlai_test, prod/staging, and non-conforming patterns")
    void schemaValidatorRejectsUnsafeSchemas(String unsafeSchema) {
        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidateSchema(unsafeSchema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL SAFETY GUARD");
    }

    @Test
    @DisplayName("5. Schema validator rejects null, empty, or blank schema names")
    void schemaValidatorRejectsBlankSchema() {
        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidateSchema(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Disposable schema name is required");

        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidateSchema("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Disposable schema name is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kiemlai_r2_v93_verify_20261031_02",
            "kiemlai_r2_v93_verify_run1",
            "kiemlai_r2_v93_verify_ci_safe",
            "kiemlai_r2_v93_verify_disposable_2026"
    })
    @DisplayName("6. Schema validator accepts conforming disposable schema names")
    void schemaValidatorAcceptsConformingSchemas(String validSchema) {
        String resolved = MediaV93VerificationGuard.resolveAndValidateSchema(validSchema);
        assertThat(resolved).isEqualTo(validSchema);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mysql-kiemlai.aivencloud.com:12345",
            "aiven-db.mysql.aiven.io",
            "kiemlaidb-production.aivencloud.com",
            "192.168.1.100:3306",
            "10.0.0.5:3306",
            "remotehost:3306",
            "localhost:3307",
            "127.0.0.1:3308",
            "localhost:8080"
    })
    @DisplayName("7. Host validator strictly rejects remote Aiven hosts, non-loopback IPs, and unexpected ports")
    void hostValidatorRejectsRemoteHosts(String remoteHost) {
        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidateHost(remoteHost))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL SAFETY GUARD");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost",
            "127.0.0.1",
            "localhost:3306",
            "127.0.0.1:3306"
    })
    @DisplayName("8. Host validator accepts loopback localhost and 127.0.0.1 on port 3306")
    void hostValidatorAcceptsLoopback(String loopbackHost) {
        String resolved = MediaV93VerificationGuard.resolveAndValidateHost(loopbackHost);
        assertThat(resolved).isIn("localhost:3306", "127.0.0.1:3306");
    }

    @Test
    @DisplayName("9. Dedicated test user validator accepts non-blank username and rejects null or blank")
    void dedicatedUserValidatorEnforcesValue() {
        assertThatCode(() -> MediaV93VerificationGuard.resolveAndValidateUser("test_admin_user"))
                .doesNotThrowAnyException();
        assertThat(MediaV93VerificationGuard.resolveAndValidateUser("test_admin_user"))
                .isEqualTo("test_admin_user");

        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidateUser(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dedicated test user is required");

        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidateUser("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dedicated test user is required");
    }

    @Test
    @DisplayName("10. Dedicated test password validator accepts non-blank password and rejects null or blank")
    void dedicatedPasswordValidatorEnforcesValue() {
        assertThatCode(() -> MediaV93VerificationGuard.resolveAndValidatePassword("secure_test_password"))
                .doesNotThrowAnyException();
        assertThat(MediaV93VerificationGuard.resolveAndValidatePassword("secure_test_password"))
                .isEqualTo("secure_test_password");

        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidatePassword(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dedicated test password is required");

        assertThatThrownBy(() -> MediaV93VerificationGuard.resolveAndValidatePassword("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dedicated test password is required");
    }

    @Test
    @DisplayName("11. Pre-existence guard strictly rejects null query result as inconclusive")
    void preExistenceGuardRejectsNullResult() {
        assertThatThrownBy(() -> MediaV93VerificationGuard.validateSchemaDoesNotExist(null, "kiemlai_r2_v93_verify_fresh"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inconclusive schema existence query result (null list)");
    }

    @Test
    @DisplayName("12. Pre-existence guard strictly rejects non-empty query result (schema already exists)")
    void preExistenceGuardRejectsExistingSchemas() {
        List<String> existing = List.of("kiemlai_r2_v93_verify_20261031");

        assertThatThrownBy(() -> MediaV93VerificationGuard.validateSchemaDoesNotExist(existing, "kiemlai_r2_v93_verify_20261031"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL ISOLATION GUARD")
                .hasMessageContaining("ALREADY EXISTS");
    }

    @Test
    @DisplayName("13. Pre-existence guard strictly rejects unexpected query results (any populated list)")
    void preExistenceGuardRejectsAnyPopulatedList() {
        List<String> unexpected = List.of("unexpected_match");

        assertThatThrownBy(() -> MediaV93VerificationGuard.validateSchemaDoesNotExist(unexpected, "kiemlai_r2_v93_verify_fresh"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRITICAL ISOLATION GUARD")
                .hasMessageContaining("ALREADY EXISTS");
    }

    @Test
    @DisplayName("14. Pre-existence guard passes only when query result is non-null and empty")
    void preExistenceGuardPassesWhenSchemaDoesNotExist() {
        assertThatCode(() -> MediaV93VerificationGuard.validateSchemaDoesNotExist(Collections.emptyList(), "kiemlai_r2_v93_verify_fresh"))
                .doesNotThrowAnyException();
    }
}
