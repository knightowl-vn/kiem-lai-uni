package com.universe.media.application.storage;

import com.universe.media.infrastructure.storage.b2.B2StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MediaStorageRoutingConfigurationIntegrationTest - Authoritative B2 Bucket Configuration")
class MediaStorageRoutingConfigurationIntegrationTest {

    private static final String CUSTOM_B2_BUCKET = "my-custom-b2-bucket";
    private static final String CUSTOM_R2_BUCKET = "my-custom-r2-bucket";
    private static final String B2_ENDPOINT = "https://s3.us-east-005.backblazeb2.com";
    private static final String B2_ACCESS_KEY = "test-access-key";
    private static final String B2_SECRET_KEY = "test-secret-key";
    private static final String B2_REGION = "us-east-005";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MediaStorageRoutingService.class,
                    B2StorageProperties.class
            );

    @Test
    @DisplayName("Asserts MediaStorageRoutingService.resolveBucketName matches B2StorageProperties bucket when custom bucket is configured")
    void shouldMatchRoutingServiceBucketAndB2PropertiesBucket() {
        contextRunner
                .withPropertyValues(
                        "media.storage.b2.bucket=" + CUSTOM_B2_BUCKET,
                        "media.storage.r2.bucket=" + CUSTOM_R2_BUCKET,
                        "media.storage.b2.endpoint=" + B2_ENDPOINT,
                        "media.storage.b2.access-key-id=" + B2_ACCESS_KEY,
                        "media.storage.b2.secret-access-key=" + B2_SECRET_KEY,
                        "media.storage.b2.region=" + B2_REGION
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MediaStorageRoutingService.class);
                    assertThat(context).hasSingleBean(B2StorageProperties.class);

                    MediaStorageRoutingService routingService = context.getBean(MediaStorageRoutingService.class);
                    B2StorageProperties b2Properties = context.getBean(B2StorageProperties.class);

                    String resolvedBucket = routingService.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER);
                    String configuredBucket = b2Properties.bucket();

                    assertThat(resolvedBucket).isEqualTo(CUSTOM_B2_BUCKET);
                    assertThat(configuredBucket).isEqualTo(CUSTOM_B2_BUCKET);
                    assertThat(resolvedBucket).isEqualTo(configuredBucket);
                });
    }

    @Test
    @DisplayName("Fails closed when media.storage.b2.bucket is missing or blank")
    void shouldFailClosedWhenB2BucketIsMissingOrBlank() {
        contextRunner
                .withPropertyValues(
                        "media.storage.b2.bucket=",
                        "media.storage.r2.bucket=" + CUSTOM_R2_BUCKET
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MediaStorageRoutingService.class);
                    MediaStorageRoutingService routingService = context.getBean(MediaStorageRoutingService.class);

                    assertThatThrownBy(() -> routingService.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Storage provider 'b2' requires an authentic configured bucket ('media.storage.b2.bucket'), but none was configured.");
                });
    }

    @Test
    @DisplayName("Fails closed when media.storage.b2.bucket is sentinel NONE")
    void shouldFailClosedWhenB2BucketIsSentinelNone() {
        contextRunner
                .withPropertyValues(
                        "media.storage.b2.bucket=NONE",
                        "media.storage.r2.bucket=" + CUSTOM_R2_BUCKET
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MediaStorageRoutingService.class);
                    MediaStorageRoutingService routingService = context.getBean(MediaStorageRoutingService.class);

                    assertThatThrownBy(() -> routingService.resolveBucketName(MediaStorageRoutingService.B2_PROVIDER))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Storage provider 'b2' requires an authentic configured bucket ('media.storage.b2.bucket'), but none was configured.");
                });
    }
}
