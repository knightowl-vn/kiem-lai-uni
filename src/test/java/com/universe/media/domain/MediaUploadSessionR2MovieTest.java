package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadSessionR2MovieTest {

    private static final String R2_MOVIE_BUCKET = "kiemlai-r2-movie-test";
    private static final StorageKey STORAGE_KEY = StorageKey.of("objects/movie/test-video.mp4");
    private static final StorageProviderId R2_MOVIE_PROVIDER = StorageProviderId.of("r2-movie");

    @Test
    @DisplayName("creates valid MediaUploadSession with r2-movie provider")
    void createsValidSessionWithR2MovieProvider() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        Instant expiresAt = now.plus(1, ChronoUnit.DAYS);

        MediaUploadSession session = MediaUploadSession.create(
                sessionId,
                "admin-user",
                "screen",
                "ScreenEntry",
                targetId,
                "idem-key-1",
                MediaUploadSession.FIXED_PART_SIZE_BYTES * 2,
                R2_MOVIE_PROVIDER,
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                allocId,
                "feature-film.mp4",
                "screen.entry:feature-film",
                expiresAt,
                now
        );

        assertThat(session).isNotNull();
        assertThat(session.getId()).isEqualTo(sessionId);
        assertThat(session.getStorageProviderId()).isEqualTo(R2_MOVIE_PROVIDER);
        assertThat(session.getStorageBucketName()).isEqualTo(R2_MOVIE_BUCKET);
        assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
        assertThat(session.getExpectedPartCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("rejects sentinel bucket NONE for r2-movie provider")
    void rejectsSentinelBucketForR2Movie() {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        Instant expiresAt = now.plus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> MediaUploadSession.create(
                UUID.randomUUID(),
                "admin-user",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-key-2",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                R2_MOVIE_PROVIDER,
                "NONE",
                STORAGE_KEY,
                UUID.randomUUID(),
                "feature-film.mp4",
                "screen.entry:feature-film",
                expiresAt,
                now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("r2-movie upload session requires an authentic non-sentinel bucket name");
    }

    @Test
    @DisplayName("rejects unsupported provider other than b2 or r2-movie")
    void rejectsUnsupportedProvider() {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        Instant expiresAt = now.plus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> MediaUploadSession.create(
                UUID.randomUUID(),
                "admin-user",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-key-3",
                MediaUploadSession.FIXED_PART_SIZE_BYTES,
                StorageProviderId.of("unsupported-provider"),
                R2_MOVIE_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                "feature-film.mp4",
                "screen.entry:feature-film",
                expiresAt,
                now
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 'b2' or 'r2-movie'");
    }
}
