package com.universe.media.infrastructure.storage.local;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.application.ports.storage.BinaryStoragePort;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Local filesystem implementation of {@link BinaryStoragePort}.
 * <p>
 * Enforces local storage semantics:
 * <ul>
 *     <li>Provider ID: {@code local}</li>
 *     <li>Strict create-only storage with temporary-file staged writes</li>
 *     <li>Path and symlink confinement within the configured root directory</li>
 *     <li>Caller ownership preservation for input/output streams</li>
 *     <li>Idempotent deletions</li>
 * </ul>
 */
@Component
public class LocalFilesystemStorageAdapter implements BinaryStoragePort {

    public static final StorageProviderId PROVIDER_ID =
            StorageProviderId.of("local");

    private static final int BUFFER_SIZE = 8192;

    private final Path rootDir;

    @Autowired
    public LocalFilesystemStorageAdapter(
            @Value("${media.storage.local.root-dir:uploads/media}") String rootDir
    ) {
        this(Paths.get(rootDir));
    }

    public LocalFilesystemStorageAdapter(
            Path rootDir
    ) {
        this.rootDir = Objects.requireNonNull(
                rootDir,
                "Root directory cannot be null."
        ).toAbsolutePath().normalize();
    }

    @Override
    public StorageProviderId providerId() {
        return PROVIDER_ID;
    }

