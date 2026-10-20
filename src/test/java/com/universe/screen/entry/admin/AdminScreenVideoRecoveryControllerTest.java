package com.universe.screen.entry.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult.DiscardOutcome;
import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.dto.RecoverableVideoUploadView;
import com.universe.screen.application.dto.RecoveryClassification;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenUploadSessionNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminScreenVideoRecoveryController Unit Tests")
class AdminScreenVideoRecoveryControllerTest {

    @Mock
    private FindRecoverableScreenVideoUploadsUseCase useCase;

    @Mock
    private DiscardScreenEntryVideoUploadUseCase discardUseCase;

    private AdminScreenVideoRecoveryController controller;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();
    private final Instant completedAt = Instant.parse("2026-10-20T10:15:30Z");

    @BeforeEach
    void setUp() {
        controller = new AdminScreenVideoRecoveryController(useCase, discardUseCase);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // =========================================================================
    // GET /admin/screen/video-uploads/recoverable TESTS
    // =========================================================================

    @Test
    @DisplayName("GET /admin/screen/video-uploads/recoverable: returns 200 with default pagination and serialized fields")
    void shouldReturnRecoverableUploadsWithDefaultParameters() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        RecoverableVideoUploadView view = new RecoverableVideoUploadView(
                sessionId,
                assetId,
                entryId,
                "Episode 1",
                titleId,
                "ep1_raw.mp4",
                52428800L,
                completedAt,
                "INITIAL",
                null,
                RecoveryClassification.UNATTACHED,
                true,
                true
        );

        RecoverableVideoUploadPageDTO pageResult = new RecoverableVideoUploadPageDTO(
                List.of(view),
                0,
                20,
                1L,
                1,
                true,
                true
        );

        when(useCase.execute(any(FindRecoverableScreenVideoUploadsQuery.class))).thenReturn(pageResult);

        mockMvc.perform(get("/admin/screen/video-uploads/recoverable")
                        .principal(principal)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.items[0].sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.items[0].videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.items[0].targetEntryId").value(entryId.toString()))
                .andExpect(jsonPath("$.items[0].targetEntryTitle").value("Episode 1"))
                .andExpect(jsonPath("$.items[0].targetTitleId").value(titleId.toString()))
                .andExpect(jsonPath("$.items[0].originalFilename").value("ep1_raw.mp4"))
                .andExpect(jsonPath("$.items[0].declaredSizeBytes").value(52428800L))
                .andExpect(jsonPath("$.items[0].completedAt").value(completedAt.toString()))
                .andExpect(jsonPath("$.items[0].intent").value("INITIAL"))
                .andExpect(jsonPath("$.items[0].replacementExpectedAssetId").doesNotExist())
                .andExpect(jsonPath("$.items[0].classification").value("UNATTACHED"))
                .andExpect(jsonPath("$.items[0].canRetry").value(true))
                .andExpect(jsonPath("$.items[0].canDiscard").value(true));

        ArgumentCaptor<FindRecoverableScreenVideoUploadsQuery> queryCaptor =
                ArgumentCaptor.forClass(FindRecoverableScreenVideoUploadsQuery.class);
        verify(useCase).execute(queryCaptor.capture());

        FindRecoverableScreenVideoUploadsQuery capturedQuery = queryCaptor.getValue();
        assertThat(capturedQuery.authenticatedActor()).isEqualTo("admin-user-1");
        assertThat(capturedQuery.targetEntryId()).isNull();
        assertThat(capturedQuery.page()).isEqualTo(0);
        assertThat(capturedQuery.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("GET /admin/screen/video-uploads/recoverable: binds custom entryId, page, and size")
    void shouldBindCustomQueryParams() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-2", null);

        RecoverableVideoUploadPageDTO emptyPage = RecoverableVideoUploadPageDTO.empty(2, 15);
        when(useCase.execute(any(FindRecoverableScreenVideoUploadsQuery.class))).thenReturn(emptyPage);

        mockMvc.perform(get("/admin/screen/video-uploads/recoverable")
                        .param("entryId", entryId.toString())
                        .param("page", "2")
                        .param("size", "15")
                        .principal(principal)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(15))
                .andExpect(jsonPath("$.items").isEmpty());

        ArgumentCaptor<FindRecoverableScreenVideoUploadsQuery> queryCaptor =
                ArgumentCaptor.forClass(FindRecoverableScreenVideoUploadsQuery.class);
        verify(useCase).execute(queryCaptor.capture());

        FindRecoverableScreenVideoUploadsQuery capturedQuery = queryCaptor.getValue();
        assertThat(capturedQuery.authenticatedActor()).isEqualTo("admin-user-2");
        assertThat(capturedQuery.targetEntryId()).isEqualTo(entryId);
        assertThat(capturedQuery.page()).isEqualTo(2);
        assertThat(capturedQuery.size()).isEqualTo(15);
    }

    @Test
    @DisplayName("GET /admin/screen/video-uploads/recoverable: throws AccessDeniedException when unauthenticated")
    void shouldThrowAccessDeniedWhenUnauthenticated() {
        assertThatThrownBy(() -> controller.getRecoverableUploads(entryId, 0, 20, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Yêu cầu xác thực tài khoản hợp lệ.");
    }

    @Test
    @DisplayName("GET /admin/screen/video-uploads/recoverable: throws AccessDeniedException when principal has blank name")
    void shouldThrowAccessDeniedWhenPrincipalNameIsBlank() {
        TestingAuthenticationToken blankPrincipal = new TestingAuthenticationToken("   ", null);

        assertThatThrownBy(() -> controller.getRecoverableUploads(entryId, 0, 20, blankPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Yêu cầu xác thực tài khoản hợp lệ.");
    }

    // =========================================================================
    // POST /admin/screen/video-uploads/{sessionId}/discard TESTS (S3-3B2)
    // =========================================================================

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 200 with DISCARDED status when request body includes videoMediaAssetId")
    void shouldDiscardUploadSessionWithRequestBody() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + assetId + "] successfully discarded."
                ));

        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(assetId);

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.message").value("Completed video upload asset [" + assetId + "] successfully discarded."));

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor =
                ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardUseCase).execute(captor.capture());

        DiscardScreenEntryVideoUploadCommand cmd = captor.getValue();
        assertThat(cmd.sessionId()).isEqualTo(sessionId);
        assertThat(cmd.videoMediaAssetId()).isEqualTo(assetId);
        assertThat(cmd.titleId()).isNull();
        assertThat(cmd.entryId()).isNull();
        assertThat(cmd.authenticatedActor()).isEqualTo("admin-user-1");
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 200 with DISCARDED status without request body")
    void shouldDiscardUploadSessionWithoutRequestBody() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + assetId + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"));

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor =
                ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardUseCase).execute(captor.capture());

        DiscardScreenEntryVideoUploadCommand cmd = captor.getValue();
        assertThat(cmd.sessionId()).isEqualTo(sessionId);
        assertThat(cmd.videoMediaAssetId()).isNull();
        assertThat(cmd.titleId()).isNull();
        assertThat(cmd.entryId()).isNull();
        assertThat(cmd.authenticatedActor()).isEqualTo("admin-user-1");
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 200 with ALREADY_DISCARDED status")
    void shouldReturnAlreadyDiscardedStatus() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.ALREADY_DISCARDED,
                        "Completed video upload asset [" + assetId + "] was already discarded."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(assetId.toString()))
                .andExpect(jsonPath("$.status").value("ALREADY_DISCARDED"));
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 403 Forbidden on ScreenVideoDiscardAccessDeniedException")
    void shouldReturn403WhenActorIsNotAuthorized() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("impostor-admin", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoDiscardAccessDeniedException(
                        "Authenticated actor is not authorized to discard this video upload session."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_DISCARD_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Authenticated actor is not authorized to discard this video upload session."));
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 409 Conflict on ScreenVideoAlreadyReferencedException")
    void shouldReturn409WhenVideoIsAlreadyReferenced() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoAlreadyReferencedException(assetId, List.of(entryId)));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_ALREADY_REFERENCED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(assetId.toString())));
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 404 Not Found on ScreenUploadSessionNotFoundException")
    void shouldReturn404WhenUploadSessionNotFound() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenUploadSessionNotFoundException(sessionId));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCREEN_UPLOAD_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 400 Bad Request on ScreenVideoDiscardValidationException")
    void shouldReturn400OnValidationException() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoDiscardValidationException(
                        "REGISTERED_ASSET_ID_MISMATCH",
                        "Upload session registered asset ID does not match."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("REGISTERED_ASSET_ID_MISMATCH"));
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 400 Bad Request on InvalidScreenMediaAssetTypeException")
    void shouldReturn400OnInvalidMediaType() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        when(discardUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new InvalidScreenMediaAssetTypeException(assetId, "VIDEO", "IMAGE"));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SCREEN_MEDIA_ASSET_TYPE"));
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 400 Bad Request on invalid UUID in sessionId path")
    void shouldReturn400OnInvalidSessionIdPath() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", "invalid-uuid")
                        .principal(principal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(discardUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: returns 400 Bad Request on malformed JSON body")
    void shouldReturn400OnMalformedJson() throws Exception {
        TestingAuthenticationToken principal = new TestingAuthenticationToken("admin-user-1", null);

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .principal(principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\": \"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(discardUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: throws AccessDeniedException when unauthenticated")
    void shouldThrowAccessDeniedWhenUnauthenticatedOnDiscard() {
        assertThatThrownBy(() -> controller.discardRecoverableVideoUpload(sessionId, null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Yêu cầu xác thực tài khoản hợp lệ.");
    }

    @Test
    @DisplayName("POST /{sessionId}/discard: throws AccessDeniedException when principal has blank name")
    void shouldThrowAccessDeniedWhenPrincipalBlankOnDiscard() {
        TestingAuthenticationToken blankPrincipal = new TestingAuthenticationToken("   ", null);

        assertThatThrownBy(() -> controller.discardRecoverableVideoUpload(sessionId, null, blankPrincipal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Yêu cầu xác thực tài khoản hợp lệ.");
    }
}
