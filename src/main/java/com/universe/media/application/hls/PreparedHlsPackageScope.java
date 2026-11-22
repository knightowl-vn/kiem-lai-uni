package com.universe.media.application.hls;

import com.universe.media.application.ports.storage.HlsArtifactStreamSource;
import com.universe.media.application.ports.storage.HlsExpectedArtifactManifest;

import java.io.IOException;

/**
 * Caller-owned lifecycle scope holding one locally packaged, locally verified, and bounded-inventoried
 * HLS output directory until upload finishes or fails and {@link #close()} releases scratch resources.
 */
public interface PreparedHlsPackageScope extends AutoCloseable {

    HlsExpectedArtifactManifest expectedManifest();

    HlsArtifactStreamSource artifactStreamSource();

    int segmentDurationSeconds();

    long totalSizeBytes();

    int objectCount();

    boolean isClosed();

    @Override
    void close() throws IOException;
}
