package com.universe.media.infrastructure.storage.local;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalFilesystemStorageAdapterTest {

    @TempDir
    private Path tempRootDir;

    private LocalFilesystemStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LocalFilesystemStorageAdapter(tempRootDir);
    }

    @Test
    @DisplayName("providerId returns 'local'")
    void shouldReturnLocalProviderId() {
        assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("local"));
    }

    @Nested
    @DisplayName("Store and Open Roundtrip Tests")
    class StoreAndOpenTests {

        @Test
        @DisplayName("stores binary content and reads it back faithfully via open")
        void shouldStoreAndOpenBinaryContentFaithfully() throws IOException {
            StorageKey key = StorageKey.of("media/sample.webp");
            byte[] expectedData = "binary-media-content-12345".getBytes(StandardCharsets.UTF_8);
            MimeType mimeType = MimeType.of("image/webp");

            adapter.store(key, new ByteArrayInputStream(expectedData), expectedData.length, mimeType);

            try (InputStream in = adapter.open(key)) {
                byte[] actualData = in.readAllBytes();
                assertThat(actualData).isEqualTo(expectedData);
            }
        }

        @Test
        @DisplayName("stores binary in deep nested directory creating intermediate directories automatically")
        void shouldStoreInDeepNestedDirectory() throws IOException {
            StorageKey key = StorageKey.of("covers/2026/09/02/novel-cover.webp");
            byte[] data = "nested-directory-content".getBytes(StandardCharsets.UTF_8);

            adapter.store(key, new ByteArrayInputStream(data), data.length, MimeType.of("image/webp"));

            Path storedFile = tempRootDir.resolve("covers/2026/09/02/novel-cover.webp");
            assertThat(Files.isRegularFile(storedFile)).isTrue();
            assertThat(Files.readAllBytes(storedFile)).isEqualTo(data);
        }

        @Test
        @DisplayName("create-only: storing with an existing key throws StorageObjectAlreadyExistsException and preserves original content")
        void shouldRejectDuplicateStoreAndPreserveOriginalContent() throws IOException {
            StorageKey key = StorageKey.of("unique/image.webp");
            byte[] initialData = "initial-binary-data".getBytes(StandardCharsets.UTF_8);
            byte[] secondData = "second-binary-data".getBytes(StandardCharsets.UTF_8);

            adapter.store(key, new ByteArrayInputStream(initialData), initialData.length, MimeType.of("image/webp"));

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(secondData), secondData.length, MimeType.of("image/webp"))
            ).isInstanceOf(StorageObjectAlreadyExistsException.class)
                    .hasMessageContaining("unique/image.webp");

            try (InputStream in = adapter.open(key)) {
                assertThat(in.readAllBytes()).isEqualTo(initialData);
            }
        }

        @Test
        @DisplayName("open on non-existent key throws StorageObjectNotFoundException")
        void shouldThrowWhenOpeningNonExistentObject() {
            StorageKey key = StorageKey.of("non/existent/key.webp");

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(StorageObjectNotFoundException.class)
                    .hasMessageContaining("non/existent/key.webp");
        }
    }

    @Nested
    @DisplayName("Stream Ownership and Size Validation Tests")
    class StreamAndSizeTests {

        @Test
        @DisplayName("store does NOT close the caller-provided InputStream")
        void shouldNotCloseCallerInputStreamOnStore() {
            StorageKey key = StorageKey.of("stream-test/file.webp");
            byte[] data = "stream-ownership-test-data".getBytes(StandardCharsets.UTF_8);

            AtomicBoolean closed = new AtomicBoolean(false);
            InputStream trackableStream = new FilterInputStream(new ByteArrayInputStream(data)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };

            adapter.store(key, trackableStream, data.length, MimeType.of("image/webp"));

            assertThat(closed.get()).isFalse();
        }

        @Test
        @DisplayName("store throws StorageException and cleans temp file when stream is shorter than declared sizeBytes")
        void shouldFailAndCleanupWhenStreamIsShorterThanDeclaredSize() throws IOException {
            StorageKey key = StorageKey.of("short-stream/file.webp");
            byte[] data = "short".getBytes(StandardCharsets.UTF_8);
            long declaredSize = 100L; // Declared 100, provided 5

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(data), declaredSize, MimeType.of("image/webp"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Payload size mismatch");

            assertThat(Files.exists(tempRootDir.resolve("short-stream/file.webp"))).isFalse();
            assertNoTempFilesRemain(tempRootDir);
        }

        @Test
        @DisplayName("store throws StorageException and cleans temp file when stream is longer than declared sizeBytes")
        void shouldFailAndCleanupWhenStreamIsLongerThanDeclaredSize() throws IOException {
            StorageKey key = StorageKey.of("long-stream/file.webp");
            byte[] data = "this payload is longer than 5 bytes".getBytes(StandardCharsets.UTF_8);
            long declaredSize = 5L;

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(data), declaredSize, MimeType.of("image/webp"))
            ).isInstanceOf(StorageException.class)
                    .hasMessageContaining("Payload size exceeded");

            assertThat(Files.exists(tempRootDir.resolve("long-stream/file.webp"))).isFalse();
            assertNoTempFilesRemain(tempRootDir);
        }
    }

    @Nested
    @DisplayName("Delete Tests")
    class DeleteTests {

        @Test
        @DisplayName("delete removes existing stored file")
        void shouldDeleteExistingFile() throws IOException {
            StorageKey key = StorageKey.of("delete-test/to-delete.webp");
            byte[] data = "to-delete".getBytes(StandardCharsets.UTF_8);

            adapter.store(key, new ByteArrayInputStream(data), data.length, MimeType.of("image/webp"));
            Path storedPath = tempRootDir.resolve("delete-test/to-delete.webp");
            assertThat(Files.exists(storedPath)).isTrue();

            adapter.delete(key);
            assertThat(Files.exists(storedPath)).isFalse();
        }

        @Test
        @DisplayName("delete is idempotent: deleting non-existent key succeeds silently")
        void shouldSucceedSilentlyWhenDeletingNonExistentFile() {
            StorageKey key = StorageKey.of("delete-test/non-existent.webp");

            adapter.delete(key);
        }
    }

    @Nested
    @DisplayName("Path Traversal and Security Hardening Tests")
    class SecurityAndPathConfinementTests {

        @ParameterizedTest(name = "rejects unsafe path pattern: {0}")
        @ValueSource(strings = {
                "/etc/passwd",
                "\\Windows\\System32\\cmd.exe",
                "../escape.txt",
                "nested/../../escape.txt",
                "nested/..\\escape.txt",
                "nested/..\\..\\escape.txt",
                "C:/Windows/system.ini",
                "D:\\data\\file.txt",
                "a//b.txt",
                "a/b/",
                "a/./b.txt"
        })
        void shouldRejectUnsafeOrEscapingStorageKeys(String unsafeKey) {
            StorageKey key = StorageKey.of(unsafeKey);
            byte[] data = "unsafe".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(data), data.length, MimeType.of("image/webp"))
            ).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> adapter.delete(key))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects key containing null byte character")
        void shouldRejectNullByteInStorageKey() {
            StorageKey key = StorageKey.of("nested/\0file.webp");
            byte[] data = "payload".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(data), data.length, MimeType.of("image/webp"))
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null character");
        }

        @Test
        @DisplayName("rejects operations when storage path contains symbolic link component")
        void shouldRejectWhenPathContainsSymbolicLink() throws IOException {
            Path targetDir = tempRootDir.resolve("real_dir");
            Files.createDirectories(targetDir);
            Path symlinkDir = tempRootDir.resolve("symlink_dir");

            try {
                Files.createSymbolicLink(symlinkDir, targetDir);
            } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
                assumeTrue(false, "Symlink creation not permitted in this environment");
                return;
            }

            StorageKey key = StorageKey.of("symlink_dir/file.webp");
            byte[] data = "payload".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() ->
                    adapter.store(key, new ByteArrayInputStream(data), data.length, MimeType.of("image/webp"))
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Symbolic links");

            assertThatThrownBy(() -> adapter.open(key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Symbolic links");

            assertThatThrownBy(() -> adapter.delete(key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Symbolic links");
        }
    }

    @Nested
    @DisplayName("Input Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("store rejects null parameters and negative size")
        void shouldRejectNullOrInvalidParametersOnStore() {
            StorageKey key = StorageKey.of("valid/key.webp");
            InputStream content = new ByteArrayInputStream(new byte[0]);
            MimeType mimeType = MimeType.of("image/webp");

            assertThatThrownBy(() -> adapter.store(null, content, 0L, mimeType))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> adapter.store(key, null, 0L, mimeType))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> adapter.store(key, content, 0L, null))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> adapter.store(key, content, -1L, mimeType))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("open rejects null key")
        void shouldRejectNullKeyOnOpen() {
            assertThatThrownBy(() -> adapter.open(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("delete rejects null key")
        void shouldRejectNullKeyOnDelete() {
            assertThatThrownBy(() -> adapter.delete(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private void assertNoTempFilesRemain(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            long tempFileCount = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(".upload-") && name.endsWith(".tmp"))
                    .count();
            assertThat(tempFileCount)
                    .withFailMessage("Expected no temporary upload files to remain, but found %d", tempFileCount)
                    .isZero();
        }
    }
}
