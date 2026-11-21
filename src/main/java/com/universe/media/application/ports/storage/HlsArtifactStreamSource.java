package com.universe.media.application.ports.storage;

import com.universe.media.domain.StorageKey;

import java.io.IOException;
import java.io.InputStream;

/**
 * Lifecycle-bound source for reading locally verified HLS artifacts during an upload.
 *
 * <p>Replaces detached raw filesystem {@link java.nio.file.Path} exposure at the application
 * port boundary so cleanup of the owning temporary attempt directory cannot race with active
 * synchronous or bounded-asynchronous artifact uploads.
 */
public interface HlsArtifactStreamSource extends AutoCloseable {

    /**
     * Active upload pin preventing the owning local package directory from being deleted
     * until the upload operation finishes or cancels and closes this scope.
     */
    interface ActiveUploadScope extends AutoCloseable {

        /**
         * Signals whether closure of the owning local package has been requested so
         * active upload stages or workers should stop scheduling new work cleanly.
         */
        boolean isCloseRequested();

        @Override
        void close();
    }

    @FunctionalInterface
    interface ArtifactStreamReader<T> {
        T read(InputStream stream, long exactSizeBytes) throws IOException;
    }

    @FunctionalInterface
    interface PinnedUploadWork<T> {
        T execute(ActiveUploadScope scope) throws Exception;
    }

    /**
     * Acquires an active upload pin preventing concurrent cleanup until closed.
     *
     * @throws IllegalStateException if closure has already been requested or completed
     */
    ActiveUploadScope openUploadScope();

    /**
     * Reads one verified artifact's stream under active lifecycle protection.
     * Fails closed if package closure has been requested or completed, or if the underlying
     * file is missing, symlinked, or modified.
     */
    <T> T withArtifactStream(StorageKey objectKey, ArtifactStreamReader<T> reader) throws IOException;

    /**
     * Convenience helper ensuring an {@link ActiveUploadScope} remains pinned across
     * an entire upload operation and is always released before returning.
     */
    default <T> T executeWhilePinned(PinnedUploadWork<T> work) throws Exception {
        try (ActiveUploadScope scope = openUploadScope()) {
            return work.execute(scope);
        }
    }

    @Override
    void close() throws IOException;
}
