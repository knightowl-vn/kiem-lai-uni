package com.universe.screen.entry.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MultipartManifestPartDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.InitiateVideoUploadSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminScreenEntryVideoControllerTest {

    @Mock
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @Mock
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    @Mock
    private AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase;

    @Mock
    private DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;

    @Mock
    private ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase;

    @Mock
    private MediaContract mediaContract;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        AdminScreenEntryVideoController controller = new AdminScreenEntryVideoController(
                getScreenTitleDetailUseCase,
                getScreenEntryDetailUseCase,
                attachScreenEntryVideoUseCase,
                detachScreenEntryVideoUseCase,
                replaceScreenEntryVideoUseCase,
                mediaContract
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("POST /upload-session: initiates upload session successfully for MP4")
    void shouldInitiateUploadSession() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO allocated = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );

        when(mediaContract.allocateUploadSession(any(AllocateUploadSessionRequestDTO.class))).thenReturn(allocated);
        when(mediaContract.initiateUploadSession(eq(sessionId), eq("admin"))).thenReturn(initiated);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                52428800L, "video.mp4", null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.expectedPartCount").value(2));

        verify(mediaContract).allocateUploadSession(any());
        verify(mediaContract).initiateUploadSession(sessionId, "admin");
    }

    @Test
    @DisplayName("POST /upload-session: reuses existing INITIATED session when retried with identical idempotencyKey (lost response recovery)")
    void shouldReuseSessionOnInitiationRetryWithIdenticalIdempotencyKey() throws Exception {
        stubTitleAndEntry(null);

        String stableKey = "screen-entry-" + entryId + "-stable-key";
        MediaUploadSessionResponseDTO existingInitiated = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );

        when(mediaContract.allocateUploadSession(any(AllocateUploadSessionRequestDTO.class))).thenReturn(existingInitiated);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                52428800L, "video.mp4", stableKey
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("INITIATED"));

        // When session is already INITIATED, initiateUploadSession is not called again
        org.mockito.Mockito.verify(mediaContract, org.mockito.Mockito.never()).initiateUploadSession(any(), any());
    }

    @Test
    @DisplayName("POST /upload-session: rejects non-MP4 file extension with 400")
    void shouldRejectNonMp4() throws Exception {
        stubTitleAndEntry(null);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                10485760L, "movie.mkv", null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /upload-session: rejects when video is already attached")
    void shouldRejectWhenVideoAlreadyAttached() throws Exception {
        stubTitleAndEntry(UUID.randomUUID());

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                10485760L, "video.mp4", null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED"));
    }

    @Test
    @DisplayName("GET /upload-session/{sessionId}/parts/{partNumber}/url: returns presigned PUT URL")
    void shouldReturnPresignedPartUrl() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO session = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(session));

        String presignedUrl = "https://s3.us-east-005.backblazeb2.com/test-bucket/video.mp4?partNumber=1&uploadId=xyz";
        when(mediaContract.generatePresignedPartUploadUrl(sessionId, 1, "admin")).thenReturn(presignedUrl);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/parts/{partNumber}/url",
                        titleId, entryId, sessionId, 1)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumber").value(1))
                .andExpect(jsonPath("$.uploadUrl").value(presignedUrl));
    }

    @Test
    @DisplayName("GET /upload-session/{sessionId}/parts/{partNumber}/url: rejects when session belongs to a different entry")
    void shouldRejectPartUrlWhenSessionBelongsToDifferentEntry() throws Exception {
        stubTitleAndEntry(null);
        UUID differentEntryId = UUID.randomUUID();
        MediaUploadSessionResponseDTO crossEntrySession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", differentEntryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(crossEntrySession));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/parts/{partNumber}/url",
                        titleId, entryId, sessionId, 1)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("GET /upload-session/{sessionId}/parts/{partNumber}/url: rejects when session is not INITIATED")
    void shouldRejectPartUrlWhenSessionNotInitiated() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/parts/{partNumber}/url",
                        titleId, entryId, sessionId, 1)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/complete: completes multipart and attaches to entry")
    void shouldCompleteAndAttachVideo() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO completingSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETING", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );

        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));
        when(mediaContract.claimCompletionIntent(any(ClaimCompletionIntentRequestDTO.class))).thenReturn(completingSession);
        when(mediaContract.completeMultipartUpload(any(CompleteMultipartUploadRequestDTO.class))).thenReturn(completedSession);

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\""))
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.playbackUrl").value("/media/assets/" + assetId + "/content"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/complete: idempotent success when already completed and attached")
    void shouldIdempotentlyReturnSuccessWhenAlreadyCompletedAndAttached() throws Exception {
        stubTitleAndEntry(assetId); // Entry already has assetId attached

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\""))
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.message").value("Video đã được hoàn tất và gắn vào Screen Entry từ trước (idempotent)."));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/complete: rejects when entry already has a DIFFERENT video attached")
    void shouldRejectCompleteWhenEntryHasDifferentVideo() throws Exception {
        UUID existingAssetId = UUID.randomUUID();
        stubTitleAndEntry(existingAssetId);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\""))
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED"));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/complete: retries attachment without re-completing when provider already COMPLETED")
    void shouldAttachDirectlyWhenSessionAlreadyCompletedOnProvider() throws Exception {
        stubTitleAndEntry(null); // Entry not attached yet

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\""))
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/abort: aborts upload session")
    void shouldAbortUploadSession() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO aborted = new MediaUploadSessionResponseDTO(
                sessionId, "ABORTED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiated));
        when(mediaContract.abortUploadSession(sessionId, "admin")).thenReturn(aborted);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/abort",
                        titleId, entryId, sessionId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("ABORTED"));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/abort: rejects abort when session is already COMPLETED")
    void shouldRejectAbortWhenSessionAlreadyCompleted() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/abort",
                        titleId, entryId, sessionId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/abort: returns status safely when session is already ABORTED")
    void shouldReturnStatusWhenSessionAlreadyAborted() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO aborted = new MediaUploadSessionResponseDTO(
                sessionId, "ABORTED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(aborted));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/abort",
                        titleId, entryId, sessionId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("ABORTED"));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/abort: rejects abort when session belongs to a different entry")
    void shouldRejectAbortWhenSessionBelongsToDifferentEntry() throws Exception {
        stubTitleAndEntry(null);
        UUID differentEntryId = UUID.randomUUID();
        MediaUploadSessionResponseDTO crossEntrySession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", differentEntryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(crossEntrySession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/abort",
                        titleId, entryId, sessionId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: attaches existing registered video asset when session is COMPLETED and provenance matches")
    void shouldAttachExistingVideoWhenSessionIsCompletedAndAssetMatches() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.playbackUrl").value("/media/assets/" + assetId + "/content"))
                .andExpect(jsonPath("$.status").value("ATTACHED"));

        verify(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);
    }

    @Test
    @DisplayName("POST /attach: returns idempotent success when identical video is already attached")
    void shouldReturnIdempotentSuccessWhenSameVideoAlreadyAttached() throws Exception {
        stubTitleAndEntry(assetId);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("ATTACHED"));

        org.mockito.Mockito.verify(attachScreenEntryVideoUseCase, org.mockito.Mockito.never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("POST /attach: rejects when sessionId is missing")
    void shouldRejectAttachWhenMissingSessionId() throws Exception {
        stubTitleAndEntry(null);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("videoMediaAssetId", assetId.toString())))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: rejects when videoMediaAssetId is missing")
    void shouldRejectAttachWhenMissingVideoMediaAssetId() throws Exception {
        stubTitleAndEntry(null);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("sessionId", sessionId.toString())))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: rejects when session is not found")
    void shouldRejectAttachWhenSessionNotFound() throws Exception {
        stubTitleAndEntry(null);
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: rejects when session status is not COMPLETED")
    void shouldRejectAttachWhenSessionNotInCompletedStatus() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiated));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: rejects when registeredAssetId does not match videoMediaAssetId (unrelated asset)")
    void shouldRejectAttachWhenRegisteredAssetIdDoesNotMatchVideoMediaAssetId() throws Exception {
        stubTitleAndEntry(null);
        UUID differentRegisteredAssetId = UUID.randomUUID();

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", differentRegisteredAssetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: rejects when session belongs to a different entry (cross-entry reuse)")
    void shouldRejectAttachWhenSessionBelongsToDifferentEntry() throws Exception {
        stubTitleAndEntry(null);
        UUID differentEntryId = UUID.randomUUID();

        MediaUploadSessionResponseDTO crossEntrySession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", differentEntryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(crossEntrySession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /attach: rejects when caller is not the session initiator (unauthorized actor)")
    void shouldRejectAttachWhenCallerIsNotSessionInitiator() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO foreignSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "other-user", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(foreignSession));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "sessionId", sessionId.toString(),
                                        "videoMediaAssetId", assetId.toString()
                                )))
                                .principal(new TestingAuthenticationToken("admin", "pass")))
        ).hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("POST /attach: rejects when entry already has a different video attached")
    void shouldRejectAttachWhenEntryAlreadyHasDifferentVideoAttached() throws Exception {
        UUID existingDifferentVideoId = UUID.randomUUID();
        stubTitleAndEntry(existingDifferentVideoId);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED"));
    }

    @Test
    @DisplayName("POST /upload-session: allows initiation for replacement when isReplacement is true and expected ID matches")
    void shouldInitiateUploadSessionForReplacement() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        MediaUploadSessionResponseDTO allocated = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "new-video.mp4", "screen-replace:" + oldVideoId, null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "new-video.mp4", "screen-replace:" + oldVideoId, null, now, now.plusSeconds(86400)
        );

        when(mediaContract.allocateUploadSession(any(AllocateUploadSessionRequestDTO.class))).thenReturn(allocated);
        when(mediaContract.initiateUploadSession(eq(sessionId), eq("admin"))).thenReturn(initiated);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                52428800L, "new-video.mp4", null, true, oldVideoId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("INITIATED"));

        verify(mediaContract).allocateUploadSession(any());
        verify(mediaContract).initiateUploadSession(sessionId, "admin");
    }

    @Test
    @DisplayName("POST /upload-session: rejects replacement when expectedCurrentVideoMediaAssetId mismatches current video")
    void shouldRejectReplacementInitiateWhenExpectedIdMismatches() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        UUID wrongExpectedId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                52428800L, "new-video.mp4", null, true, wrongExpectedId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_CONFLICT"));
    }

    @Test
    @DisplayName("POST /complete: calls replaceScreenEntryVideoUseCase and returns REPLACED when entry already has video and session is replacement-bound")
    void shouldCompleteUploadSessionWithReplacement() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        String replaceClientTag = "screen-replace:" + oldVideoId;
        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", replaceClientTag, null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO completingSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETING", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", replaceClientTag, null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", replaceClientTag, assetId, now, now.plusSeconds(86400)
        );

        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));
        when(mediaContract.claimCompletionIntent(any(ClaimCompletionIntentRequestDTO.class))).thenReturn(completingSession);
        when(mediaContract.completeMultipartUpload(any(CompleteMultipartUploadRequestDTO.class))).thenReturn(completedSession);

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                oldVideoId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLACED"))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()));

        verify(replaceScreenEntryVideoUseCase).execute(titleId, entryId, assetId, oldVideoId);
    }

    @Test
    @DisplayName("POST /complete: rejects replacement when session was NOT allocated as replacement (E2L1-03)")
    void shouldRejectReplacementCompletionWhenSessionWasNotInitiatedAsReplacement() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        // Session was allocated as regular upload ("screen-video")
        MediaUploadSessionResponseDTO session = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(session));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                oldVideoId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED"));
    }

    @Test
    @DisplayName("POST /complete: rejects replacement when expected ID in complete request mismatches session clientTag (E2L1-03)")
    void shouldRejectReplacementCompletionWhenExpectedIdMismatchesSessionClientTag() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        UUID differentExpectedId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        // Session was allocated with oldVideoId
        MediaUploadSessionResponseDTO session = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-replace:" + oldVideoId, assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(session));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                differentExpectedId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_CONFLICT"));
    }

    @Test
    @DisplayName("POST /upload-session: rejects replacement when expectedCurrentVideoMediaAssetId is null (E2L1-02)")
    void shouldRejectInitiationWhenReplacementHasNullExpectedId() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                52428800L, "new-video.mp4", null, true, null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("expectedCurrentVideoMediaAssetId không được để trống khi thực hiện thay thế video."));
    }

    @Test
    @DisplayName("POST /detach: successfully detaches video from entry and returns DETACHED")
    void shouldDetachVideoSuccessfully() throws Exception {
        UUID currentVideoId = UUID.randomUUID();
        stubTitleAndEntry(currentVideoId);

        ScreenEntryDTO detachedEntry = new ScreenEntryDTO(
                entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                null, null, 1200L, ScreenPublicationStatus.DRAFT, null, now, now.plusSeconds(10)
        );
        when(detachScreenEntryVideoUseCase.execute(titleId, entryId, currentVideoId)).thenReturn(detachedEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVideoMediaAssetId", currentVideoId.toString())))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DETACHED"))
                .andExpect(jsonPath("$.entryId").value(entryId.toString()));

        verify(detachScreenEntryVideoUseCase).execute(titleId, entryId, currentVideoId);
    }

    @Test
    @DisplayName("POST /detach: rejects when expectedVideoMediaAssetId is missing or empty (E2L1-02)")
    void shouldRejectDetachWhenExpectedVideoMediaAssetIdMissing() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("expectedVideoMediaAssetId không được để trống khi thực hiện gỡ bỏ video."));
    }

    @Test
    @DisplayName("POST /complete: rejects replacement when expected ID mismatches BEFORE claimCompletionIntent")
    void shouldRejectCompletionWhenExpectedIdMismatchesBeforeClaimingCompletionIntent() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        UUID wrongExpectedId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        String replaceClientTag = "screen-replace:" + oldVideoId;
        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", replaceClientTag, null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                wrongExpectedId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_CONFLICT"));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
        verify(replaceScreenEntryVideoUseCase, never()).execute(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /complete: rejects completion when session has malformed clientTag BEFORE provider completion")
    void shouldRejectCompletionWhenSessionHasMalformedClientTagBeforeProviderCompletion() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        String malformedClientTag = "screen-replace:not-a-valid-uuid";
        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", malformedClientTag, null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                oldVideoId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Upload session có clientTag thay thế video không đúng định dạng UUID: " + malformedClientTag));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
    }

    @Test
    @DisplayName("POST /complete: rejects completion when session has unknown clientTag BEFORE provider completion")
    void shouldRejectCompletionWhenSessionHasUnknownClientTagBeforeProviderCompletion() throws Exception {
        stubTitleAndEntry(null);

        String unknownClientTag = "unsupported-custom-tag";
        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", unknownClientTag, null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Upload session có clientTag không được hỗ trợ: " + unknownClientTag));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
    }

    @Test
    @DisplayName("POST /complete: rejects converting initial session to replacement when entry already has video BEFORE provider completion")
    void shouldRejectConvertingInitialSessionToReplacementWhenEntryAlreadyHasVideo() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                oldVideoId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED"));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
    }

    @Test
    @DisplayName("POST /complete: rejects converting initial session to replacement when expectedId is supplied on empty entry BEFORE provider completion")
    void shouldRejectConvertingInitialSessionToReplacementWhenExpectedIdSuppliedOnEmptyEntry() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO initiatedSession = new MediaUploadSessionResponseDTO(
                sessionId, "INITIATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(initiatedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                UUID.randomUUID()
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Upload session ban đầu không thể dùng để thay thế video."));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
    }

    @Test
    @DisplayName("POST /upload-session: rejects allocation when idempotency key reuse returns mismatched clientTag")
    void shouldRejectInitiationWhenIdempotencyKeyReuseReturnsMismatchedClientTag() throws Exception {
        UUID oldVideoId = UUID.randomUUID();
        stubTitleAndEntry(oldVideoId);

        // Client requested replacement (expects "screen-replace:" + oldVideoId), but existing session has "screen-video"
        MediaUploadSessionResponseDTO mismatchedSession = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.allocateUploadSession(any())).thenReturn(mismatchedSession);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(
                52428800L, "video.mp4", "reused-idempotency-key", true, oldVideoId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Upload session đã tồn tại với ý định thao tác không khớp: screen-video"));

        verify(mediaContract, never()).initiateUploadSession(any(), any());
    }

    @Test
    @DisplayName("POST /complete: idempotently recovers when completed session registered asset is already attached")
    void shouldIdempotentlyRecoverCompletedSessionWhenAlreadyAttached() throws Exception {
        stubTitleAndEntry(assetId);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.message").value("Video đã được hoàn tất và gắn vào Screen Entry từ trước (idempotent)."));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
        verify(attachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("POST /complete: recovers when provider completion succeeded previously but screen attachment failed")
    void shouldRecoverWhenProviderCompletedButScreenPersistenceFailedPreviously() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete", titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()));

        verify(mediaContract, never()).claimCompletionIntent(any());
        verify(mediaContract, never()).completeMultipartUpload(any());
        verify(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);
    }

    @Test
    @DisplayName("Adversarial: does not disclose sensitive information from unexpected IllegalStateException")
    void shouldNotDiscloseSensitiveMessageOnUnexpectedIllegalStateException() throws Exception {
        when(getScreenTitleDetailUseCase.execute(titleId))
                .thenThrow(new IllegalStateException("CRITICAL_LEAK: jdbc:mysql://root:superSecretPassword@10.0.0.1:3306/db"));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/parts/{partNumber}/url",
                        titleId, entryId, sessionId, 1)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Yêu cầu không hợp lệ hoặc đã xảy ra lỗi trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("superSecretPassword"))));
    }

    @Test
    @DisplayName("Adversarial: does not disclose sensitive information from unexpected infrastructure RuntimeException")
    void shouldNotDiscloseSensitiveMessageOnUnexpectedInfrastructureRuntimeException() throws Exception {
        stubTitleAndEntry(null);
        when(mediaContract.getUploadSession(sessionId))
                .thenThrow(new RuntimeException("B2_CREDENTIAL_FAILURE: secretAccessKey=AKIAIOSFODNN7EXAMPLE"));

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "content-hash-hex",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                null
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Yêu cầu không hợp lệ hoặc đã xảy ra lỗi trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("AKIAIOSFODNN7EXAMPLE"))));
    }

    private void stubTitleAndEntry(UUID currentVideoId) {
        ScreenTitleDTO title = new ScreenTitleDTO(
                titleId, "title-slug", "Title", null, null, ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION, null, null, ScreenPublicationStatus.PUBLISHED,
                null, now, now
        );
        ScreenEntryDTO entry = new ScreenEntryDTO(
                entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                currentVideoId, null, 1200L, ScreenPublicationStatus.DRAFT, null, now, now
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(title);
        when(getScreenEntryDetailUseCase.execute(titleId, entryId)).thenReturn(entry);
    }
}
