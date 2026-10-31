package com.universe.media.infrastructure.storage.r2movie;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaUploadSessionRepositoryPort;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.application.ports.storage.MultipartStoragePort;
import com.universe.media.application.ports.storage.StorageProviderResolverPort;
import com.universe.media.application.storage.MediaStorageRoutingService;
import com.universe.media.application.storage.MediaUploadSessionService;
import com.universe.media.application.storage.StorageKeyAllocationService;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.infrastructure.storage.DefaultStorageProviderRegistry;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class R2MovieStorageSpringWiringTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");
    private static final StorageProviderId R2_NOVEL_PROVIDER = StorageProviderId.of("r2");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Configuration
    static class BaseTestConfig {
        @Bean
        public MediaUploadSessionRepositoryPort sessionRepositoryPort() {
            return mock(MediaUploadSessionRepositoryPort.class);
        }

        @Bean
        public StorageKeyAllocationService storageKeyAllocationService() {
            return mock(StorageKeyAllocationService.class);
        }

        @Bean
        public ClockPort clockPort() {
            return () -> Instant.parse("2026-10-31T10:00:00Z");
        }

        @Bean
        public PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        public MediaAssetRepositoryPort mediaAssetRepositoryPort() {
            return mock(MediaAssetRepositoryPort.class);
        }

        @Bean
        public MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort() {
            return mock(MediaAssetVersionRepositoryPort.class);
        }
    }

    @Configuration
    static class B2OnlyConfig {
        @Bean
        public MultipartStoragePort b2StoragePort() {
            MultipartStoragePort port = mock(MultipartStoragePort.class);
            when(port.providerId()).thenReturn(B2_PROVIDER);
            when(port.configuredBucketName()).thenReturn("b2-test-bucket");
            return port;
        }

        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            return new MediaStorageRoutingService("b2-test-bucket", null, null, false);
        }
    }

    @Configuration
    static class DualStorageConfigFlagOff {
        @Bean
        public MultipartStoragePort b2StoragePort() {
            MultipartStoragePort port = mock(MultipartStoragePort.class);
            when(port.providerId()).thenReturn(B2_PROVIDER);
            when(port.configuredBucketName()).thenReturn("b2-test-bucket");
            return port;
        }

        @Bean
        public MultipartStoragePort r2MovieStoragePort() {
            MultipartStoragePort port = mock(MultipartStoragePort.class);
            when(port.providerId()).thenReturn(R2_MOVIE_PROVIDER);
            when(port.configuredBucketName()).thenReturn("r2-movie-bucket");
            return port;
        }

        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            return new MediaStorageRoutingService("b2-test-bucket", "r2-novel-bucket", "r2-movie-bucket", false);
        }
    }

    @Configuration
    static class DualStorageConfigFlagOn {
        @Bean
        public MultipartStoragePort b2StoragePort() {
            MultipartStoragePort port = mock(MultipartStoragePort.class);
            when(port.providerId()).thenReturn(B2_PROVIDER);
            when(port.configuredBucketName()).thenReturn("b2-test-bucket");
            return port;
        }

        @Bean
        public MultipartStoragePort r2MovieStoragePort() {
            MultipartStoragePort port = mock(MultipartStoragePort.class);
            when(port.providerId()).thenReturn(R2_MOVIE_PROVIDER);
            when(port.configuredBucketName()).thenReturn("r2-movie-bucket");
            return port;
        }

        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            return new MediaStorageRoutingService("b2-test-bucket", "r2-novel-bucket", "r2-movie-bucket", true);
        }
    }

    @Configuration
    static class MissingR2AdapterFlagOnConfig {
        @Bean
        public MultipartStoragePort b2StoragePort() {
            MultipartStoragePort port = mock(MultipartStoragePort.class);
            when(port.providerId()).thenReturn(B2_PROVIDER);
            when(port.configuredBucketName()).thenReturn("b2-test-bucket");
            return port;
        }

        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            return new MediaStorageRoutingService("b2-test-bucket", null, "r2-movie-bucket", true);
        }
    }

    @Configuration
    static class NoPortsConfig {
        @Bean
        public MediaStorageRoutingService mediaStorageRoutingService() {
            return new MediaStorageRoutingService("b2-test-bucket", null, null, false);
        }
    }

    @Test
    @DisplayName("SPRING-01: B2-only configuration starts and registers MediaUploadSessionService successfully")
    void spring01_b2OnlyConfigurationStarts() {
        contextRunner
                .withUserConfiguration(BaseTestConfig.class, B2OnlyConfig.class, MediaUploadSessionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaUploadSessionService.class);
                });
    }

    @Test
    @DisplayName("SPRING-02: B2 + R2 Movie configuration starts successfully without multiple bean conflict")
    void spring02_dualConfigurationStartsDeterministically() {
        contextRunner
                .withUserConfiguration(BaseTestConfig.class, DualStorageConfigFlagOff.class, MediaUploadSessionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MediaUploadSessionService.class);
                    assertThat(context.getBeansOfType(MultipartStoragePort.class)).hasSize(2);
                });
    }

    @Test
    @DisplayName("SPRING-03: B2 + R2 Movie with routing flag OFF starts and routes to B2")
    void spring03_dualConfigurationFlagOffRoutesToB2() {
        contextRunner
                .withUserConfiguration(BaseTestConfig.class, DualStorageConfigFlagOff.class, MediaUploadSessionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaStorageRoutingService routing = context.getBean(MediaStorageRoutingService.class);
                    assertThat(routing.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-video")).isEqualTo(B2_PROVIDER);
                    assertThat(routing.resolveWriteProvider(MediaType.VIDEO, "screen-video")).isEqualTo(B2_PROVIDER);
                });
    }

    @Test
    @DisplayName("SPRING-04: B2 + R2 Movie with flag ON starts with correct R2 Movie routing for Screen consumer")
    void spring04_dualConfigurationFlagOnRoutesToR2Movie() {
        contextRunner
                .withUserConfiguration(BaseTestConfig.class, DualStorageConfigFlagOn.class, MediaUploadSessionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaStorageRoutingService routing = context.getBean(MediaStorageRoutingService.class);
                    assertThat(routing.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-video")).isEqualTo(R2_MOVIE_PROVIDER);
                    assertThat(routing.resolveWriteProvider(MediaType.VIDEO, "screen-video")).isEqualTo(B2_PROVIDER);
                });
    }

    @Test
    @DisplayName("SPRING-05: Missing Movie R2 adapter while movie routing is enabled fails closed before new write")
    void spring05_missingR2AdapterFlagOnFailsBeforeAllocation() {
        contextRunner
                .withUserConfiguration(BaseTestConfig.class, MissingR2AdapterFlagOnConfig.class, MediaUploadSessionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaUploadSessionService service = context.getBean(MediaUploadSessionService.class);

                    // Trying to allocate when routed to r2-movie but r2-movie port is missing must fail closed
                    assertThatThrownBy(() -> service.allocateSession(
                            "admin-1",
                            "screen",
                            "SCREEN_ENTRY",
                            UUID.randomUUID(),
                            "idem-test-1",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            "movie.mp4",
                            "screen-video"
                    )).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("MultipartStoragePort is not configured or available for provider: r2-movie");
                });
    }

    @Test
    @DisplayName("SPRING-06: No MultipartStoragePort configuration does not accidentally select dummy or unrelated adapter")
    void spring06_noPortsConfigurationFailsClosed() {
        contextRunner
                .withUserConfiguration(BaseTestConfig.class, NoPortsConfig.class, MediaUploadSessionService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MediaUploadSessionService service = context.getBean(MediaUploadSessionService.class);

                    assertThatThrownBy(() -> service.allocateSession(
                            "admin-1",
                            "screen",
                            "SCREEN_ENTRY",
                            UUID.randomUUID(),
                            "idem-test-2",
                            MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                            "movie.mp4",
                            "screen-video"
                    )).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("MultipartStoragePort is not configured or available for provider: b2");
                });
    }

    @Test
    @DisplayName("SPRING-07: Existing Novel TTS r2 bean remains independently registered in StorageProviderRegistry")
    void spring07_novelTtsR2BeanRemainsIndependent() {
        BinaryStoragePort b2Port = mock(BinaryStoragePort.class);
        when(b2Port.providerId()).thenReturn(B2_PROVIDER);

        BinaryStoragePort r2MoviePort = mock(BinaryStoragePort.class);
        when(r2MoviePort.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        BinaryStoragePort r2NovelPort = mock(BinaryStoragePort.class);
        when(r2NovelPort.providerId()).thenReturn(R2_NOVEL_PROVIDER);

        DefaultStorageProviderRegistry registry = new DefaultStorageProviderRegistry(
                List.of(b2Port, r2MoviePort, r2NovelPort)
        );

        assertThat(registry.resolve(R2_NOVEL_PROVIDER)).isSameAs(r2NovelPort);
        assertThat(registry.resolve(R2_MOVIE_PROVIDER)).isSameAs(r2MoviePort);
        assertThat(registry.resolve(B2_PROVIDER)).isSameAs(b2Port);
    }

    @Test
    @DisplayName("SPRING-08: Duplicate provider IDs are rejected deterministically in MediaUploadSessionService")
    void spring08_duplicateProviderIdsRejected() {
        MultipartStoragePort port1 = mock(MultipartStoragePort.class);
        when(port1.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        MultipartStoragePort port2 = mock(MultipartStoragePort.class);
        when(port2.providerId()).thenReturn(R2_MOVIE_PROVIDER);

        assertThatThrownBy(() -> new MediaUploadSessionService(
                mock(MediaUploadSessionRepositoryPort.class),
                mock(StorageKeyAllocationService.class),
                mock(MediaStorageRoutingService.class),
                () -> Instant.now(),
                null,
                List.of(port1, port2),
                null,
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate MultipartStoragePort registration detected for provider ID: r2-movie");
    }
}
