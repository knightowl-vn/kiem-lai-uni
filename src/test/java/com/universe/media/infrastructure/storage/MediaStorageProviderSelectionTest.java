package com.universe.media.infrastructure.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.ImageVariantPublicUrlPort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.storage.cloudinary.CloudinaryStorageAdapter;
import com.universe.media.infrastructure.storage.local.LocalFilesystemStorageAdapter;
import com.universe.media.infrastructure.storage.r2.R2StorageAdapter;
import com.universe.media.infrastructure.storage.r2.R2StorageProperties;
import com.universe.shared.configuration.CloudinaryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStorageProviderSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LocalFilesystemStorageAdapter.class,
                    R2StorageProperties.class,
                    R2StorageAdapter.class,
                    CloudinaryConfig.class,
                    CloudinaryStorageAdapter.class,
                    DefaultStorageProviderRegistry.class
            );

    private static final String[] VALID_CLOUDINARY_PROPS = {
            "cloudinary.cloud-name=test-cloud",
            "cloudinary.api-key=test-api-key",
            "cloudinary.api-secret=test-api-secret"
    };

    private static final String[] VALID_R2_PROPS = {
            "media.storage.r2.bucket=test-r2-bucket",
            "media.storage.r2.endpoint=https://test-account.r2.cloudflarestorage.com",
            "media.storage.r2.access-key-id=test-r2-key",
            "media.storage.r2.secret-access-key=test-r2-secret"
    };

    @Test
    @DisplayName("no external provider config: registry contains local only")
    void shouldRegisterOnlyLocalWhenNoExternalProviderConfigured() {
        contextRunner
                .withPropertyValues(
                        "media.storage.r2.bucket=",
                        "media.storage.r2.endpoint=",
                        "media.storage.r2.access-key-id=",
                        "media.storage.r2.secret-access-key=",
                        "cloudinary.cloud-name=",
                        "cloudinary.cloud_name=",
                        "CLOUDINARY_CLOUD_NAME=",
                        "cloudinary.api-key=",
                        "cloudinary.api-secret="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    assertThat(context).doesNotHaveBean(R2StorageAdapter.class);
                    assertThat(context).doesNotHaveBean(R2StorageProperties.class);
                    assertThat(context).doesNotHaveBean(CloudinaryStorageAdapter.class);

                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThat(resolver.resolve(StorageProviderId.of("local")))
                            .isInstanceOf(LocalFilesystemStorageAdapter.class);

                    assertThatThrownBy(() -> resolver.resolve(StorageProviderId.of("cloudinary")))
                            .isInstanceOf(StorageException.class)
                            .hasMessageContaining("Unsupported or unconfigured storage provider: cloudinary");

                    assertThatThrownBy(() -> resolver.resolve(StorageProviderId.of("r2")))
                            .isInstanceOf(StorageException.class)
                            .hasMessageContaining("Unsupported or unconfigured storage provider: r2");
                });
    }

    @Test
    @DisplayName("valid Cloudinary config: registry contains local + cloudinary")
    void shouldRegisterLocalAndCloudinaryWhenValidCloudinaryConfigured() {
        contextRunner
                .withPropertyValues(
                        "media.storage.r2.bucket=",
                        "media.storage.r2.endpoint=",
                        "media.storage.r2.access-key-id=",
                        "media.storage.r2.secret-access-key="
                )
                .withPropertyValues(VALID_CLOUDINARY_PROPS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    assertThat(context).hasSingleBean(CloudinaryStorageAdapter.class);
                    assertThat(context).doesNotHaveBean(R2StorageAdapter.class);

                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThat(resolver.resolve(StorageProviderId.of("local")))
                            .isInstanceOf(LocalFilesystemStorageAdapter.class);
                    assertThat(resolver.resolve(StorageProviderId.of("cloudinary")))
                            .isInstanceOf(CloudinaryStorageAdapter.class);

                    assertThat(context).hasSingleBean(ImageVariantPublicUrlPort.class);
                });
    }

    @Test
    @DisplayName("valid R2 config: registry contains local + r2")
    void shouldRegisterLocalAndR2WhenValidR2Configured() {
        contextRunner
                .withPropertyValues(
                        "cloudinary.cloud-name=",
                        "cloudinary.cloud_name=",
                        "CLOUDINARY_CLOUD_NAME=",
                        "cloudinary.api-key=",
                        "cloudinary.api-secret="
                )
                .withPropertyValues(VALID_R2_PROPS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    assertThat(context).hasSingleBean(R2StorageAdapter.class);
                    assertThat(context).hasSingleBean(R2StorageProperties.class);
                    assertThat(context).doesNotHaveBean(CloudinaryStorageAdapter.class);

                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThat(resolver.resolve(StorageProviderId.of("local")))
                            .isInstanceOf(LocalFilesystemStorageAdapter.class);
                    assertThat(resolver.resolve(StorageProviderId.of("r2")))
                            .isInstanceOf(R2StorageAdapter.class);
                });
    }

    @Test
    @DisplayName("valid Cloudinary + R2: registry contains local + cloudinary + r2")
    void shouldRegisterLocalCloudinaryAndR2WhenBothConfigured() {
        contextRunner
                .withPropertyValues(VALID_CLOUDINARY_PROPS)
                .withPropertyValues(VALID_R2_PROPS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    assertThat(context).hasSingleBean(CloudinaryStorageAdapter.class);
                    assertThat(context).hasSingleBean(R2StorageAdapter.class);

                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThat(resolver.resolve(StorageProviderId.of("local"))).isInstanceOf(LocalFilesystemStorageAdapter.class);
                    assertThat(resolver.resolve(StorageProviderId.of("cloudinary"))).isInstanceOf(CloudinaryStorageAdapter.class);
                    assertThat(resolver.resolve(StorageProviderId.of("r2"))).isInstanceOf(R2StorageAdapter.class);
                });
    }

    @Test
    @DisplayName("setting legacy media.storage.provider=r2 must NOT remove Local provider")
    void shouldNotRemoveLocalWhenLegacyProviderIsR2() {
        contextRunner
                .withPropertyValues("media.storage.provider=r2")
                .withPropertyValues(
                        "media.storage.r2.bucket=",
                        "media.storage.r2.endpoint=",
                        "media.storage.r2.access-key-id=",
                        "media.storage.r2.secret-access-key=",
                        "cloudinary.cloud-name=",
                        "cloudinary.cloud_name=",
                        "CLOUDINARY_CLOUD_NAME=",
                        "cloudinary.api-key=",
                        "cloudinary.api-secret="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThat(resolver.resolve(StorageProviderId.of("local"))).isInstanceOf(LocalFilesystemStorageAdapter.class);
                });
    }

    @Test
    @DisplayName("setting legacy media.storage.provider=local must NOT suppress a correctly configured R2 provider")
    void shouldNotSuppressR2WhenLegacyProviderIsLocal() {
        contextRunner
                .withPropertyValues("media.storage.provider=local")
                .withPropertyValues(
                        "cloudinary.cloud-name=",
                        "cloudinary.cloud_name=",
                        "CLOUDINARY_CLOUD_NAME=",
                        "cloudinary.api-key=",
                        "cloudinary.api-secret="
                )
                .withPropertyValues(VALID_R2_PROPS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LocalFilesystemStorageAdapter.class);
                    assertThat(context).hasSingleBean(R2StorageAdapter.class);
                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThat(resolver.resolve(StorageProviderId.of("local"))).isInstanceOf(LocalFilesystemStorageAdapter.class);
                    assertThat(resolver.resolve(StorageProviderId.of("r2"))).isInstanceOf(R2StorageAdapter.class);
                });
    }

    @Test
    @DisplayName("missing Cloudinary config: resolver.resolve(\"cloudinary\") fails closed")
    void shouldFailClosedWhenCloudinaryNotConfigured() {
        contextRunner
                .withPropertyValues(
                        "cloudinary.cloud-name=",
                        "cloudinary.cloud_name=",
                        "CLOUDINARY_CLOUD_NAME=",
                        "cloudinary.api-key=",
                        "cloudinary.api-secret="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CloudinaryStorageAdapter.class);
                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThatThrownBy(() -> resolver.resolve(StorageProviderId.of("cloudinary")))
                            .isInstanceOf(StorageException.class)
                            .hasMessageContaining("Unsupported or unconfigured storage provider: cloudinary");
                });
    }

    @Test
    @DisplayName("missing R2 config: resolver.resolve(\"r2\") fails closed")
    void shouldFailClosedWhenR2NotConfigured() {
        contextRunner
                .withPropertyValues(
                        "media.storage.r2.bucket=",
                        "media.storage.r2.endpoint=",
                        "media.storage.r2.access-key-id=",
                        "media.storage.r2.secret-access-key="
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(R2StorageAdapter.class);
                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);
                    assertThatThrownBy(() -> resolver.resolve(StorageProviderId.of("r2")))
                            .isInstanceOf(StorageException.class)
                            .hasMessageContaining("Unsupported or unconfigured storage provider: r2");
                });
    }

    @Test
    @DisplayName("legacy read regression: with both R2 and Local available, persisted provider routes correctly")
    void shouldResolveCorrectAdapterForLegacyAndActiveStoredProviders() {
        contextRunner
                .withPropertyValues(
                        "cloudinary.cloud-name=",
                        "cloudinary.cloud_name=",
                        "CLOUDINARY_CLOUD_NAME=",
                        "cloudinary.api-key=",
                        "cloudinary.api-secret="
                )
                .withPropertyValues(VALID_R2_PROPS)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StorageProviderResolverPort resolver = context.getBean(StorageProviderResolverPort.class);

                    // Persisted provider=local -> resolves LocalFilesystemStorageAdapter
                    BinaryStoragePort localPort = resolver.resolve(StorageProviderId.of("local"));
                    assertThat(localPort).isInstanceOf(LocalFilesystemStorageAdapter.class);
                    assertThat(localPort.providerId()).isEqualTo(StorageProviderId.of("local"));

                    // Persisted provider=r2 -> resolves R2StorageAdapter
                    BinaryStoragePort r2Port = resolver.resolve(StorageProviderId.of("r2"));
                    assertThat(r2Port).isInstanceOf(R2StorageAdapter.class);
                    assertThat(r2Port.providerId()).isEqualTo(StorageProviderId.of("r2"));
                });
    }
}
