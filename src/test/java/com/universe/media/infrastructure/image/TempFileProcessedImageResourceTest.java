package com.universe.media.infrastructure.image;

import com.universe.media.application.exceptions.ImageProcessingException;
import com.universe.media.domain.MimeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TempFileProcessedImageResourceTest {

    @Test
    @DisplayName("outstanding stream prevents resource close and double close does not decrement twice")
    void shouldPreventResourceCloseWhileStreamIsOpenAndHandleDoubleCloseIdempotently() throws IOException {
        Path tempFile = Files.createTempFile("test_lifecycle_", ".tmp");
        Files.writeString(tempFile, "sample data");
        assertThat(Files.exists(tempFile)).isTrue();

        TempFileProcessedImageResource resource = new TempFileProcessedImageResource(
                tempFile,
                MimeType.of("image/jpeg"),
                11L,
                100,
                100
        );

        InputStream stream1 = resource.openStream();
        InputStream stream2 = resource.openStream();

        // 2 active streams: resource.close() fails
        assertThatThrownBy(resource::close)
                .isInstanceOf(ImageProcessingException.class)
                .hasMessageContaining("2 open stream(s)");
        assertThat(Files.exists(tempFile)).isTrue();

        // Close stream 1
        stream1.close();

        // Double close stream 1 (must be idempotent and not decrement active count again)
        stream1.close();

        // 1 active stream remaining: resource.close() still fails
        assertThatThrownBy(resource::close)
                .isInstanceOf(ImageProcessingException.class)
                .hasMessageContaining("1 open stream(s)");
        assertThat(Files.exists(tempFile)).isTrue();

        // Close stream 2
        stream2.close();
        stream2.close();

        // Now resource.close() succeeds and deletes file
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // Resource close is idempotent
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // openStream after close fails
        assertThatThrownBy(resource::openStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has already been closed");
    }

    @Test
    @DisplayName("underlying stream close failure propagates exception, preserves active count, and is truly retryable")
    void shouldMakeUnderlyingStreamCloseTrulyRetryable() throws IOException {
        Path tempFile = Files.createTempFile("test_failure_res_", ".tmp");
        Files.writeString(tempFile, "sample payload");
        assertThat(Files.exists(tempFile)).isTrue();

        AtomicInteger closeInvocations = new AtomicInteger(0);
        AtomicBoolean underlyingClosed = new AtomicBoolean(false);
        AtomicBoolean failClose = new AtomicBoolean(true);

        TempFileProcessedImageResource.StreamOpener failingOpener = path -> new FilterInputStream(new ByteArrayInputStream(new byte[]{1, 2, 3})) {
            @Override
            public void close() throws IOException {
                closeInvocations.incrementAndGet();
                if (failClose.get()) {
                    throw new IOException("Simulated disk error on stream close");
                }
                underlyingClosed.set(true);
                super.close();
            }
        };

        TempFileProcessedImageResource resource = new TempFileProcessedImageResource(
                tempFile,
                MimeType.of("image/png"),
                14L,
                200,
                200,
                failingOpener
        );

        InputStream is = resource.openStream();

        // 1. First close attempt on stream throws IOException
        assertThatThrownBy(is::close)
                .isInstanceOf(IOException.class)
                .hasMessage("Simulated disk error on stream close");

        // Explicit proof 1: underlying close invocation count is 1 after failed attempt
        assertThat(closeInvocations.get()).isEqualTo(1);

        // Explicit proof 2: underlying stream is not marked closed
        assertThat(underlyingClosed.get()).isFalse();

        // Explicit proof 3: activeStreams was NOT decremented; resource.close() refuses deletion
        assertThatThrownBy(resource::close)
                .isInstanceOf(ImageProcessingException.class)
                .hasMessageContaining("1 open stream(s)");
        assertThat(Files.exists(tempFile)).isTrue();

        // 2. Retry stream close when underlying issue is resolved
        failClose.set(false);
        is.close();

        // Explicit proof 4: underlying close invocation count becomes 2 after retry
        assertThat(closeInvocations.get()).isEqualTo(2);

        // Explicit proof 5: underlying stream is actually marked closed only after successful retry
        assertThat(underlyingClosed.get()).isTrue();

        // 3. Double close is idempotent and does not call underlying close again
        is.close();
        assertThat(closeInvocations.get()).isEqualTo(2);

        // 4. resource.close() deletes the temp file afterward
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // 5. Resource close is idempotent
        resource.close();
        assertThat(Files.exists(tempFile)).isFalse();

        // 6. openStream after close fails
        assertThatThrownBy(resource::openStream)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has already been closed");
    }
}
