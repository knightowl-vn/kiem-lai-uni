package com.universe.media.application.storage;

import com.universe.media.domain.MediaUploadSession;
import com.universe.media.domain.MediaUploadSessionStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import com.universe.media.domain.UploadSessionCleanupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenVideoVisibilityProvenanceTest {

    private static final StorageProviderId B2_PROVIDER = StorageProviderId.of("b2");
    private static final String B2_BUCKET = "kiemlai-video";
    private static final StorageKey STORAGE_KEY = StorageKey.of("media/video/session-test.mp4");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private MediaUploadSession createSession(
            String consumerContext,
            String targetEntityType,
            UUID targetEntityId,
            String clientTag,
            MediaType mediaType
    ) {
        return MediaUploadSession.rehydrate(
                UUID.randomUUID(),
                "admin-123",
                consumerContext,
                targetEntityType,
                targetEntityId,
                "idemp-" + UUID.randomUUID(),
                mediaType,
                "video/mp4",
                26214400L,
                B2_PROVIDER,
                B2_BUCKET,
                STORAGE_KEY,
                UUID.randomUUID(),
                "provider-upload-123",
                26214400L,
                1,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "video.mp4",
                clientTag,
                MediaUploadSessionStatus.COMPLETING,
                NOW.plusSeconds(86400),
                null,
                null,
                null,
                UploadSessionCleanupStatus.NONE,
                0,
                null,
                null,
                NOW,
                NOW,
                null,
                1L,
                null
        );
    }

    @Test
    @DisplayName("Screen video upload session completes into MediaVisibility.PRIVATE for initial attachment")
    void screenVideoInitialUpload_resolvesToPrivate() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-video",
                MediaType.VIDEO
        );

        MediaVisibility visibility = MediaUploadSessionService.resolveInitialVisibility(session);
        assertThat(visibility).isEqualTo(MediaVisibility.PRIVATE);
    }

    @Test
    @DisplayName("Screen video upload session completes into MediaVisibility.PRIVATE for replacement")
    void screenVideoReplacementUpload_resolvesToPrivate() {
        UUID oldAssetId = UUID.randomUUID();
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-replace:" + oldAssetId,
                MediaType.VIDEO
        );

        MediaVisibility visibility = MediaUploadSessionService.resolveInitialVisibility(session);
        assertThat(visibility).isEqualTo(MediaVisibility.PRIVATE);
    }

    @Test
    @DisplayName("Pre-existing allocated Screen upload session completes into MediaVisibility.PRIVATE")
    void preExistingAllocatedScreenSession_resolvesToPrivateAtTxB() {
        // Simulates a session allocated prior to E2-L2 deployment:
        // Already persisted in media_upload_sessions with consumerContext='screen' and targetEntityType='SCREEN_ENTRY'
        MediaUploadSession preExistingSession = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-video",
                MediaType.VIDEO
        );

        MediaVisibility visibility = MediaUploadSessionService.resolveInitialVisibility(preExistingSession);
        assertThat(visibility).isEqualTo(MediaVisibility.PRIVATE);
    }

    @Test
    @DisplayName("Other consumer contexts (e.g. novel, wiki, identity) retain MediaVisibility.PUBLIC")
    void otherConsumers_retainPublicVisibility() {
        MediaUploadSession novelSession = createSession(
                "novel",
                "NOVEL_CHAPTER_NARRATION",
                UUID.randomUUID(),
                "narration-audio",
                MediaType.VIDEO // upload sessions are currently VIDEO
        );

        MediaVisibility novelVisibility = MediaUploadSessionService.resolveInitialVisibility(novelSession);
        assertThat(novelVisibility).isEqualTo(MediaVisibility.PUBLIC);

        MediaUploadSession wikiSession = createSession(
                "wiki",
                "WIKI_ENTRY_VIDEO",
                UUID.randomUUID(),
                "wiki-media",
                MediaType.VIDEO
        );

        MediaVisibility wikiVisibility = MediaUploadSessionService.resolveInitialVisibility(wikiSession);
        assertThat(wikiVisibility).isEqualTo(MediaVisibility.PUBLIC);
    }

    @Test
    @DisplayName("Screen context with invalid targetEntityType is rejected safely")
    void screenContext_invalidTargetEntityType_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_TITLE", // Screen video uploads are only allowed for SCREEN_ENTRY
                UUID.randomUUID(),
                "screen-video",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or ambiguous Screen target entity type");
    }

    @Test
    @DisplayName("Screen context with null targetEntityId is rejected safely")
    void screenContext_nullTargetEntityId_rejected() {
        // Using constructor reflection or domain check
        assertThatThrownBy(() -> createSession(
                "screen",
                "SCREEN_ENTRY",
                null,
                "screen-video",
                MediaType.VIDEO
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Screen context with unsupported clientTag is rejected safely")
    void screenContext_unsupportedClientTag_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "unsupported-client-tag",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Screen context with empty replacement suffix is rejected safely")
    void screenContext_emptyReplacementSuffix_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-replace:",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Screen context with whitespace replacement suffix is rejected safely")
    void screenContext_whitespaceReplacementSuffix_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-replace:   ",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Screen context with malformed replacement UUID is rejected safely")
    void screenContext_malformedReplacementUuid_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-replace:not-a-valid-uuid-string",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Screen context with non-canonical truncated UUID is rejected safely")
    void screenContext_truncatedReplacementUuid_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "screen-replace:0-0-0-0-0",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Screen context with null clientTag is rejected safely")
    void screenContext_nullClientTag_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                null,
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Screen context with blank clientTag is rejected safely")
    void screenContext_blankClientTag_rejected() {
        MediaUploadSession session = createSession(
                "screen",
                "SCREEN_ENTRY",
                UUID.randomUUID(),
                "   ",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported or malformed Screen client tag");
    }

    @Test
    @DisplayName("Non-screen consumer context attempting to claim 'screen-video' clientTag is rejected safely")
    void nonScreenConsumer_claimingScreenVideoClientTag_rejected() {
        MediaUploadSession session = createSession(
                "novel",
                "CHAPTER",
                UUID.randomUUID(),
                "screen-video",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous Screen client tag 'screen-video' detected for non-screen consumer context 'novel'");
    }

    @Test
    @DisplayName("Non-screen consumer context attempting to claim 'screen-replace' clientTag is rejected safely")
    void nonScreenConsumer_claimingScreenReplaceClientTag_rejected() {
        UUID randomAssetId = UUID.randomUUID();
        MediaUploadSession session = createSession(
                "wiki",
                "WIKI_ENTRY",
                UUID.randomUUID(),
                "screen-replace:" + randomAssetId,
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous Screen client tag 'screen-replace:" + randomAssetId + "' detected for non-screen consumer context 'wiki'");
    }

    @Test
    @DisplayName("Non-screen consumer context attempting to claim arbitrary 'screen-' prefix is rejected safely")
    void nonScreenConsumer_claimingScreenPrefixTag_rejected() {
        MediaUploadSession session = createSession(
                "identity",
                "AVATAR",
                UUID.randomUUID(),
                "screen-custom-operation",
                MediaType.VIDEO
        );

        assertThatThrownBy(() -> MediaUploadSessionService.resolveInitialVisibility(session))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous Screen client tag 'screen-custom-operation' detected for non-screen consumer context 'identity'");
    }
}