    @Override
    public void store(
            StorageKey key,
            InputStream content,
            long sizeBytes,
            MimeType mimeType
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        Objects.requireNonNull(content, "Content InputStream cannot be null.");
        Objects.requireNonNull(mimeType, "MimeType cannot be null.");

        if (sizeBytes < 0) {
            throw new IllegalArgumentException(
                    "sizeBytes cannot be negative: " + sizeBytes
            );
        }

        try {
            Path targetPath = resolveAndVerifyPath(key);

            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageObjectAlreadyExistsException(key);
            }

            Path parentDir = targetPath.getParent();
            Path tempFile = null;

            try {
                if (parentDir != null) {
                    Files.createDirectories(parentDir);
                }

                tempFile = Files.createTempFile(
                        parentDir != null ? parentDir : rootDir,
                        ".upload-",
                        ".tmp"
                );

                long bytesWritten = 0;
                byte[] buffer = new byte[BUFFER_SIZE];

                try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = content.read(buffer)) != -1) {
                        bytesWritten += read;
                        if (bytesWritten > sizeBytes) {
                            throw new StorageException(
                                    "Payload size exceeded declared sizeBytes: declared="
                                            + sizeBytes
                                            + ", received at least="
                                            + bytesWritten
                            );
                        }
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                if (bytesWritten != sizeBytes) {
                    throw new StorageException(
                            "Payload size mismatch: declared="
                                    + sizeBytes
                                    + ", received="
                                    + bytesWritten
                    );
                }

                // Final publication: standard move without REPLACE_EXISTING to guarantee CREATE-ONLY
                Files.move(tempFile, targetPath);

            } catch (FileAlreadyExistsException e) {
                deleteQuietly(tempFile);
                throw new StorageObjectAlreadyExistsException(key, e);
            } catch (StorageException e) {
                deleteQuietly(tempFile);
                throw e;
            } catch (SecurityException e) {
                deleteQuietly(tempFile);
                throw new StorageException(
                        "Storage access denied for key: " + key.value(),
                        e
                );
            } catch (IOException e) {
                deleteQuietly(tempFile);
                throw new StorageException(
                        "Failed to store binary content for key: " + key.value(),
                        e
                );
            } catch (Exception e) {
                deleteQuietly(tempFile);
                throw e;
            }
        } catch (SecurityException e) {
            throw new StorageException(
                    "Storage access denied for key: " + key.value(),
                    e
            );
        }
    }

    @Override
    public InputStream open(
            StorageKey key
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        try {
            Path targetPath = resolveAndVerifyPath(key);

            if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(targetPath)) {
                throw new StorageObjectNotFoundException(key);
            }

            try {
                return Files.newInputStream(targetPath, StandardOpenOption.READ);
            } catch (NoSuchFileException e) {
                throw new StorageObjectNotFoundException(key, e);
            } catch (SecurityException e) {
                throw new StorageException(
                        "Storage access denied for key: " + key.value(),
                        e
                );
            } catch (IOException e) {
                throw new StorageException(
                        "Failed to open binary content for key: " + key.value(),
                        e
                );
            }
        } catch (SecurityException e) {
            throw new StorageException(
                    "Storage access denied for key: " + key.value(),
                    e
            );
        }
    }

    @Override
    public InputStream openRange(
            StorageKey key,
            long startInclusive,
            long length
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");
        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive cannot be negative: " + startInclusive);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }

        try {
            Path targetPath = resolveReadableRegularFile(key);
            FileChannel channel = null;
            try {
                channel = FileChannel.open(targetPath, StandardOpenOption.READ);
                channel.position(startInclusive);
                return new BoundedInputStream(Channels.newInputStream(channel), length);
            } catch (NoSuchFileException e) {
                closeQuietly(channel);
                throw new StorageObjectNotFoundException(key, e);
            } catch (SecurityException e) {
                closeQuietly(channel);
                throw new StorageException(
                        "Storage access denied for key: " + key.value(),
                        e
                );
            } catch (IOException e) {
                closeQuietly(channel);
                throw new StorageException(
                        "Failed to open binary range for key: " + key.value(),
                        e
                );
            }
        } catch (SecurityException e) {
            throw new StorageException(
                    "Storage access denied for key: " + key.value(),
                    e
            );
        }
    }

    @Override
    public void delete(
            StorageKey key
    ) {
        Objects.requireNonNull(key, "StorageKey cannot be null.");

        try {
            Path targetPath = resolveAndVerifyPath(key);

            try {
                Files.deleteIfExists(targetPath);
            } catch (SecurityException e) {
                throw new StorageException(
                        "Storage access denied for key: " + key.value(),
                        e
                );
            } catch (IOException e) {
                throw new StorageException(
                        "Failed to delete binary content for key: " + key.value(),
                        e
                );
            }
        } catch (SecurityException e) {
            throw new StorageException(
                    "Storage access denied for key: " + key.value(),
                    e
            );
        }
    }

    private Path resolveAndVerifyPath(
            StorageKey key
    ) {
        String rawKey = key.value();

        if (rawKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "StorageKey contains null character: " + rawKey
            );
        }

        if (rawKey.startsWith("/") || rawKey.startsWith("\\")) {
            throw new IllegalArgumentException(
                    "StorageKey must be a relative path: " + rawKey
            );
        }

        if (rawKey.length() >= 2 && Character.isLetter(rawKey.charAt(0)) && rawKey.charAt(1) == ':') {
            throw new IllegalArgumentException(
                    "StorageKey must not contain a drive letter: " + rawKey
            );
        }

        String normalizedSeparators = rawKey.replace('\\', '/');
        String[] segments = normalizedSeparators.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        "StorageKey must not contain empty path segments: " + rawKey
                );
            }
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new IllegalArgumentException(
                        "StorageKey must not contain relative path traversal segments: " + rawKey
                );
            }
        }

        Path resolved = rootDir.resolve(rawKey).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new IllegalArgumentException(
                    "StorageKey resolves outside the root directory: " + rawKey
            );
        }

        verifyNoSymbolicLinks(resolved);

        return resolved;
    }

    private Path resolveReadableRegularFile(
            StorageKey key
    ) {
        Path targetPath = resolveAndVerifyPath(key);
        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(targetPath)) {
            throw new StorageObjectNotFoundException(key);
        }
        return targetPath;
    }

    private void verifyNoSymbolicLinks(
            Path targetPath
    ) {
        Path current = rootDir;
        Path relative = rootDir.relativize(targetPath);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                        "Symbolic links are not allowed in storage path: " + targetPath
                );
            }
        }
    }

    private void deleteQuietly(
            Path path
    ) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException | SecurityException ignored) {
                // Best effort cleanup
            }
        }
    }

    private void closeQuietly(
            FileChannel channel
    ) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Best effort cleanup after a failed range open
            }
        }
    }

    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long length) {
            this.delegate = delegate;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = delegate.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int boundedLength = (int) Math.min((long) length, remaining);
            int bytesRead = delegate.read(buffer, offset, boundedLength);
            if (bytesRead > 0) {
                remaining -= bytesRead;
            }
            return bytesRead;
        }

        @Override
        public long skip(long byteCount) throws IOException {
            if (byteCount <= 0 || remaining == 0) {
                return 0;
            }
            long skipped = delegate.skip(Math.min(byteCount, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min((long) delegate.available(), Math.min(remaining, Integer.MAX_VALUE));
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
