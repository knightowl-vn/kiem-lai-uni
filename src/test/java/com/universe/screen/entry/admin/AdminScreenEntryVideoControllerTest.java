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
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
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
