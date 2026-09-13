package com.universe.media.infrastructure.storage;

import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.infrastructure.storage.local.LocalFilesystemStorageAdapter;
import com.universe.media.infrastructure.storage.r2.R2StorageAdapter;
import com.universe.media.infrastructure.storage.r2.R2StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MediaStorageProviderSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LocalFilesystemStorageAdapter.class,
                    R2StorageProperties.class,
                    R2StorageAdapter.class
            );

    @Test
    @DisplayName("local provider is selected by default when media.storage.provider is absent")
    void shouldSelectLocalByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BinaryStoragePort.class);
            assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
            assertThat(context).doesNotHaveBean(R2StorageAdapter.class);
            assertThat(context).doesNotHaveBean(R2StorageProperties.class);

            BinaryStoragePort port = context.getBean(BinaryStoragePort.class);
            assertThat(port.providerId()).isEqualTo(LocalFilesystemStorageAdapter.PROVIDER_ID);
        });
    }

    @Test
    @DisplayName("explicit local selects LocalFilesystemStorageAdapter")
    void shouldSelectLocalWhenExplicitlyConfigured() {
        contextRunner.withPropertyValues("media.storage.provider=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BinaryStoragePort.class);
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    assertThat(context).doesNotHaveBean(R2StorageAdapter.class);
                    assertThat(context).doesNotHaveBean(R2StorageProperties.class);
                });
    }

    @Test
    @DisplayName("explicit r2 selects R2StorageAdapter and R2StorageProperties")
    void shouldSelectR2WhenExplicitlyConfigured() {
        contextRunner.withPropertyValues(
                "media.storage.provider=r2",
                "media.storage.r2.bucket=my-r2-bucket",
                "media.storage.r2.endpoint=https://test-account.r2.cloudflarestorage.com",
                "media.storage.r2.access-key-id=test-access-key",
                "media.storage.r2.secret-access-key=test-secret-key"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BinaryStoragePort.class);
            assertThat(context).hasSingleBean(R2StorageAdapter.class);
            assertThat(context).hasSingleBean(R2StorageProperties.class);
            assertThat(context).doesNotHaveBean(LocalFilesystemStorageAdapter.class);

            BinaryStoragePort port = context.getBean(BinaryStoragePort.class);
            assertThat(port.providerId()).isEqualTo(R2StorageAdapter.PROVIDER_ID);
        });
    }

    @Test
    @DisplayName("inactive R2 configuration does not require R2 credentials or configuration")
    void shouldNotRequireR2CredentialsWhenR2IsInactive() {
        contextRunner.withPropertyValues(
                "media.storage.provider=local",
                "media.storage.r2.bucket=",
                "media.storage.r2.endpoint=",
                "media.storage.r2.access-key-id=",
                "media.storage.r2.secret-access-key="
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
            assertThat(context).doesNotHaveBean(R2StorageProperties.class);
            assertThat(context).doesNotHaveBean(R2StorageAdapter.class);
        });
    }

    @Test
    @DisplayName("active R2 with missing bucket fails startup clearly")
    void shouldFailWhenActiveR2HasMissingBucket() {
        contextRunner.withPropertyValues(
                "media.storage.provider=r2",
                "media.storage.r2.bucket=",
                "media.storage.r2.endpoint=https://test-account.r2.cloudflarestorage.com",
                "media.storage.r2.access-key-id=key",
                "media.storage.r2.secret-access-key=secret"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Cloudflare R2 configuration [media.storage.r2.bucket] must not be blank when R2 storage provider is active.");
        });
    }

    @Test
    @DisplayName("active R2 with missing endpoint fails startup clearly")
    void shouldFailWhenActiveR2HasMissingEndpoint() {
        contextRunner.withPropertyValues(
                "media.storage.provider=r2",
                "media.storage.r2.bucket=my-bucket",
                "media.storage.r2.endpoint=",
                "media.storage.r2.access-key-id=key",
                "media.storage.r2.secret-access-key=secret"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Cloudflare R2 configuration [media.storage.r2.endpoint] must not be blank when R2 storage provider is active.");
        });
    }

    @Test
    @DisplayName("active R2 with missing access-key-id fails startup clearly")
    void shouldFailWhenActiveR2HasMissingAccessKeyId() {
        contextRunner.withPropertyValues(
                "media.storage.provider=r2",
                "media.storage.r2.bucket=my-bucket",
                "media.storage.r2.endpoint=https://test-account.r2.cloudflarestorage.com",
                "media.storage.r2.access-key-id=",
                "media.storage.r2.secret-access-key=secret"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Cloudflare R2 configuration [media.storage.r2.access-key-id] must not be blank when R2 storage provider is active.");
        });
    }

    @Test
    @DisplayName("active R2 with missing secret-access-key fails startup clearly")
    void shouldFailWhenActiveR2HasMissingSecretAccessKey() {
        contextRunner.withPropertyValues(
                "media.storage.provider=r2",
                "media.storage.r2.bucket=my-bucket",
                "media.storage.r2.endpoint=https://test-account.r2.cloudflarestorage.com",
                "media.storage.r2.access-key-id=key",
                "media.storage.r2.secret-access-key="
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Cloudflare R2 configuration [media.storage.r2.secret-access-key] must not be blank when R2 storage provider is active.");
        });
    }
}
