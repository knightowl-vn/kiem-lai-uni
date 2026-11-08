package com.universe.screen.entry.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.media.application.exceptions.StorageAmbiguousInitiationException;
import com.universe.media.application.exceptions.StorageInitiationRejectedException;
import com.universe.media.application.exceptions.UploadSessionInitiationConflictException;
import com.universe.media.application.exceptions.UploadSessionUnconfirmedInitiationException;
import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MultipartManifestPartDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult.DiscardOutcome;
import com.universe.screen.application.dto.ReconcileScreenVideoVisibilityResult;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenEntryVideoNotAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.shared.exceptions.BaseApplicationException;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.ReconcileScreenEntryVideoVisibilityUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.entry.admin.dto.CompleteVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadSessionRequest;
import com.universe.screen.entry.admin.dto.InitiateVideoUploadSessionRequest;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.universe.screen.entry.admin.dto.ScreenVideoErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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

    @Mock
    private DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase;

    @Mock
    private ReconcileScreenEntryVideoVisibilityUseCase reconcileScreenEntryVideoVisibilityUseCase;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AdminScreenEntryVideoController controller;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new AdminScreenEntryVideoController(
                getScreenTitleDetailUseCase,
                getScreenEntryDetailUseCase,
                attachScreenEntryVideoUseCase,
                detachScreenEntryVideoUseCase,
                replaceScreenEntryVideoUseCase,
                mediaContract,
                discardScreenEntryVideoUploadUseCase,
                reconcileScreenEntryVideoVisibilityUseCase
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
    @DisplayName("POST /upload-session: maps StorageInitiationRejectedException to HTTP 502 MEDIA_STORAGE_INITIATION_REJECTED")
    void shouldMapStorageInitiationRejectedExceptionTo502() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO allocated = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.allocateUploadSession(any())).thenReturn(allocated);
        when(mediaContract.initiateUploadSession(eq(sessionId), eq("admin")))
                .thenThrow(new StorageInitiationRejectedException("Provider definitively rejected multipart initiation [errorCode=MalformedAccessKeyId]"));

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(52428800L, "video.mp4", "idem-key");
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("MEDIA_STORAGE_INITIATION_REJECTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nhà cung cấp lưu trữ từ chối yêu cầu khởi tạo")));
    }

    @Test
    @DisplayName("POST /upload-session: maps StorageAmbiguousInitiationException to HTTP 504 MEDIA_STORAGE_INITIATION_AMBIGUOUS")
    void shouldMapStorageAmbiguousInitiationExceptionTo504() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO allocated = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.allocateUploadSession(any())).thenReturn(allocated);
        when(mediaContract.initiateUploadSession(eq(sessionId), eq("admin")))
                .thenThrow(new StorageAmbiguousInitiationException("Provider multipart initiation timed out"));

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(52428800L, "video.mp4", "idem-key");
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("MEDIA_STORAGE_INITIATION_AMBIGUOUS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("chưa được xác nhận hoặc bị gián đoạn")));
    }

    @Test
    @DisplayName("POST /upload-session: maps UploadSessionInitiationConflictException to HTTP 409 MEDIA_UPLOAD_INITIATION_IN_PROGRESS")
    void shouldMapUploadSessionInitiationConflictExceptionTo409() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO allocated = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.allocateUploadSession(any())).thenReturn(allocated);
        when(mediaContract.initiateUploadSession(eq(sessionId), eq("admin")))
                .thenThrow(new UploadSessionInitiationConflictException(sessionId, "INITIATION_CLAIM:worker-1", "Concurrent initiation"));

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(52428800L, "video.mp4", "idem-key");
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MEDIA_UPLOAD_INITIATION_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("đang được khởi tạo bởi một tiến trình khác")));
    }

    @Test
    @DisplayName("POST /upload-session: maps UploadSessionUnconfirmedInitiationException to HTTP 409 MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM")
    void shouldMapUploadSessionUnconfirmedInitiationExceptionTo409() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO allocated = new MediaUploadSessionResponseDTO(
                sessionId, "ALLOCATED", "admin", "screen", "SCREEN_ENTRY", entryId,
                52428800L, 26214400L, 2, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.allocateUploadSession(any())).thenReturn(allocated);
        when(mediaContract.initiateUploadSession(eq(sessionId), eq("admin")))
                .thenThrow(new UploadSessionUnconfirmedInitiationException(sessionId, "INITIATION_CLAIM:worker-1", "Unconfirmed claim"));

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(52428800L, "video.mp4", "idem-key");
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("MEDIA_UPLOAD_INITIATION_UNCONFIRMED_CLAIM"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("chưa xác nhận kết quả. Cần thực hiện đối soát")));
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
                .andExpect(jsonPath("$.playbackUrl").value("/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content"))
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
    @DisplayName("POST /upload-session/{sessionId}/complete: retains MediaAsset and session when post-completion attachment fails with conflict")
    void shouldRetainMediaAssetAndSessionWhenPostCompletionAttachmentFailsWithConflict() throws Exception {
        stubTitleAndEntry(null);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));
        doThrow(new ScreenEntryVideoAlreadyAttachedException(entryId, UUID.randomUUID()))
                .when(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);

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
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_ALREADY_ATTACHED"))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()));

        verify(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);
        verify(mediaContract, never()).delete(any());
        verify(mediaContract, never()).abortUploadSession(any(), any());
        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/complete: retains new upload and preserves old video when replacement fails with conflict")
    void shouldRetainNewUploadAndPreserveOldVideoWhenReplacementFailsWithConflict() throws Exception {
        UUID oldAssetId = UUID.randomUUID();
        stubTitleAndEntry(oldAssetId);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-replace:" + oldAssetId, assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));
        doThrow(new ScreenEntryVideoConflictException(entryId, oldAssetId, UUID.randomUUID()))
                .when(replaceScreenEntryVideoUseCase).execute(titleId, entryId, assetId, oldAssetId);

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\"")),
                oldAssetId
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_CONFLICT"))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()));

        verify(replaceScreenEntryVideoUseCase).execute(titleId, entryId, assetId, oldAssetId);
        verify(mediaContract, never()).delete(any());
        verify(mediaContract, never()).abortUploadSession(any(), any());
        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /attach: returns conflict error response with asset ID and session ID when replacement conflict occurs")
    void shouldReturnConflictResponseWithAssetIdWhenAttachExistingFailsWithConflict() throws Exception {
        UUID oldAssetId = UUID.randomUUID();
        UUID newAssetId = UUID.randomUUID();
        stubTitleAndEntry(oldAssetId);

        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-replace:" + oldAssetId, newAssetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));
        doThrow(new ScreenEntryVideoConflictException(entryId, oldAssetId, UUID.randomUUID()))
                .when(replaceScreenEntryVideoUseCase).execute(titleId, entryId, newAssetId, oldAssetId);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach",
                        titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", newAssetId.toString(),
                                "isReplacement", "true",
                                "expectedCurrentVideoMediaAssetId", oldAssetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_VIDEO_CONFLICT"))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(newAssetId.toString()));

        verify(replaceScreenEntryVideoUseCase).execute(titleId, entryId, newAssetId, oldAssetId);
        verify(mediaContract, never()).delete(any());
        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
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
                .andExpect(jsonPath("$.playbackUrl").value("/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content"))
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
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
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
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("AKIAIOSFODNN7EXAMPLE"))));
    }

    @Test
    @DisplayName("GET /content: streams video successfully when entry is DRAFT")
    void shouldStreamVideoWhenEntryIsDraft() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", new ByteArrayInputStream("video-data".getBytes())
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Cache-Control", "private, no-cache, no-store, must-revalidate"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().bytes("video-data".getBytes()));
    }

    @Test
    @DisplayName("GET /content: streams video successfully when entry is PUBLISHED")
    void shouldStreamVideoWhenEntryIsPublished() throws Exception {
        stubTitleAndEntryWithStatus(assetId, ScreenPublicationStatus.PUBLISHED);
        stubActiveVideoMediaAsset(5000000L);
        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", new ByteArrayInputStream("video-data".getBytes())
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().bytes("video-data".getBytes()));
    }

    @Test
    @DisplayName("GET /content: streams video successfully when entry is ARCHIVED")
    void shouldStreamVideoWhenEntryIsArchived() throws Exception {
        stubTitleAndEntryWithStatus(assetId, ScreenPublicationStatus.ARCHIVED);
        stubActiveVideoMediaAsset(5000000L);
        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", new ByteArrayInputStream("video-data".getBytes())
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().bytes("video-data".getBytes()));
    }

    @Test
    @DisplayName("GET /content: returns 404 when entry has no video attached")
    void shouldReturn404WhenNoVideoAttached() throws Exception {
        stubTitleAndEntry(null);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /content: returns 404 when entry is not found or does not belong to title")
    void shouldReturn404WhenEntryNotFound() throws Exception {
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(new ScreenTitleDTO(
                titleId, "title-slug", "Title", null, null, ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION, null, null, ScreenPublicationStatus.PUBLISHED,
                null, now, now
        ));
        when(getScreenEntryDetailUseCase.execute(titleId, entryId))
                .thenThrow(new ScreenEntryNotFoundException(entryId));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /content: returns 404 when title is not found")
    void shouldReturn404WhenTitleNotFound() throws Exception {
        when(getScreenTitleDetailUseCase.execute(titleId))
                .thenThrow(new ScreenTitleNotFoundException(titleId));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /content: returns 404 when MediaAsset is not found")
    void shouldReturn404WhenMediaAssetNotFound() throws Exception {
        stubTitleAndEntry(assetId);
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /content: returns 404 when MediaAsset status is DELETED")
    void shouldReturn404WhenMediaAssetIsDeleted() throws Exception {
        stubTitleAndEntry(assetId);
        MediaVersionDTO version = new MediaVersionDTO(
                UUID.randomUUID(), assetId, 1, null, "video/mp4", 5000000L, "video.mp4", now
        );
        MediaAssetDetailDTO detail = new MediaAssetDetailDTO(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PRIVATE, MediaAssetStatusDTO.DELETED, 1, now, now, version
        );
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /content: returns 404 when MediaAsset mediaType is not VIDEO")
    void shouldReturn404WhenMediaAssetIsNotVideo() throws Exception {
        stubTitleAndEntry(assetId);
        MediaVersionDTO version = new MediaVersionDTO(
                UUID.randomUUID(), assetId, 1, null, "image/jpeg", 500000L, "cover.jpg", now
        );
        MediaAssetDetailDTO detail = new MediaAssetDetailDTO(
                assetId, MediaTypeDTO.IMAGE, MediaVisibilityDTO.PRIVATE, MediaAssetStatusDTO.ACTIVE, 1, now, now, version
        );
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /content: returns 206 Partial Content for valid byte range")
    void shouldReturn206ForValidByteRange() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        byte[] expectedBytes = new byte[1024];
        when(mediaContract.openRangeStream(assetId, 0L, 1024L)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 1024L, "hash", new ByteArrayInputStream(expectedBytes)
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId)
                        .header("Range", "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Range", "bytes 0-1023/5000000"))
                .andExpect(header().string("Content-Length", "1024"))
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(expectedBytes));
    }

    @Test
    @DisplayName("GET /content: returns 416 Range Not Satisfiable for unsatisfiable range")
    void shouldReturn416ForUnsatisfiableByteRange() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId)
                        .header("Range", "bytes=99999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"));
    }

    @Test
    @DisplayName("HEAD /content: returns 200 OK with metadata headers and no body")
    void shouldReturn200ForHeadRequestWithMetadataHeaders() throws Exception {
        stubTitleAndEntry(assetId);
        UUID versionId = stubActiveVideoMediaAsset(5000000L);

        mockMvc.perform(head("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Cache-Control", "private, no-cache, no-store, must-revalidate"))
                .andExpect(header().string("ETag", "\"" + versionId + "\""));

        verify(mediaContract, never()).openStream(any());
        verify(mediaContract, never()).openRangeStream(any(), any(long.class), any(long.class));
    }

    @Test
    @DisplayName("GET /content: propagates storage open failure during async streaming without swallowing")
    void shouldPropagateStorageOpenFailureDuringAsyncStreaming() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        when(mediaContract.openStream(assetId)).thenThrow(new RuntimeException("B2 storage open failed"));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThatThrownBy(() -> mockMvc.perform(asyncDispatch(result)))
                .hasCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("B2 storage open failed");
    }

    @Test
    @DisplayName("GET /content: storage InputStream throwing IOException('Broken pipe') MUST propagate as failure, not success")
    void shouldPropagateStorageInputStreamBrokenPipeFailure() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream brokenPipeStream = new InputStream() {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
            @Override
            public int read() throws IOException {
                throw new IOException("Broken pipe");
            }
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", brokenPipeStream
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        // Upstream storage read failure MUST propagate and must NOT be treated as client disconnect
        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Broken pipe");

        // MediaAssetStreamDTO ensures input stream is closed exactly once
        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /content: storage InputStream throwing IOException('Connection reset') MUST propagate as failure")
    void shouldPropagateStorageInputStreamConnectionResetFailure() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream connectionResetStream = new InputStream() {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Connection reset by peer");
            }
            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset by peer");
            }
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", connectionResetStream
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Connection reset by peer");

        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /content: storage InputStream throwing ordinary read exception propagates failure and closes resource")
    void shouldPropagateStorageInputStreamOrdinaryReadFailure() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream failingStream = new InputStream() {
            private int count = 0;
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (++count > 2) {
                    throw new IOException("Mid-stream B2 connection drop");
                }
                return Math.min(len, 100);
            }
            @Override
            public int read() throws IOException {
                throw new IOException("Mid-stream B2 connection drop");
            }
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", failingStream
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThatThrownBy(() -> mockMvc.perform(asyncDispatch(result)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Mid-stream B2 connection drop");

        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("StreamingResponseBody: downstream client disconnect on OutputStream handled gracefully and resource closed once")
    void shouldHandleDownstreamClientDisconnectGracefullyAndCloseResource() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream goodStream = new ByteArrayInputStream("valid-video-bytes".getBytes()) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", goodStream
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        OutputStream brokenClientOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        // When downstream OutputStream throws client disconnect, it is logged and handled cleanly without rethrowing
        body.writeTo(brokenClientOutputStream);

        // Resource closed exactly once
        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("StreamingResponseBody: downstream unexpected OutputStream failure propagates without leaking diagnostics")
    void shouldPropagateDownstreamUnexpectedOutputStreamFailure() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream goodStream = new ByteArrayInputStream("valid-video-bytes".getBytes()) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", goodStream
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        OutputStream failingOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Disk quota exceeded");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Disk quota exceeded");
            }
        };

        assertThatThrownBy(() -> body.writeTo(failingOutputStream))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Disk quota exceeded");

        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("StreamingResponseBody: resource is closed exactly once after successful streaming")
    void shouldCloseResourceExactlyOnceOnSuccess() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream goodStream = new ByteArrayInputStream("test-video-payload".getBytes()) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", goodStream
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().bytes("test-video-payload".getBytes()));

        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /content (Range): propagates partial range stream read failure and closes resource")
    void shouldPropagatePartialRangeStreamReadFailureAndCloseResource() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream failingRangeStream = new InputStream() {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Storage read timeout during range");
            }
            @Override
            public int read() throws IOException {
                throw new IOException("Storage read timeout during range");
            }
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openRangeStream(assetId, 0L, 1024L)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 1024L, "hash", failingRangeStream
        ));

        MvcResult result = mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", titleId, entryId)
                        .header("Range", "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThatThrownBy(() -> mockMvc.perform(asyncDispatch(result)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Storage read timeout during range");

        assertThat(closeCount.get()).isEqualTo(1);
    }

    // =========================================================================
    // DISCARD UPLOAD SESSION TESTS
    // =========================================================================

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: successfully discards active video upload session")
    void shouldSuccessfullyDiscardVideoUploadSession() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(assetId);
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + assetId + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.message").value("Completed video upload asset [" + assetId + "] successfully discarded."));

        verify(discardScreenEntryVideoUploadUseCase).execute(eq(new DiscardScreenEntryVideoUploadCommand(
                titleId,
                entryId,
                sessionId,
                assetId,
                "admin"
        )));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: returns ALREADY_DISCARDED for already deleted asset")
    void shouldReturnAlreadyDiscardedWhenSessionIsAlreadyDiscarded() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(assetId);
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.ALREADY_DISCARDED,
                        "Completed video upload asset [" + assetId + "] was already discarded."
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("ALREADY_DISCARDED"))
                .andExpect(jsonPath("$.message").value("Completed video upload asset [" + assetId + "] was already discarded."));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects query-only request with form content-type as 415")
    void shouldRejectQueryOnlyRequestWithoutJsonBody() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("videoMediaAssetId", assetId.toString())
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isUnsupportedMediaType());

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects when videoMediaAssetId is missing in JSON body")
    void shouldRejectWhenVideoMediaAssetIdMissingInJsonBody() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("videoMediaAssetId không được để trống khi thực hiện hủy video đã upload."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects when videoMediaAssetId is null in JSON body")
    void shouldRejectWhenVideoMediaAssetIdIsNullInJsonBody() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":null}")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("videoMediaAssetId không được để trống khi thực hiện hủy video đã upload."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: sanitizes unexpected IllegalArgumentException into HTTP 500 without leaking secrets")
    void shouldSanitizeUnexpectedIllegalArgumentExceptionInDiscard() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new IllegalArgumentException("INTERNAL_LEAK: db_pass=super_secret_123 on host=10.0.0.1"));

        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(assetId);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("super_secret_123"))));
    }

    @Test
    @DisplayName("POST /attach: sanitizes unexpected IllegalArgumentException into HTTP 500 without leaking secrets")
    void shouldSanitizeUnexpectedIllegalArgumentExceptionInAttach() throws Exception {
        stubTitleAndEntry(null);
        MediaUploadSessionResponseDTO completedSession = new MediaUploadSessionResponseDTO(
                sessionId, "COMPLETED", "admin", "screen", "SCREEN_ENTRY", entryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", assetId, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(sessionId)).thenReturn(Optional.of(completedSession));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("SECRET_STORAGE_KEY: b2-secret-abc"))
                .when(attachScreenEntryVideoUseCase).execute(titleId, entryId, assetId);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", titleId, entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", sessionId.toString(),
                                "videoMediaAssetId", assetId.toString()
                        )))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("b2-secret-abc"))));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: sanitizes unknown BaseApplicationException into HTTP 500 without leaking message")
    void shouldSanitizeUnknownBaseApplicationException() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new BaseApplicationException("UNKNOWN_INTERNAL_ERROR", "INTERNAL_SECRET_LEAK: api_key=topsecret") {});

        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(assetId);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("topsecret"))));
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects malformed JSON with HTTP 400 Bad Request")
    void shouldReturnBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Dữ liệu yêu cầu không hợp lệ hoặc sai định dạng JSON."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects invalid body UUID format with HTTP 400 Bad Request")
    void shouldReturnBadRequestForInvalidUuidInBody() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":\"not-a-valid-uuid\"}")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Dữ liệu yêu cầu không hợp lệ hoặc sai định dạng JSON."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects invalid path UUID format with HTTP 400 Bad Request")
    void shouldReturnBadRequestForInvalidPathUuid() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        "not-a-uuid", entryId, sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(assetId)))
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Tham số đường dẫn hoặc tham số truy vấn không đúng định dạng."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /upload-session/{sessionId}/discard: rejects unsupported content type with HTTP 415")
    void shouldReturnUnsupportedMediaTypeWhenContentTypeIsNotJson() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("text-payload")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isUnsupportedMediaType());

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("GET /upload-session/{sessionId}/discard: rejects GET method with HTTP 405 Method Not Allowed")
    void shouldReturnMethodNotAllowedForGetOnDiscardRoute() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, sessionId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isMethodNotAllowed());

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("isClientDisconnect: correctly identifies client connection abort patterns and excludes storage errors")
    void shouldCorrectlyIdentifyClientDisconnectExceptions() {
        // Must match client disconnect signatures
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("An established connection was aborted by the software in your host machine"))).isTrue();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("Broken pipe"))).isTrue();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("Connection reset by peer"))).isTrue();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("Software caused connection abort: socket write error"))).isTrue();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("connection was aborted"))).isTrue();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("connection closed"))).isTrue();

        // Must match wrapped/nested cause
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new RuntimeException(new IOException("broken pipe")))).isTrue();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new Exception("Outer", new RuntimeException("Inner", new IOException("connection reset"))))).isTrue();

        // Must NOT match storage or unexpected errors (fail-closed)
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(null)).isFalse();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("Mid-stream B2 connection drop"))).isFalse();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("Disk quota exceeded"))).isFalse();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IOException("Read timed out"))).isFalse();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new RuntimeException("Something went wrong"))).isFalse();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new NullPointerException())).isFalse();
        assertThat(AdminScreenEntryVideoController.isClientDisconnect(
                new IllegalArgumentException("invalid argument"))).isFalse();
    }

    @Test
    @DisplayName("handleUnexpected: client disconnect returns null without error response body or converter error")
    void shouldHandleClientDisconnectGracefullyReturningNull() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException clientAbort = new IOException("An established connection was aborted by the software in your host machine");

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(clientAbort, request, response);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("handleUnexpected: AsyncRequestTimeoutException before commit returns 503 without body")
    void shouldHandleAsyncRequestTimeoutBeforeCommitWith503AndNoBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AsyncRequestTimeoutException timeoutEx = new AsyncRequestTimeoutException();

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(timeoutEx, request, response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).isNull();
    }

    @Test
    @DisplayName("handleUnexpected: AsyncRequestTimeoutException after commit returns null without attempting write")
    void shouldHandleAsyncRequestTimeoutAfterCommitReturningNull() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        AsyncRequestTimeoutException timeoutEx = new AsyncRequestTimeoutException();

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(timeoutEx, request, response);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("handleUnexpected: unexpected exception on committed response returns null without attempting write")
    void shouldHandleUnexpectedExceptionOnCommittedResponseReturningNull() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        RuntimeException ex = new RuntimeException("Stream write error after headers sent");

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(ex, request, response);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("handleUnexpected: streaming request with uncommitted response rethrows exception cleanly")
    void shouldRethrowExceptionForStreamingContentWhenUncommitted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException ex = new IllegalStateException("Storage cluster unreachable");

        assertThatThrownBy(() -> controller.handleUnexpected(ex, request, response))
                .isSameAs(ex);
    }

    @Test
    @DisplayName("handleUnexpected: non-streaming request with uncommitted response returns JSON 500 even on connection reset")
    void shouldReturnJson500ForNonStreamingRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/attach");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuntimeException ex = new RuntimeException("Database connection failure", new IOException("Connection reset by peer"));

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(ex, request, response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error()).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("GET /content: configures WebAsyncManager timeout when AsyncWebRequest is present")
    void shouldConfigureAsyncTimeoutWhenAsyncWebRequestIsPresent() {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);

        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        AsyncWebRequest asyncWebRequest = org.mockito.Mockito.mock(AsyncWebRequest.class);
        WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
        asyncManager.setAsyncWebRequest(asyncWebRequest);

        ResponseEntity<StreamingResponseBody> entity = controller.streamVideoContent(titleId, entryId, request);

        assertThat(entity).isNotNull();
        verify(asyncWebRequest).setTimeout(1_800_000L);
    }

    @Test
    @DisplayName("isUpstreamStorageFailure: correctly identifies StorageStreamException in causal chain")
    void shouldCorrectlyIdentifyUpstreamStorageFailures() {
        assertThat(AdminScreenEntryVideoController.isUpstreamStorageFailure(
                new AdminScreenEntryVideoController.StorageStreamException("open", new IOException("Connection reset")))).isTrue();
        assertThat(AdminScreenEntryVideoController.isUpstreamStorageFailure(
                new RuntimeException("wrapper", new AdminScreenEntryVideoController.StorageStreamException("read", new IOException("Connection reset"))))).isTrue();

        assertThat(AdminScreenEntryVideoController.isUpstreamStorageFailure(null)).isFalse();
        assertThat(AdminScreenEntryVideoController.isUpstreamStorageFailure(
                new IOException("Connection reset by peer"))).isFalse();
        assertThat(AdminScreenEntryVideoController.isUpstreamStorageFailure(
                new IOException("An established connection was aborted by the software in your host machine"))).isFalse();
    }

    @Test
    @DisplayName("GET /content: B2 stream opening failure with connection reset propagates as failure and is NOT swallowed")
    void shouldPropagateB2StreamOpeningConnectionResetFailure() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        when(mediaContract.openStream(assetId)).thenAnswer(inv -> {
            throw new IOException("Connection reset by peer");
        });

        MockHttpServletRequest directRequest = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, directRequest);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        // Must propagate as StorageStreamException, NOT swallow as client disconnect
        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(AdminScreenEntryVideoController.StorageStreamException.class)
                .hasMessageContaining("Connection reset by peer")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("GET /content: B2 stream read failure with connection reset propagates as failure and is NOT swallowed")
    void shouldPropagateB2StreamReadConnectionResetFailure() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream b2ResetStream = new InputStream() {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Connection reset by peer");
            }
            @Override
            public int read() throws IOException {
                throw new IOException("Connection reset by peer");
            }
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", b2ResetStream
        ));

        MockHttpServletRequest directRequest = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, directRequest);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        // Must propagate as StorageStreamException and close storage stream, NOT swallow as client disconnect
        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(AdminScreenEntryVideoController.StorageStreamException.class)
                .hasMessageContaining("Connection reset by peer")
                .hasCauseInstanceOf(IOException.class);

        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("StreamingResponseBody: browser downstream OutputStream connection reset handled gracefully without rethrowing")
    void shouldHandleDownstreamBrowserConnectionResetGracefully() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        AtomicInteger closeCount = new AtomicInteger(0);
        InputStream goodStream = new ByteArrayInputStream("valid-video-bytes".getBytes()) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };

        when(mediaContract.openStream(assetId)).thenReturn(new MediaAssetStreamDTO(
                assetId, 1, "video/mp4", 5000000L, "hash", goodStream
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        OutputStream clientOutputStream = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Connection reset by peer");
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Connection reset by peer");
            }
        };

        // Downstream client cancellation must return cleanly and close upstream storage stream
        body.writeTo(clientOutputStream);
        assertThat(closeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Non-streaming route: unrelated connection reset must return HTTP 500 JSON and NEVER be swallowed")
    void shouldReturnHttp500ForNonStreamingOperationWithConnectionReset() throws Exception {
        UUID mockSessionId = UUID.randomUUID();
        when(discardScreenEntryVideoUploadUseCase.execute(any()))
                .thenThrow(new RuntimeException("Database error", new IOException("Connection reset by peer")));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        titleId, entryId, mockSessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":\"" + UUID.randomUUID() + "\"}")
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    @DisplayName("handleUnexpected: upstream storage failure with connection reset is rethrown when uncommitted")
    void shouldRethrowUpstreamStorageFailureWithConnectionResetWhenUncommitted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AdminScreenEntryVideoController.StorageStreamException storageEx =
                new AdminScreenEntryVideoController.StorageStreamException("Storage stream open failure",
                        new IOException("Connection reset by peer"));

        assertThatThrownBy(() -> controller.handleUnexpected(storageEx, request, response))
                .isSameAs(storageEx);
    }

    @Test
    @DisplayName("handleUnexpected: upstream storage failure on committed response returns null without JSON body")
    void shouldReturnNullForUpstreamStorageFailureOnCommittedResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        request.setAttribute(AdminScreenEntryVideoController.STREAMING_ACTIVE_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        AdminScreenEntryVideoController.StorageStreamException storageEx =
                new AdminScreenEntryVideoController.StorageStreamException("Storage stream read failure",
                        new IOException("Connection reset by peer"));

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(storageEx, request, response);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("handleUnexpected: /content request before streaming is active returns HTTP 500 JSON even on connection reset")
    void shouldReturnJson500WhenStreamingPathFailsBeforeStreamingIsActive() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RuntimeException ex = new RuntimeException("Pre-stream database failure", new IOException("Connection reset by peer"));

        ResponseEntity<ScreenVideoErrorResponse> result = controller.handleUnexpected(ex, request, response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().error()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(result.getBody().message()).isEqualTo("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video.");
    }

    @Test
    @DisplayName("GET /content: pre-streaming metadata lookup failure with connection reset returns HTTP 500 JSON via MockMvc")
    void shouldReturnHttp500WhenPreStreamingDependencyThrowsConnectionReset() throws Exception {
        UUID nonExistentTitleId = UUID.randomUUID();
        when(getScreenTitleDetailUseCase.execute(nonExistentTitleId))
                .thenThrow(new RuntimeException("Database connection failure", new IOException("Connection reset by peer")));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", nonExistentTitleId, entryId))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."));
    }

    @Test
    @DisplayName("StreamingResponseBody: when stream supplier returns null DTO, throws StorageStreamException")
    void shouldThrowStorageStreamExceptionWhenStreamSupplierReturnsNullDto() {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        when(mediaContract.openStream(assetId)).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(AdminScreenEntryVideoController.StorageStreamException.class)
                .hasMessageContaining("Storage stream is unavailable (null stream)");
    }

    @Test
    @DisplayName("StreamingResponseBody: when DTO has null content stream, closes DTO and throws StorageStreamException")
    void shouldCloseDtoAndThrowStorageStreamExceptionWhenContentStreamIsNull() throws Exception {
        stubTitleAndEntry(assetId);
        stubActiveVideoMediaAsset(5000000L);
        MediaAssetStreamDTO mockDto = mock(MediaAssetStreamDTO.class);
        when(mockDto.contentStream()).thenReturn(null);
        when(mediaContract.openStream(assetId)).thenReturn(mockDto);

        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/admin/screen/titles/" + titleId + "/entries/" + entryId + "/video/content");
        ResponseEntity<StreamingResponseBody> response = controller.streamVideoContent(titleId, entryId, request);
        StreamingResponseBody body = response.getBody();
        assertThat(body).isNotNull();

        assertThatThrownBy(() -> body.writeTo(new ByteArrayOutputStream()))
                .isInstanceOf(AdminScreenEntryVideoController.StorageStreamException.class)
                .hasMessageContaining("Storage stream content is unavailable");

        verify(mockDto).close();
    }

    @Test
    @DisplayName("POST /reconcile: successfully reconciles video visibility to PUBLIC")
    void shouldReconcileVideoVisibilitySuccessfully() throws Exception {
        ReconcileScreenVideoVisibilityResult result = new ReconcileScreenVideoVisibilityResult(
                titleId, entryId, assetId, "PRIVATE", "PUBLIC",
                ReconcileScreenVideoVisibilityResult.Outcome.RECONCILED, "Video visibility reconciled to PUBLIC"
        );
        when(reconcileScreenEntryVideoVisibilityUseCase.execute(titleId, entryId)).thenReturn(result);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", titleId, entryId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleId").value(titleId.toString()))
                .andExpect(jsonPath("$.entryId").value(entryId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.reconciledVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.outcome").value("RECONCILED"));
    }

    @Test
    @DisplayName("POST /reconcile: returns 404 when entry or title not found")
    void shouldReturn404WhenEntryNotFoundOnReconcile() throws Exception {
        when(reconcileScreenEntryVideoVisibilityUseCase.execute(titleId, entryId))
                .thenThrow(new ScreenEntryNotFoundException(entryId));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", titleId, entryId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /reconcile: returns 200 with NO_VIDEO_ATTACHED outcome when no video attached")
    void shouldReturnNoVideoAttachedOutcomeOnReconcile() throws Exception {
        ReconcileScreenVideoVisibilityResult result = new ReconcileScreenVideoVisibilityResult(
                titleId, entryId, null, null, null,
                ReconcileScreenVideoVisibilityResult.Outcome.NO_VIDEO_ATTACHED, "No video attached"
        );
        when(reconcileScreenEntryVideoVisibilityUseCase.execute(titleId, entryId)).thenReturn(result);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", titleId, entryId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_VIDEO_ATTACHED"));
    }

    @Test
    @DisplayName("POST /reconcile: returns 200 with KEPT_PUBLIC_BY_SHARED_REFERENCE outcome for shared public asset")
    void shouldReturnKeptPublicBySharedReferenceOutcomeOnReconcile() throws Exception {
        ReconcileScreenVideoVisibilityResult result = new ReconcileScreenVideoVisibilityResult(
                titleId, entryId, assetId, "PUBLIC", "PUBLIC",
                ReconcileScreenVideoVisibilityResult.Outcome.KEPT_PUBLIC_BY_SHARED_REFERENCE,
                "Shared published reference preserved"
        );
        when(reconcileScreenEntryVideoVisibilityUseCase.execute(titleId, entryId)).thenReturn(result);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", titleId, entryId)
                        .principal(new TestingAuthenticationToken("admin", "pass")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciledVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.outcome").value("KEPT_PUBLIC_BY_SHARED_REFERENCE"));
    }

    private UUID stubActiveVideoMediaAsset(long sizeBytes) {
        UUID versionId = UUID.randomUUID();
        MediaVersionDTO version = new MediaVersionDTO(
                versionId, assetId, 1, null, "video/mp4", sizeBytes, "video.mp4", now
        );
        MediaAssetDetailDTO detail = new MediaAssetDetailDTO(
                assetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PRIVATE, MediaAssetStatusDTO.ACTIVE, 1, now, now, version
        );
        when(mediaContract.getAssetDetail(assetId)).thenReturn(Optional.of(detail));
        return versionId;
    }

    private void stubTitleAndEntryWithStatus(UUID currentVideoId, ScreenPublicationStatus status) {
        ScreenTitleDTO title = new ScreenTitleDTO(
                titleId, "title-slug", "Title", null, null, ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION, null, null, ScreenPublicationStatus.PUBLISHED,
                null, now, now
        );
        ScreenEntryDTO entry = new ScreenEntryDTO(
                entryId, titleId, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                currentVideoId, null, 1200L, status, null, now, now
        );
        when(getScreenTitleDetailUseCase.execute(titleId)).thenReturn(title);
        when(getScreenEntryDetailUseCase.execute(titleId, entryId)).thenReturn(entry);
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
