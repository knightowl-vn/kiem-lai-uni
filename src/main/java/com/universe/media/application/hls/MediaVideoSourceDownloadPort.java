package com.universe.media.application.hls;

/**
 * Outbound application port for safely downloading an immutable source MP4 version
 * to an isolated local temporary file outside any database transaction (MS-08 HLS2B3A1).
 */
public interface MediaVideoSourceDownloadPort {

    /**
     * Downloads the binary identified by {@code snapshot} using bounded chunked reads,
     * verifies exact byte length and SHA-256 digest, and returns an explicit lifecycle scope.
     */
    DownloadedSourceScope downloadVerifiedSource(MediaVideoSourceSnapshot snapshot);
}
