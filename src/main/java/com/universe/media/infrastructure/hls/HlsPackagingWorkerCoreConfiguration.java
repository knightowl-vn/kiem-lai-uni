package com.universe.media.infrastructure.hls;

import com.universe.media.application.hls.CompleteVerifiedHlsPackagingUseCase;
import com.universe.media.application.hls.FailMediaVideoPackagingJobUseCase;
import com.universe.media.application.hls.HlsLocalPackagingPort;
import com.universe.media.application.hls.HlsPackagingArtifactLimits;
import com.universe.media.application.hls.MediaVideoSourceDownloadPort;
import com.universe.media.application.hls.ProcessClaimedHlsPackagingLeaseUseCase;
import com.universe.media.application.hls.ResolveMediaVideoSourceSnapshotUseCase;
import com.universe.media.application.ports.storage.HlsPackageArtifactStoragePort;
import com.universe.media.application.ports.storage.HlsPackageKeyValidator;
import com.universe.media.infrastructure.storage.r2hls.R2HlsStorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Passive Spring configuration registering {@link ProcessClaimedHlsPackagingLeaseUseCase} only
 * when both local packaging and R2 HLS artifact storage are explicitly enabled.
 * Schedules no background tasks, starts no workers, and performs zero startup work.
 */
@Configuration
@Conditional(HlsPackagingWorkerCoreCondition.class)
public class HlsPackagingWorkerCoreConfiguration {

    @Bean
    public ProcessClaimedHlsPackagingLeaseUseCase processClaimedHlsPackagingLeaseUseCase(
            ResolveMediaVideoSourceSnapshotUseCase resolveSourceSnapshotUseCase,
            MediaVideoSourceDownloadPort sourceDownloadPort,
            HlsLocalPackagingPort localPackagingPort,
            HlsPackageArtifactStoragePort packageArtifactStoragePort,
            CompleteVerifiedHlsPackagingUseCase completeVerifiedHlsPackagingUseCase,
            FailMediaVideoPackagingJobUseCase failMediaVideoPackagingJobUseCase,
            R2HlsStorageProperties r2HlsStorageProperties
    ) {
        LocalHlsPackageInventory.InventoryLimits limits =
                LocalHlsPackageInventory.InventoryLimits.stricterOfDefaultsAndRemote(
                        r2HlsStorageProperties.maxVerificationCount(),
                        r2HlsStorageProperties.maxArtifactSizeBytes()
                );
        HlsPackagingArtifactLimits policy = new HlsPackagingArtifactLimits(
                limits.maxObjectCount(),
                limits.maxSingleObjectBytes(),
                HlsPackagingArtifactLimits.DEFAULT_BASE_RETRY_BACKOFF,
                HlsPackagingArtifactLimits.DEFAULT_MAX_RETRY_BACKOFF
        );
        return new ProcessClaimedHlsPackagingLeaseUseCase(
                resolveSourceSnapshotUseCase,
                sourceDownloadPort,
                localPackagingPort,
                packageArtifactStoragePort,
                completeVerifiedHlsPackagingUseCase,
                failMediaVideoPackagingJobUseCase,
                HlsPackageKeyValidator.REQUIRED_PROVIDER_ID,
                r2HlsStorageProperties.bucket(),
                policy
        );
    }
}
