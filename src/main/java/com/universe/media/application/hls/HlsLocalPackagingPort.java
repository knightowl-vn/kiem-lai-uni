package com.universe.media.application.hls;

/**
 * Outbound application port decoupling HLS worker core orchestration from concrete local FFmpeg
 * packaging and artifact inventory infrastructure (MS-08 HLS-2B3B1).
 */
public interface HlsLocalPackagingPort {

    PreparedHlsPackageScope preparePackage(PrepareLocalHlsPackageCommand command);
}
