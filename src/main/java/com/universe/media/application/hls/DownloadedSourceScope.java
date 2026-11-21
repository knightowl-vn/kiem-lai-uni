package com.universe.media.application.hls;

import com.universe.media.domain.ContentHash;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Explicit caller-owned lifecycle scope retaining a locally downloaded and SHA-256-verified
 * source MP4 file until the future packaging caller closes it (MS-08 HLS2B3A1).
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Expose {@link #sourceFile()} only while the scope remains open.</li>
 *   <li>Delete only its isolated temporary source file on {@link #close()}.</li>
 *   <li>Support idempotent repeated {@link #close()} calls without failing.</li>
 * </ul>
 */
public interface DownloadedSourceScope extends AutoCloseable {

    /**
     * Returns the verified local temporary source MP4 file path while this scope is open.
     *
     * @throws IllegalStateException if this scope has already been closed
     */
    Path sourceFile();

    MediaVideoSourceSnapshot snapshot();

    ContentHash verifiedSha256();

    long verifiedSizeBytes();

    boolean isClosed();

    @Override
    void close() throws IOException;
}
