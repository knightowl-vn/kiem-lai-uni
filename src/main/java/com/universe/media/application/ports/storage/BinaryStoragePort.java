package com.universe.media.application.ports.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;

import java.io.InputStream;

/**
 * Outbound port for streaming provider-neutral binary storage operations.
 * <p>
 * Core Contract Semantics:
 * <ul>
 *     <li>Streaming I/O only; memory buffers (byte[]) and web constructs (MultipartFile) are prohibited.</li>
 *     <li>{@link #store} is CREATE-ONLY. If an object already exists at the given key, it must fail with {@link StorageObjectAlreadyExistsException} and never overwrite.</li>
 *     <li>{@link #open} must fail with {@link StorageObjectNotFoundException} if the targeted key does not exist.</li>
 *     <li>{@link #delete} is idempotent: deleting a non-existent or previously deleted key must succeed silently.</li>
 *     <li>The caller retains ownership of the {@link InputStream} passed to {@link #store}; implementations must NOT close the store input stream.</li>
 *     <li>The caller owns and is responsible for closing the {@link InputStream} returned by {@link #open}.</li>
 *     <li>This port deals strictly with binary transport and contains no delivery URLs, deduplication, or vendor-specific constructs.</li>
 * </ul>
 */
public interface BinaryStoragePort {

    /**
     * Identifies the storage provider handled by this port implementation.
     *
     * @return the unique, provider-neutral {@link StorageProviderId} (e.g. "local", "s3", "cloudinary")
     */
    StorageProviderId providerId();

    /**
     * Stores a binary stream under the specified opaque storage key.
     * <p>
     * This operation is CREATE-ONLY. If an object already exists for the given {@code key},
     * this method must fail and never overwrite existing data.
     * <p>
     * <b>Stream Ownership:</b> The caller retains ownership of the {@code content} stream.
     * Implementations must NOT close the passed {@code content} stream.
     *
     * @param key       the unique opaque storage key
     * @param content   the binary content input stream
     * @param sizeBytes the exact size of the payload in bytes (must be >= 0)
     * @param mimeType  the validated MIME type of the binary payload
     * @throws StorageObjectAlreadyExistsException if an object already exists at {@code key}
     * @throws StorageException                   if storage fails due to underlying I/O or provider error
     * @throws NullPointerException               if any required parameter is null
     * @throws IllegalArgumentException           if sizeBytes is negative
     */
    void store(
            StorageKey key,
            InputStream content,
            long sizeBytes,
            MimeType mimeType
    );

    /**
     * Opens and returns an input stream to read the binary payload associated with the given key.
     * <p>
     * <b>Stream Ownership:</b> The caller is responsible for properly closing the returned {@link InputStream}.
     *
     * @param key the unique opaque storage key
     * @return an open {@link InputStream} containing the stored binary data
     * @throws StorageObjectNotFoundException if no object exists at {@code key}
     * @throws StorageException               if reading fails due to underlying I/O or provider error
     * @throws NullPointerException           if key is null
     */
    InputStream open(
            StorageKey key
    );

    /**
     * Deletes the binary object identified by the given key.
     * <p>
     * <b>Idempotency:</b> Deleting a key that does not exist or was already deleted must complete
     * cleanly without throwing {@link StorageObjectNotFoundException}.
     *
     * @param key the unique opaque storage key
     * @throws StorageException     if deletion fails due to underlying provider or permission errors
     * @throws NullPointerException if key is null
     */
    void delete(
            StorageKey key
    );
}
