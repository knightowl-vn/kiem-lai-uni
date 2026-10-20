package com.universe.screen.entry.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.dto.RecoverableVideoUploadView;
import com.universe.screen.application.dto.RecoveryClassification;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminScreenVideoRecoveryController Unit Tests")
class AdminScreenVideoRecoveryControllerTest {

    @Mock
    private FindRecoverableScreenVideoUploadsUseCase useCase;

    private AdminScreenVideoRecoveryController controller;
    private MockMvc mockMvc;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();
    private final Instant completedAt = Instant.parse("2026-10-20T10:15:30Z");

    @BeforeEach
    void setUp() {
        controller = new AdminScreenVideoRecoveryController(useCase);
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

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
}
