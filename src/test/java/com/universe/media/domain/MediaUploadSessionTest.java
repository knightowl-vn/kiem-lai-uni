package com.universe.media.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUploadSessionTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/test.mp4");
    private static final String VALID_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private MediaUploadSession createValidSession() {
        return createValidSession(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2); // 50 MiB, 2 parts
    }

    private MediaUploadSession createValidSession(long declaredSizeBytes) {
        Instant now = Instant.now();
        return MediaUploadSession.create(
                UUID.randomUUID(),
                "user-123",
                "screen",
                "ScreenItem",
                UUID.randomUUID(),
                "idem-key-1",
                declaredSizeBytes,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                "video.mp4",
                "screen",
                now.plus(24, ChronoUnit.HOURS),
                now
        );
    }

    @Nested
    @DisplayName("Creation & Invariant Tests")
    class CreationTests {

        @Test
        @DisplayName("Successfully creates session with valid inputs and calculates expected parts")
        void createsSuccessfully() {
            MediaUploadSession session = createValidSession(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ALLOCATED);
            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.NONE);
            assertThat(session.getInitiatorPrincipalId()).isEqualTo("user-123");
            assertThat(session.getIdempotencyKey()).isEqualTo("idem-key-1");
            assertThat(session.getConsumerContext()).isEqualTo("screen");
            assertThat(session.getTargetEntityType()).isEqualTo("ScreenItem");
            assertThat(session.getMediaType()).isEqualTo(MediaType.VIDEO);
            assertThat(session.getDeclaredMimeType()).isEqualTo("video/mp4");
            assertThat(session.getPartSizeBytes()).isEqualTo(MediaUploadSession.FIXED_PART_SIZE_BYTES);
            assertThat(session.getDeclaredSizeBytes()).isEqualTo(MediaUploadSession.FIXED_PART_SIZE_BYTES * 2);
            assertThat(session.getExpectedPartCount()).isEqualTo(2);
            assertThat(session.getStorageProviderId()).isEqualTo(B2_PROVIDER);
            assertThat(session.getStorageBucketName()).isEqualTo(B2_BUCKET);
            assertThat(session.getStorageKey()).isEqualTo(STORAGE_KEY);
            assertThat(session.getProviderUploadId()).isNull();
            assertThat(session.getDeclaredContentHash()).isNull();
            assertThat(session.getRegisteredAssetId()).isNull();
        }

        @Test
        @DisplayName("Calculates single expected part when max size is less than part size")
        void singlePartCalculation() {
            MediaUploadSession session = createValidSession(10_000_000L); // 10 MB < 25 MiB
            assertThat(session.getExpectedPartCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Calculates ceil division expected parts correctly")
        void ceilDivisionCalculation() {
            MediaUploadSession session = createValidSession(MediaUploadSession.FIXED_PART_SIZE_BYTES + 1);
            assertThat(session.getExpectedPartCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Rejects max size exceeding 50 GiB")
        void rejectsExceedingMaxVideoSize() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-123",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.MAX_VIDEO_SIZE_BYTES + 1,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "video.mp4",
                    "screen",
                    now.plus(1, ChronoUnit.HOURS),
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("53687091200");
        }

        @Test
        @DisplayName("Rejects provider other than b2")
        void rejectsNonB2Provider() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-123",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES,
                    StorageProviderId.of("local"),
                    B2_BUCKET,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "video.mp4",
                    "screen",
                    now.plus(1, ChronoUnit.HOURS),
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("b2");
        }

        @Test
        @DisplayName("Rejects sentinel bucket NONE for B2")
        void rejectsSentinelBucketForB2() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> MediaUploadSession.create(
                    UUID.randomUUID(),
                    "user-123",
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES,
                    B2_PROVIDER,
                    StorageKeyAllocation.CANONICAL_BUCKETLESS_SENTINEL,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "video.mp4",
                    "screen",
                    now.plus(1, ChronoUnit.HOURS),
                    now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-sentinel bucket");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("Rejects blank initiatorPrincipalId")
        void rejectsBlankInitiator(String initiator) {
            Instant now = Instant.now();
            assertThatThrownBy(() -> MediaUploadSession.create(
                    UUID.randomUUID(),
                    initiator,
                    "screen",
                    "ScreenItem",
                    UUID.randomUUID(),
                    "idem-1",
                    MediaUploadSession.FIXED_PART_SIZE_BYTES,
                    B2_PROVIDER,
                    B2_BUCKET,
                    STORAGE_KEY,
                    UUID.randomUUID(),
                    "video.mp4",
                    "screen",
                    now.plus(1, ChronoUnit.HOURS),
                    now
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("State Transition Tests")
    class StateTransitionTests {

        @Test
        @DisplayName("Transitions from ALLOCATED to INITIATED with provider upload id")
        void initiatesSuccessfully() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.INITIATED);
            assertThat(session.getProviderUploadId()).isEqualTo("b2-upload-xyz");
        }

        @Test
        @DisplayName("Rejects initiating twice")
        void rejectsReinitiating() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);

            assertThatThrownBy(() -> session.markInitiated("b2-upload-abc", now))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot initiate upload session with status: INITIATED");
        }

        @Test
        @DisplayName("Transitions from INITIATED to COMPLETING with valid hash")
        void completingSuccessfully() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);
            session.markCompleting(VALID_HASH, now);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETING);
            assertThat(session.getDeclaredContentHash()).isEqualTo(VALID_HASH);
        }

        @Test
        @DisplayName("Rejects completing with invalid hash")
        void rejectsInvalidHash() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);

            assertThatThrownBy(() -> session.markCompleting("short-hash", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("64-character lowercase hex");
        }

        @Test
        @DisplayName("Transitions from COMPLETING to COMPLETED with media asset id")
        void completedSuccessfully() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);
            session.markCompleting(VALID_HASH, now);
            UUID assetId = UUID.randomUUID();
            session.markCompleted(assetId, now);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.COMPLETED);
            assertThat(session.getRegisteredAssetId()).isEqualTo(assetId);
            assertThat(session.getCompletedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Rejects completing when not in COMPLETING status")
        void rejectsCompletedWhenNotCompleting() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);

            assertThatThrownBy(() -> session.markCompleted(UUID.randomUUID(), now))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Aborts session from ALLOCATED or INITIATED")
        void abortsSuccessfully() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.abort(now);

            assertThat(session.getStatus()).isEqualTo(MediaUploadSessionStatus.ABORTED);
        }

        @Test
        @DisplayName("Rejects aborting already completed session")
        void rejectsAbortingCompletedSession() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.markInitiated("b2-upload-xyz", now);
            session.markCompleting(VALID_HASH, now);
            session.markCompleted(UUID.randomUUID(), now);

            assertThatThrownBy(() -> session.abort(now))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot abort session in terminal state");
        }

        @Test
        @DisplayName("Marks cleanup status")
        void marksCleanupStatus() {
            MediaUploadSession session = createValidSession();
            Instant now = Instant.now();
            session.abort(now);
            session.markCleanupPending(now);

            assertThat(session.getCleanupStatus()).isEqualTo(UploadSessionCleanupStatus.PENDING);
        }
    }
}
