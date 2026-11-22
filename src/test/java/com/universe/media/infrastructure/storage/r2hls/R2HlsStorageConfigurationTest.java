package com.universe.media.infrastructure.storage.r2hls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MS-08 HLS-2B2B: R2 HLS Storage Configuration & Dormancy Tests")
class R2HlsStorageConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(R2HlsStorageProperties.class);

    @Test
    @DisplayName("Condition matches false by default when media.hls.storage.r2.enabled is unset or false")
    void conditionMatchesFalseByDefault() {
        R2HlsStorageCondition condition = new R2HlsStorageCondition();
        ConditionContext context = mock(ConditionContext.class);
        Environment env = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(env);
        AnnotatedTypeMetadata metadata = mock(AnnotatedTypeMetadata.class);

        when(env.getProperty("media.hls.storage.r2.enabled")).thenReturn(null);
        assertThat(condition.matches(context, metadata)).isFalse();

        when(env.getProperty("media.hls.storage.r2.enabled")).thenReturn("false");
        assertThat(condition.matches(context, metadata)).isFalse();

        when(env.getProperty("media.hls.storage.r2.enabled")).thenReturn("FALSE");
        assertThat(condition.matches(context, metadata)).isFalse();
    }

    @Test
    @DisplayName("Condition matches true when media.hls.storage.r2.enabled=true")
    void conditionMatchesTrueWhenExplicitlyEnabled() {
        R2HlsStorageCondition condition = new R2HlsStorageCondition();
        ConditionContext context = mock(ConditionContext.class);
        Environment env = mock(Environment.class);
        when(context.getEnvironment()).thenReturn(env);
        AnnotatedTypeMetadata metadata = mock(AnnotatedTypeMetadata.class);

        when(env.getProperty("media.hls.storage.r2.enabled")).thenReturn("true");
        assertThat(condition.matches(context, metadata)).isTrue();

        when(env.getProperty("media.hls.storage.r2.enabled")).thenReturn("TRUE");
        assertThat(condition.matches(context, metadata)).isTrue();
    }

    @Test
    @DisplayName("Spring context runner: disabled by default leaves ApplicationContext without R2 HLS beans")
    void springContext_disabledByDefaultHasNoBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(R2HlsStorageProperties.class);
        });

        contextRunner.withPropertyValues("media.hls.storage.r2.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(R2HlsStorageProperties.class);
        });
    }

    @Test
    @DisplayName("Spring context runner: explicit activation with missing properties fails context refresh")
    void springContext_enabledWithMissingPropertiesFailsClosed() {
        contextRunner.withPropertyValues("media.hls.storage.r2.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    @DisplayName("Spring context runner: explicit activation with valid properties loads properties bean successfully")
    void springContext_enabledWithValidPropertiesLoadsBean() {
        contextRunner.withPropertyValues(
                "media.hls.storage.r2.enabled=true",
                "media.storage.r2-hls.bucket=my-test-bucket",
                "media.storage.r2-hls.endpoint=https://account.r2.cloudflarestorage.com",
                "media.storage.r2-hls.access-key-id=mock-access-key",
                "media.storage.r2-hls.secret-access-key=mock-secret-key"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(R2HlsStorageProperties.class);
            R2HlsStorageProperties bean = context.getBean(R2HlsStorageProperties.class);
            assertThat(bean.bucket()).isEqualTo("my-test-bucket");
            assertThat(bean.endpoint()).isEqualTo("https://account.r2.cloudflarestorage.com");
            assertThat(bean.uploadStageTimeout()).isEqualTo(Duration.ofSeconds(900));
            assertThat(bean.maxVerificationCount()).isEqualTo(5000);
            assertThat(bean.requireFullSegmentIntegrityVerification()).isFalse();
        });
    }

    @Test
    @DisplayName("Properties instantiate successfully with valid parameters")
    void propertiesInstantiateWithValidConfiguration() {
        R2HlsStorageProperties props = new R2HlsStorageProperties(
                "my-hls-bucket",
                "https://account.r2.cloudflarestorage.com",
                "test-access-key",
                "test-secret-key",
                "auto",
                4,
                30,
                120,
                3,
                64L * 1024L * 1024L,
                5000,
                60,
                1000,
                5000,
                true
        );

        assertThat(props.bucket()).isEqualTo("my-hls-bucket");
        assertThat(props.endpoint()).isEqualTo("https://account.r2.cloudflarestorage.com");
        assertThat(props.accessKeyId()).isEqualTo("test-access-key");
        assertThat(props.secretAccessKey()).isEqualTo("test-secret-key");
        assertThat(props.region()).isEqualTo("auto");
        assertThat(props.maxConcurrentUploads()).isEqualTo(4);
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.totalAttemptTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(props.maxRetries()).isEqualTo(3);
        assertThat(props.maxArtifactSizeBytes()).isEqualTo(67108864L);
        assertThat(props.maxVerificationCount()).isEqualTo(5000);
        assertThat(props.purgeMaxDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.purgeBatchSize()).isEqualTo(1000);
        assertThat(props.purgeMaxKeys()).isEqualTo(5000);
        assertThat(props.requireFullSegmentIntegrityVerification()).isTrue();
    }

    @Test
    @DisplayName("toString protects credentials from leaking into logs")
    void toStringMasksCredentials() {
        R2HlsStorageProperties props = new R2HlsStorageProperties(
                "my-hls-bucket",
                "https://account.r2.cloudflarestorage.com",
                "super-secret-access-key-12345",
                "super-secret-key-abcdef",
                "auto",
                4,
                30,
                120,
                3,
                67108864L,
                5000,
                60,
                1000,
                5000,
                false
        );

        String s = props.toString();
        assertThat(s).doesNotContain("super-secret-access-key-12345");
        assertThat(s).doesNotContain("super-secret-key-abcdef");
        assertThat(s).contains("accessKeyId='[PROTECTED]'");
        assertThat(s).contains("secretAccessKey='[PROTECTED]'");
    }

    @Test
    @DisplayName("Fails closed on missing or blank bucket")
    void failsOnMissingOrBlankBucket() {
        assertThatThrownBy(() -> new R2HlsStorageProperties(
                null,
                "https://account.r2.cloudflarestorage.com",
                "key",
                "secret",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("media.storage.r2-hls.bucket");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "   ",
                "https://account.r2.cloudflarestorage.com",
                "key",
                "secret",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("media.storage.r2-hls.bucket");
    }

    @Test
    @DisplayName("Fails closed on non-HTTPS, missing host, or invalid endpoint URI")
    void failsOnInvalidEndpoint() {
        // Non-HTTPS (HTTP)
        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket",
                "http://account.r2.cloudflarestorage.com",
                "key",
                "secret",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");

        // Embedded user-info credentials
        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket",
                "https://user:pass@account.r2.cloudflarestorage.com",
                "key",
                "secret",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("user-info");

        // Malformed URI
        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket",
                "not a uri",
                "key",
                "secret",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("endpoint");
    }

    @Test
    @DisplayName("Fails closed on missing access key or secret key")
    void failsOnMissingCredentials() {
        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket",
                "https://account.r2.cloudflarestorage.com",
                "",
                "secret",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("media.storage.r2-hls.access-key-id");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket",
                "https://account.r2.cloudflarestorage.com",
                "key",
                "  ",
                "auto", 4, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("media.storage.r2-hls.secret-access-key");
    }

    @Test
    @DisplayName("Fails closed on invalid numeric bounds")
    void failsOnInvalidNumericBounds() {
        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                0, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("maxConcurrentUploads");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                20, 30, 120, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("maxConcurrentUploads");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                4, 30, 20, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("totalAttemptTimeoutSeconds");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                4, 30, 120, -1, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("maxRetries");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                4, 30, 120, 3, 67108864L, 5000, 60, 1001, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("purgeBatchSize");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                4, 30, 120, 60, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("uploadStageTimeoutSeconds");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                4, 30, 120, 0, 3, 67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("uploadStageTimeoutSeconds");

        assertThatThrownBy(() -> new R2HlsStorageProperties(
                "my-bucket", "https://account.r2.cloudflarestorage.com", "k", "s", "auto",
                4, 30, 120, R2HlsStorageProperties.MAX_UPLOAD_STAGE_TIMEOUT_SECONDS + 1, 3,
                67108864L, 5000, 60, 1000, 5000, false
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("uploadStageTimeoutSeconds");
    }
}
