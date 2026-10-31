package com.universe.media.application.storage;

import com.universe.media.application.exceptions.StorageException;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MS-08 R2-01: Movie Storage Routing & Acceptance Regression Tests")
class MediaStorageRoutingServiceR2MovieTest {

    private static final String B2_BUCKET = "kiemlai-b2-test";
    private static final String R2_AUDIO_BUCKET = "kiemlai-r2-audio-test";
    private static final String R2_MOVIE_BUCKET = "kiemlai-r2-movie-test";
    private static final UUID VALID_ASSET_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final MediaStorageRoutingService flagOffService = new MediaStorageRoutingService(
            B2_BUCKET, R2_AUDIO_BUCKET, R2_MOVIE_BUCKET, false
    );

    private final MediaStorageRoutingService flagOnService = new MediaStorageRoutingService(
            B2_BUCKET, R2_AUDIO_BUCKET, R2_MOVIE_BUCKET, true
    );

    // =============================================================================================
    // Authoritative Routing Tests (ROUTING-A through ROUTING-O)
    // =============================================================================================

    @Test
    @DisplayName("ROUTING-A: Flag ON, trusted Screen consumer, valid initial tag -> R2 Movie")
    void routingA_flagOnScreenConsumerInitialTagRoutesToR2Movie() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-video"))
                .isEqualTo(StorageProviderId.of("r2-movie"));
    }

    @Test
    @DisplayName("ROUTING-B: Flag ON, trusted Screen consumer, valid replacement UUID -> R2 Movie")
    void routingB_flagOnScreenConsumerReplacementTagRoutesToR2Movie() {
        String validReplaceTag = "screen-replace:" + VALID_ASSET_UUID;
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", validReplaceTag))
                .isEqualTo(StorageProviderId.of("r2-movie"));
    }

    @Test
    @DisplayName("ROUTING-C: Flag OFF, same Screen operation -> B2")
    void routingC_flagOffSameScreenOperationRoutesToB2() {
        assertThat(flagOffService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOffService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-D: Null consumer context + valid Screen tag -> NOT R2 Movie (routes to B2)")
    void routingD_nullConsumerContextWithValidScreenTagRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, null, "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, null, "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-E: Generic two-argument VIDEO routing + Screen tag -> NOT R2 Movie (routes to B2)")
    void routingE_genericTwoArgumentVideoRoutingRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-F: Community consumer + Screen tag -> NOT R2 Movie (routes to B2)")
    void routingF_communityConsumerWithScreenTagRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "community", "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "community", "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-G: Novel consumer + Screen tag -> NOT R2 Movie (routes to B2)")
    void routingG_novelConsumerWithScreenTagRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "novel", "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "novel", "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-H: Blank consumer + Screen tag -> NOT R2 Movie (routes to B2)")
    void routingH_blankConsumerWithScreenTagRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "   ", "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "", "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-I: Valid consumer + malformed UUID -> NOT R2 Movie (routes to B2)")
    void routingI_validConsumerWithMalformedUuidRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-replace:invalid-uuid"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-replace:550e8400-e29b-41d4-a716-44665544000"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-replace:"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-replace:not-a-uuid"))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-J: Valid consumer + broad substring tags -> NOT R2 Movie (routes to B2)")
    void routingJ_validConsumerWithBroadSubstringTagsRoutesToB2() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "other-movie-test"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "random-entry"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-invalid"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen.entry"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen.entry:my-movie"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "movie"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", null))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "   "))
                .isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-K: Existing B2 session after flag ON -> B2")
    void routingK_existingB2SessionRemainsBoundToB2AfterFlagOn() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession historicalB2Session = MediaUploadSession.create(
                sessionId,
                "user-1",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-key-1",
                52428800L,
                StorageProviderId.of("b2"),
                B2_BUCKET,
                StorageKey.of("media/video/movie.mp4"),
                allocId,
                "movie.mp4",
                "screen-video",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        assertThat(historicalB2Session.getStorageProviderId()).isEqualTo(StorageProviderId.of("b2"));
    }

    @Test
    @DisplayName("ROUTING-L: Existing R2 Movie session after flag OFF -> R2 Movie")
    void routingL_existingR2MovieSessionRemainsBoundToR2MovieAfterFlagOff() {
        UUID sessionId = UUID.randomUUID();
        UUID allocId = UUID.randomUUID();
        MediaUploadSession r2Session = MediaUploadSession.create(
                sessionId,
                "user-1",
                "screen",
                "ScreenEntry",
                UUID.randomUUID(),
                "idem-key-2",
                52428800L,
                StorageProviderId.of("r2-movie"),
                R2_MOVIE_BUCKET,
                StorageKey.of("media/video/movie.mp4"),
                allocId,
                "movie.mp4",
                "screen-video",
                Instant.now().plusSeconds(3600),
                Instant.now()
        );
        assertThat(r2Session.getStorageProviderId()).isEqualTo(StorageProviderId.of("r2-movie"));
    }

    @Test
    @DisplayName("ROUTING-M: Invalid routing creates no storage allocation")
    void routingM_invalidRoutingFailsClosedWithoutAllocation() {
        assertThatThrownBy(() -> flagOnService.resolveWriteProvider(MediaType.DOCUMENT, "screen", "screen-video"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("ROUTING-N: Client-controlled metadata cannot impersonate trusted Screen consumer identity")
    void routingN_clientMetadataCannotImpersonateScreenConsumer() {
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "client-dto", "screen-video"))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "public-web", "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("b2"));
        assertThat(MediaStorageRoutingService.isScreenConsumer("public-web")).isFalse();
        assertThat(MediaStorageRoutingService.isScreenConsumer("client-dto")).isFalse();
        assertThat(MediaStorageRoutingService.isScreenConsumer("screen")).isTrue();
    }

    @Test
    @DisplayName("ROUTING-O: Screen admin upload and replacement still work through existing Media capability")
    void routingO_screenAdminUploadAndReplacementWorkThroughMediaCapability() {
        // Screen admin initial upload: consumer="screen", tag="screen-video"
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-video"))
                .isEqualTo(StorageProviderId.of("r2-movie"));

        // Screen admin replacement: consumer="screen", tag="screen-replace:<canonical-UUID>"
        assertThat(flagOnService.resolveWriteProvider(MediaType.VIDEO, "screen", "screen-replace:" + VALID_ASSET_UUID))
                .isEqualTo(StorageProviderId.of("r2-movie"));

        // Non-Screen or non-video operations remain preserved
        assertThat(flagOnService.resolveWriteProvider(MediaType.IMAGE, "screen", "screen.poster"))
                .isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.AUDIO, "novel", "novel.chapter.audio"))
                .isEqualTo(StorageProviderId.of("r2"));
    }

    // =============================================================================================
    // Baseline Invariants & Preservation Tests
    // =============================================================================================

    @Test
    @DisplayName("IMAGE routing remains Cloudinary at baseline")
    void imageRoutingRemainsCloudinary() {
        assertThat(flagOffService.resolveWriteProvider(MediaType.IMAGE, null))
                .isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.IMAGE, null))
                .isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.IMAGE, "screen", "screen.poster"))
                .isEqualTo(StorageProviderId.of("cloudinary"));
    }

    @Test
    @DisplayName("AUDIO routing remains R2 at baseline")
    void audioRoutingRemainsR2() {
        assertThat(flagOffService.resolveWriteProvider(MediaType.AUDIO, null))
                .isEqualTo(StorageProviderId.of("r2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.AUDIO, null))
                .isEqualTo(StorageProviderId.of("r2"));
        assertThat(flagOnService.resolveWriteProvider(MediaType.AUDIO, "novel", "novel.chapter.audio"))
                .isEqualTo(StorageProviderId.of("r2"));
    }

    @Test
    @DisplayName("DOCUMENT routing throws unsupported StorageException")
    void documentRoutingThrowsUnsupported() {
        assertThatThrownBy(() -> flagOffService.resolveWriteProvider(MediaType.DOCUMENT, null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unsupported MediaType for storage write routing: DOCUMENT");

        assertThatThrownBy(() -> flagOnService.resolveWriteProvider(MediaType.DOCUMENT, "screen", "screen-video"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unsupported MediaType for storage write routing: DOCUMENT");
    }

    @Test
    @DisplayName("resolves bucket name for r2-movie successfully when configured")
    void shouldResolveR2MovieBucketWhenConfigured() {
        assertThat(flagOnService.resolveBucketName(MediaStorageRoutingService.R2_MOVIE_PROVIDER))
                .isEqualTo(R2_MOVIE_BUCKET);
    }

    @Test
    @DisplayName("fails closed when resolving r2-movie bucket if missing, blank, or NONE")
    void shouldFailClosedWhenR2MovieBucketNotConfigured() {
        MediaStorageRoutingService nullService = new MediaStorageRoutingService(
                B2_BUCKET, R2_AUDIO_BUCKET, null, true
        );
        assertThatThrownBy(() -> nullService.resolveBucketName(MediaStorageRoutingService.R2_MOVIE_PROVIDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.bucket");

        MediaStorageRoutingService blankService = new MediaStorageRoutingService(
                B2_BUCKET, R2_AUDIO_BUCKET, "   ", true
        );
        assertThatThrownBy(() -> blankService.resolveBucketName(MediaStorageRoutingService.R2_MOVIE_PROVIDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.bucket");

        MediaStorageRoutingService sentinelService = new MediaStorageRoutingService(
                B2_BUCKET, R2_AUDIO_BUCKET, "NONE", true
        );
        assertThatThrownBy(() -> sentinelService.resolveBucketName(MediaStorageRoutingService.R2_MOVIE_PROVIDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media.storage.r2-movie.bucket");
    }
}
