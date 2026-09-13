package com.universe.media.infrastructure.storage.r2;

import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.exceptions.StorageObjectNotFoundException;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in live integration verification against real Cloudflare R2 storage.
 * <p>
 * This test is strictly gated by both an explicit opt-in system property:
 * <ul>
 *     <li>{@code -Dmedia.storage.r2.live-test=true}</li>
 * </ul>
 * AND all required R2 credential environment variables:
 * <ul>
 *     <li>{@code MEDIA_STORAGE_R2_BUCKET}</li>
 *     <li>{@code MEDIA_STORAGE_R2_ENDPOINT}</li>
 *     <li>{@code MEDIA_STORAGE_R2_ACCESS_KEY_ID}</li>
 *     <li>{@code MEDIA_STORAGE_R2_SECRET_ACCESS_KEY}</li>
 * </ul>
 * If the opt-in property is absent/false OR if any credential variable is missing/blank,
 * the test is cleanly skipped.
 */
@Tag("live-r2")
@EnabledIfSystemProperty(
        named = "media.storage.r2.live-test",
        matches = "(?i)true"
)
@EnabledIfEnvironmentVariable(named = "MEDIA_STORAGE_R2_BUCKET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MEDIA_STORAGE_R2_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MEDIA_STORAGE_R2_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MEDIA_STORAGE_R2_SECRET_ACCESS_KEY", matches = ".+")
class R2StorageAdapterLiveIntegrationTest {

    private R2StorageAdapter adapter;
    private StorageKey generatedKey;

    @BeforeEach
    void setUp() {
        assumeTrue(
                Boolean.parseBoolean(System.getProperty("media.storage.r2.live-test")),
                "Cloudflare R2 live integration test skipped: system property -Dmedia.storage.r2.live-test=true not set."
        );
        assumeTrue(
                isConfigured(),
                "Cloudflare R2 live integration test skipped: missing required environment variables."
        );

        String bucket = System.getenv("MEDIA_STORAGE_R2_BUCKET");
        String endpoint = System.getenv("MEDIA_STORAGE_R2_ENDPOINT");
        String accessKeyId = System.getenv("MEDIA_STORAGE_R2_ACCESS_KEY_ID");
        String secretAccessKey = System.getenv("MEDIA_STORAGE_R2_SECRET_ACCESS_KEY");
        String region = System.getenv("MEDIA_STORAGE_R2_REGION");
        if (region == null || region.isBlank()) {
            region = "auto";
        }

        R2StorageProperties properties = new R2StorageProperties(
                bucket,
                endpoint,
                accessKeyId,
                secretAccessKey,
                region
        );
        this.adapter = new R2StorageAdapter(properties);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            if (generatedKey != null) {
                try {
                    adapter.delete(generatedKey);
                } catch (Exception ignored) {
                    // Best-effort cleanup for uniquely owned test key
                }
            }
            try {
                adapter.close();
            } catch (Exception ignored) {
                // Best-effort close
            }
        }
    }

    private static boolean isConfigured() {
        return isNonBlank(System.getenv("MEDIA_STORAGE_R2_BUCKET"))
                && isNonBlank(System.getenv("MEDIA_STORAGE_R2_ENDPOINT"))
                && isNonBlank(System.getenv("MEDIA_STORAGE_R2_ACCESS_KEY_ID"))
                && isNonBlank(System.getenv("MEDIA_STORAGE_R2_SECRET_ACCESS_KEY"));
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isBlank();
    }

    @Test
    @DisplayName("interoperates with Cloudflare R2: store -> open -> openRange -> duplicate reject -> delete -> verify deleted")
    void shouldVerifyFullR2Lifecycle() throws IOException {
        generatedKey = StorageKey.of("objects/r2-smoke/" + UUID.randomUUID());
        byte[] payload = "KiemLai-R2-LiveSmoke-Payload-0123456789-abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        long sizeBytes = payload.length;
        MimeType mimeType = MimeType.of("text/plain");

        long rangeStart = 10L;
        long rangeLength = 15L;
        byte[] expectedSlice = Arrays.copyOfRange(payload, (int) rangeStart, (int) (rangeStart + rangeLength));

        // 1. store a unique test object
        adapter.store(generatedKey, new ByteArrayInputStream(payload), sizeBytes, mimeType);

        // 2. open and verify full content
        try (InputStream in = adapter.open(generatedKey)) {
            byte[] actualContent = in.readAllBytes();
            assertThat(actualContent).isEqualTo(payload);
        }

        // 3. openRange and verify exact partial content
        try (InputStream rangeIn = adapter.openRange(generatedKey, rangeStart, rangeLength)) {
            byte[] actualSlice = rangeIn.readAllBytes();
            assertThat(actualSlice).isEqualTo(expectedSlice);
        }

        // 4. attempt duplicate create-only store and verify StorageObjectAlreadyExistsException
        assertThatThrownBy(() ->
                adapter.store(generatedKey, new ByteArrayInputStream(payload), sizeBytes, mimeType)
        ).isInstanceOf(StorageObjectAlreadyExistsException.class);

        // 5. delete the test object
        adapter.delete(generatedKey);

        // 6. verify the deleted object is no longer readable
        assertThatThrownBy(() -> adapter.open(generatedKey))
                .isInstanceOf(StorageObjectNotFoundException.class);
    }
}
