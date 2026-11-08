package com.universe.screen.entry.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.infrastructure.security.SafeReturnToValidator;
import com.universe.media.contracts.dto.AllocateUploadSessionRequestDTO;
import com.universe.media.contracts.dto.ClaimCompletionIntentRequestDTO;
import com.universe.media.contracts.dto.CompleteMultipartUploadRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaAssetStreamDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.dto.MultipartManifestPartDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import java.io.ByteArrayInputStream;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult.DiscardOutcome;
import com.universe.screen.application.dto.ReconcileScreenVideoVisibilityResult;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenUploadSessionNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
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
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminScreenEntryVideoController.class)
@Import({SecurityBeanConfig.class, AdminScreenEntryVideoSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenEntryVideoSecurityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String SUPER_ADMIN_EMAIL = "superadmin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";
    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SESSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public GoogleOAuthSuccessHandler googleOAuthSuccessHandler() {
            return new GoogleOAuthSuccessHandler(Mockito.mock(GoogleOAuthUserService.class));
        }

        @Bean
        public CustomAuthenticationFailureHandler authenticationFailureHandler() {
            return new CustomAuthenticationFailureHandler(new SafeReturnToValidator());
        }

        @Bean
        public AccountStatusFilter accountStatusFilter(SpringDataUserJpaRepository userRepository) {
            return new AccountStatusFilter(
                    com.universe.identity.infrastructure.security.AccountStatusFilterTestSupport
                            .queryPort(userRepository)
            );
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SpringDataUserJpaRepository springDataUserJpaRepository;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @MockBean
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    @MockBean
    private AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase;

    @MockBean
    private DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;

    @MockBean
    private ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase;

    @MockBean
    private MediaContract mediaContract;

    @MockBean
    private DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase;

    @MockBean
    private ReconcileScreenEntryVideoVisibilityUseCase reconcileScreenEntryVideoVisibilityUseCase;

    private ScreenTitleDTO sampleTitle;
    private ScreenEntryDTO sampleEntry;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-13T10:00:00Z");

        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(UUID.randomUUID().toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setDisplayName("Admin User");
        adminEntity.setPublicHandle("admin_user");
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);
        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));

        UserJpaEntity superAdminEntity = new UserJpaEntity();
        superAdminEntity.setId(UUID.randomUUID().toString());
        superAdminEntity.setEmail(SUPER_ADMIN_EMAIL);
        superAdminEntity.setDisplayName("Super Admin User");
        superAdminEntity.setPublicHandle("super_admin_user");
        superAdminEntity.setStatus("ACTIVE");
        superAdminEntity.setRole(UserRole.SUPER_ADMIN);
        when(springDataUserJpaRepository.findByEmail(SUPER_ADMIN_EMAIL)).thenReturn(Optional.of(superAdminEntity));

        UserJpaEntity userEntity = new UserJpaEntity();
        userEntity.setId(UUID.randomUUID().toString());
        userEntity.setEmail(USER_EMAIL);
        userEntity.setDisplayName("Regular User");
        userEntity.setPublicHandle("regular_user");
        userEntity.setStatus("ACTIVE");
        userEntity.setRole(UserRole.USER);
        when(springDataUserJpaRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(userEntity));

        sampleTitle = new ScreenTitleDTO(
                TITLE_ID, "title-slug", "Title", null, null, ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION, null, null, ScreenPublicationStatus.PUBLISHED,
                null, now, now
        );
        sampleEntry = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                null, null, 1200L, ScreenPublicationStatus.DRAFT, null, now, now
        );

        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);
    }

    @Test
    @DisplayName("Security: Anonymous user cannot access upload-session (redirects to /login)")
    void anonymousUser_cannotInitiateUploadSession() throws Exception {
        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(26214400L, "video.mp4", null);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("Security: Regular USER role is redirected to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotInitiateUploadSession() throws Exception {
        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(26214400L, "video.mp4", null);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN role without CSRF token is rejected and redirected to /access-denied")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withoutCsrf_isForbidden() throws Exception {
        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(26214400L, "video.mp4", null);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", TITLE_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN role with CSRF token can initiate upload session")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withCsrf_canInitiateUploadSession() throws Exception {
        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                SESSION_ID, "INITIATED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.allocateUploadSession(any(AllocateUploadSessionRequestDTO.class))).thenReturn(initiated);

        InitiateVideoUploadSessionRequest req = new InitiateVideoUploadSessionRequest(26214400L, "video.mp4", null);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("INITIATED"));
    }

    @Test
    @DisplayName("Security: ADMIN role can get presigned part upload URL")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canGetPresignedPartUrl() throws Exception {
        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                SESSION_ID, "INITIATED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(SESSION_ID)).thenReturn(Optional.of(initiated));

        String testUrl = "https://s3.us-east-005.backblazeb2.com/test-bucket/video.mp4?partNumber=1";
        when(mediaContract.generatePresignedPartUploadUrl(eq(SESSION_ID), eq(1), eq(ADMIN_EMAIL))).thenReturn(testUrl);

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/parts/{partNumber}/url",
                        TITLE_ID, ENTRY_ID, SESSION_ID, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumber").value(1))
                .andExpect(jsonPath("$.uploadUrl").value(testUrl));
    }

    @Test
    @DisplayName("Security: ADMIN role cannot access part URL for session belonging to a different Screen Entry")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_cannotGetPartUrl_forDifferentEntrySession() throws Exception {
        UUID otherEntryId = UUID.randomUUID();
        MediaUploadSessionResponseDTO crossEntrySession = new MediaUploadSessionResponseDTO(
                SESSION_ID, "INITIATED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", otherEntryId,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(SESSION_ID)).thenReturn(Optional.of(crossEntrySession));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/parts/{partNumber}/url",
                        TITLE_ID, ENTRY_ID, SESSION_ID, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Security: ADMIN role can complete upload session and attach video")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canCompleteUploadSession() throws Exception {
        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                SESSION_ID, "INITIATED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO completing = new MediaUploadSessionResponseDTO(
                SESSION_ID, "COMPLETING", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO completed = new MediaUploadSessionResponseDTO(
                SESSION_ID, "COMPLETED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", ASSET_ID, now, now.plusSeconds(86400)
        );

        when(mediaContract.getUploadSession(SESSION_ID)).thenReturn(Optional.of(initiated));
        when(mediaContract.claimCompletionIntent(any(ClaimCompletionIntentRequestDTO.class))).thenReturn(completing);
        when(mediaContract.completeMultipartUpload(any(CompleteMultipartUploadRequestDTO.class))).thenReturn(completed);

        CompleteVideoUploadSessionRequest req = new CompleteVideoUploadSessionRequest(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                List.of(new MultipartManifestPartDTO(1, 26214400L, "\"etag-1\""))
        );

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/complete",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.playbackUrl").value("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID + "/video/content"));

        verify(attachScreenEntryVideoUseCase).execute(TITLE_ID, ENTRY_ID, ASSET_ID);
    }

    @Test
    @DisplayName("Security: ADMIN role can abort upload session")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canAbortUploadSession() throws Exception {
        MediaUploadSessionResponseDTO initiated = new MediaUploadSessionResponseDTO(
                SESSION_ID, "INITIATED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );
        MediaUploadSessionResponseDTO aborted = new MediaUploadSessionResponseDTO(
                SESSION_ID, "ABORTED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", null, now, now.plusSeconds(86400)
        );

        when(mediaContract.getUploadSession(SESSION_ID)).thenReturn(Optional.of(initiated));
        when(mediaContract.abortUploadSession(SESSION_ID, ADMIN_EMAIL)).thenReturn(aborted);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/abort",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ABORTED"));
    }

    @Test
    @DisplayName("Security: ADMIN role can attach existing video when session is completed and provenance matches")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canAttachExistingVideo_withValidSession() throws Exception {
        MediaUploadSessionResponseDTO completed = new MediaUploadSessionResponseDTO(
                SESSION_ID, "COMPLETED", ADMIN_EMAIL, "screen", "SCREEN_ENTRY", ENTRY_ID,
                26214400L, 26214400L, 1, "video.mp4", "screen-video", ASSET_ID, now, now.plusSeconds(86400)
        );
        when(mediaContract.getUploadSession(SESSION_ID)).thenReturn(Optional.of(completed));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "sessionId", SESSION_ID.toString(),
                                "videoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoMediaAssetId").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.status").value("ATTACHED"));

        verify(attachScreenEntryVideoUseCase).execute(TITLE_ID, ENTRY_ID, ASSET_ID);
    }

    @Test
    @DisplayName("Security: USER role cannot access attach video endpoint")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotAttachVideo() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "sessionId", SESSION_ID.toString(),
                                "videoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN role without CSRF token cannot attach video")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withoutCsrf_cannotAttachVideo() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/attach", TITLE_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "sessionId", SESSION_ID.toString(),
                                "videoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: Unauthenticated request cannot detach video")
    void unauthenticated_cannotDetachVideo() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("Security: USER role cannot detach video")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotDetachVideo() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN without CSRF cannot detach video")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withoutCsrf_cannotDetachVideo() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN with CSRF can detach video successfully")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withCsrf_canDetachVideo() throws Exception {
        ScreenEntryDTO detachedEntry = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                null, null, 1200L, ScreenPublicationStatus.DRAFT, null, now, now
        );
        when(detachScreenEntryVideoUseCase.execute(TITLE_ID, ENTRY_ID, ASSET_ID)).thenReturn(detachedEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVideoMediaAssetId", ASSET_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DETACHED"));

        verify(detachScreenEntryVideoUseCase).execute(TITLE_ID, ENTRY_ID, ASSET_ID);
    }

    @Test
    @DisplayName("Security: Anonymous user cannot stream video (redirects to /login)")
    void anonymousUser_cannotStreamVideo() throws Exception {
        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", TITLE_ID, ENTRY_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("Security: Regular USER role cannot stream video (redirects to /access-denied)")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotStreamVideo() throws Exception {
        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", TITLE_ID, ENTRY_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN role can stream video successfully")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canStreamVideo() throws Exception {
        ScreenEntryDTO attachedEntry = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                ASSET_ID, null, 1200L, ScreenPublicationStatus.DRAFT, null, now, now
        );
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(attachedEntry);

        MediaVersionDTO version = new MediaVersionDTO(
                UUID.randomUUID(), ASSET_ID, 1, null, "video/mp4", 5000000L, "video.mp4", now
        );
        MediaAssetDetailDTO detail = new MediaAssetDetailDTO(
                ASSET_ID, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PRIVATE, MediaAssetStatusDTO.ACTIVE, 1, now, now, version
        );
        when(mediaContract.getAssetDetail(ASSET_ID)).thenReturn(Optional.of(detail));
        when(mediaContract.openStream(ASSET_ID)).thenReturn(new MediaAssetStreamDTO(
                ASSET_ID, 1, "video/mp4", 5000000L, "hash", new ByteArrayInputStream("video-bytes".getBytes())
        ));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", TITLE_ID, ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Cache-Control", "private, no-cache, no-store, must-revalidate"));
    }

    @Test
    @DisplayName("Security: SUPER_ADMIN role can stream video successfully")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_canStreamVideo() throws Exception {
        ScreenEntryDTO attachedEntry = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1", "Synopsis",
                ASSET_ID, null, 1200L, ScreenPublicationStatus.DRAFT, null, now, now
        );
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(attachedEntry);

        MediaVersionDTO version = new MediaVersionDTO(
                UUID.randomUUID(), ASSET_ID, 1, null, "video/mp4", 5000000L, "video.mp4", now
        );
        MediaAssetDetailDTO detail = new MediaAssetDetailDTO(
                ASSET_ID, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PRIVATE, MediaAssetStatusDTO.ACTIVE, 1, now, now, version
        );
        when(mediaContract.getAssetDetail(ASSET_ID)).thenReturn(Optional.of(detail));
        when(mediaContract.openStream(ASSET_ID)).thenReturn(new MediaAssetStreamDTO(
                ASSET_ID, 1, "video/mp4", 5000000L, "hash", new ByteArrayInputStream("video-bytes".getBytes())
        ));

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries/{entryId}/video/content", TITLE_ID, ENTRY_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Content-Length", "5000000"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Cache-Control", "private, no-cache, no-store, must-revalidate"));
    }

    // =========================================================================
    // S2-B2-C2-A GUARDED VIDEO DISCARD HTTP & SECURITY TESTS (A - M)
    // =========================================================================

    @Test
    @DisplayName("A. Unauthenticated user cannot discard (redirects to login)")
    void unauthenticatedUser_cannotDiscardVideoUploadSession() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("B. Authenticated non-admin cannot discard (redirects to /access-denied)")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotDiscardVideoUploadSession() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("C. ADMIN with valid CSRF can reach guarded discard")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withValidCsrf_canReachGuardedDiscard() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        ASSET_ID,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + ASSET_ID + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.message").value("Completed video upload asset [" + ASSET_ID + "] successfully discarded."));

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor = ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardScreenEntryVideoUploadUseCase).execute(captor.capture());
        DiscardScreenEntryVideoUploadCommand captured = captor.getValue();
        assertThat(captured.titleId()).isEqualTo(TITLE_ID);
        assertThat(captured.entryId()).isEqualTo(ENTRY_ID);
        assertThat(captured.sessionId()).isEqualTo(SESSION_ID);
        assertThat(captured.videoMediaAssetId()).isEqualTo(ASSET_ID);
        assertThat(captured.authenticatedActor()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("D1. SUPER_ADMIN with valid CSRF can reach guarded discard when owner")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_withValidCsrf_canReachGuardedDiscard_whenInitiator() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        ASSET_ID,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + ASSET_ID + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"));

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor = ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardScreenEntryVideoUploadUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(SUPER_ADMIN_EMAIL);
    }

    @Test
    @DisplayName("D2. SUPER_ADMIN with valid CSRF is denied when not session owner (no ownership override)")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_withValidCsrf_isDenied_whenNotInitiator_noOwnershipOverride() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoDiscardAccessDeniedException("Authenticated actor is not authorized to discard this video upload session."));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_DISCARD_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Authenticated actor is not authorized to discard this video upload session."));
    }

    @Test
    @DisplayName("E1. ADMIN without CSRF cannot mutate (redirects to /access-denied)")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withoutCsrf_cannotDiscard() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("E2. SUPER_ADMIN without CSRF cannot mutate (redirects to /access-denied)")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_withoutCsrf_cannotDiscard() throws Exception {
        DiscardVideoUploadSessionRequest req = new DiscardVideoUploadSessionRequest(ASSET_ID);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("F. Client cannot spoof authenticatedActor or initiatorPrincipalId")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void client_cannotSpoofAuthenticatedActorOrInitiatorPrincipalId() throws Exception {
        String spoofedPayload = "{\"videoMediaAssetId\":\"" + ASSET_ID + "\",\"authenticatedActor\":\"spoofed-actor\",\"initiatorPrincipalId\":\"spoofed-initiator\"}";

        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(ASSET_ID, DiscardOutcome.DISCARDED, "Discarded."));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .param("authenticatedActor", "spoofed-param-actor")
                        .param("initiatorPrincipalId", "spoofed-param-initiator")
                        .header("X-Authenticated-Actor", "spoofed-header-actor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofedPayload))
                .andExpect(status().isOk());

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor = ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardScreenEntryVideoUploadUseCase).execute(captor.capture());
        DiscardScreenEntryVideoUploadCommand captured = captor.getValue();
        assertThat(captured.authenticatedActor()).isEqualTo(ADMIN_EMAIL);
        assertThat(captured.authenticatedActor()).doesNotContain("spoofed");
    }

    @Test
    @DisplayName("G. Cross-owner access returns safe denial with no metadata disclosure")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void crossOwnerAccess_returnsSafeDenial_withNoMetadataDisclosure() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoDiscardAccessDeniedException("Authenticated actor is not authorized to discard this video upload session."));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_DISCARD_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Authenticated actor is not authorized to discard this video upload session."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("original-initiator"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(ADMIN_EMAIL))));
    }

    @Test
    @DisplayName("H. Referenced-video exception never exposes referencing IDs in JSON")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void referencedVideoConflict_neverExposesReferencingIdsInJson() throws Exception {
        UUID referencingEntry1 = UUID.randomUUID();
        UUID referencingEntry2 = UUID.randomUUID();
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoAlreadyReferencedException(ASSET_ID, List.of(referencingEntry1, referencingEntry2)));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_ALREADY_REFERENCED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(ASSET_ID.toString())))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(referencingEntry1.toString()))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(referencingEntry2.toString()))));
    }

    @Test
    @DisplayName("I. Valid ACTIVE discard response returns DISCARDED")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void validActiveDiscard_returnsDiscardedOutcomeResponse() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        ASSET_ID,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + ASSET_ID + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("successfully discarded")));
    }

    @Test
    @DisplayName("J. Authorized DELETED idempotent response returns ALREADY_DISCARDED")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void authorizedDeletedAsset_returnsAlreadyDiscardedOutcomeResponse() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        ASSET_ID,
                        DiscardOutcome.ALREADY_DISCARDED,
                        "Completed video upload asset [" + ASSET_ID + "] was already discarded."
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.videoMediaAssetId").value(ASSET_ID.toString()))
                .andExpect(jsonPath("$.status").value("ALREADY_DISCARDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already discarded")));
    }

    @Test
    @DisplayName("K1. Validation/not-found mapping: ScreenUploadSessionNotFoundException maps to 404")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_mapsScreenUploadSessionNotFoundException_to404() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenUploadSessionNotFoundException(SESSION_ID));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCREEN_UPLOAD_SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(SESSION_ID.toString())));
    }

    @Test
    @DisplayName("K2. Validation/not-found mapping: ScreenTitleNotFoundException maps to 404")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_mapsScreenTitleNotFoundException_to404() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenTitleNotFoundException(TITLE_ID));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCREEN_TITLE_NOT_FOUND"));
    }

    @Test
    @DisplayName("K3. Validation/not-found mapping: ScreenEntryNotFoundException maps to 404")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_mapsScreenEntryNotFoundException_to404() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenEntryNotFoundException(ENTRY_ID));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCREEN_ENTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("K4. Validation/not-found mapping: ScreenMediaAssetNotFoundException maps to 404")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_mapsScreenMediaAssetNotFoundException_to404() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenMediaAssetNotFoundException(ASSET_ID));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SCREEN_MEDIA_ASSET_NOT_FOUND"));
    }

    @Test
    @DisplayName("K5. Validation/conflict mapping: ScreenVideoDiscardValidationException maps to 400")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_mapsScreenVideoDiscardValidationException_to400() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoDiscardValidationException("UPLOAD_SESSION_NOT_COMPLETED", "Session not completed."));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UPLOAD_SESSION_NOT_COMPLETED"))
                .andExpect(jsonPath("$.message").value("[UPLOAD_SESSION_NOT_COMPLETED] Session not completed."));
    }

    @Test
    @DisplayName("K6. Validation/conflict mapping: InvalidScreenMediaAssetTypeException maps to 400")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_mapsInvalidScreenMediaAssetTypeException_to400() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new InvalidScreenMediaAssetTypeException(ASSET_ID, "VIDEO", "IMAGE"));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SCREEN_MEDIA_ASSET_TYPE"));
    }

    @Test
    @DisplayName("K7. Missing videoMediaAssetId in JSON request maps to 400 Bad Request")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsMissingVideoMediaAssetId_with400BadRequest() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("videoMediaAssetId không được để trống khi thực hiện hủy video đã upload."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("K8. Null videoMediaAssetId in JSON request maps to 400 Bad Request")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsNullVideoMediaAssetId_with400BadRequest() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("videoMediaAssetId không được để trống khi thực hiện hủy video đã upload."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("K9. Malformed JSON maps to 400 Bad Request with sanitized message")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsMalformedJson_with400BadRequest() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Dữ liệu yêu cầu không hợp lệ hoặc sai định dạng JSON."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("K10. Invalid body UUID format maps to 400 Bad Request with sanitized message")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsInvalidBodyUuid_with400BadRequest() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoMediaAssetId\":\"not-a-valid-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Dữ liệu yêu cầu không hợp lệ hoặc sai định dạng JSON."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("K11. Invalid path UUID format maps to 400 Bad Request with sanitized message")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsInvalidPathUuid_with400BadRequest() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        "invalid-uuid", ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Tham số đường dẫn hoặc tham số truy vấn không đúng định dạng."));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("K12. Unsupported content-type (non-JSON) maps to 415 Unsupported Media Type")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsUnsupportedMediaType_with415() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("videoMediaAssetId=" + ASSET_ID))
                .andExpect(status().isUnsupportedMediaType());

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("K13. Unsupported HTTP GET method maps to 405 Method Not Allowed")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void discard_rejectsGetMethod_with405() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf()))
                .andExpect(status().isMethodNotAllowed());

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("L1. Unexpected IllegalArgumentException produces sanitized 500 without leaking secrets")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void unexpectedIllegalArgumentException_producesSanitized500WithoutLeakingSecrets() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new IllegalArgumentException("INTERNAL_LEAK: db_pass=topsecret123 b2_key=k999 host=192.168.1.50"));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("topsecret123"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("k999"))));
    }

    @Test
    @DisplayName("L2. Unknown BaseApplicationException produces sanitized 500 without leaking secrets")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void unknownBaseApplicationException_producesSanitized500WithoutLeakingSecrets() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new BaseApplicationException("UNKNOWN_SCREEN_BUG", "INTERNAL_LEAK: root_pwd=superadmin999") {});

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("superadmin999"))));
    }

    @Test
    @DisplayName("L3. Unexpected IllegalStateException produces sanitized 500 without leaking internal messages")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void unexpectedIllegalStateException_producesSanitized500() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new IllegalStateException("CRITICAL_INTERNAL_DB_FAILURE: pass=secret-db-pass"));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("Đã xảy ra lỗi máy chủ không mong muốn trong quá trình xử lý video."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-db-pass"))));
    }

    @Test
    @DisplayName("M. Controller delegates exactly once and does not perform media deletion itself")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void controllerDelegatesExactlyOnce_andDoesNotPerformMediaDeletionItself() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(ASSET_ID, DiscardOutcome.DISCARDED, "Discarded."));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/upload-session/{sessionId}/discard",
                        TITLE_ID, ENTRY_ID, SESSION_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DiscardVideoUploadSessionRequest(ASSET_ID))))
                .andExpect(status().isOk());

        verify(discardScreenEntryVideoUploadUseCase, times(1)).execute(any(DiscardScreenEntryVideoUploadCommand.class));
        verify(mediaContract, never()).delete(any());
        verify(mediaContract, never()).abortUploadSession(any(), any());
    }

    @Test
    @DisplayName("Security: ADMIN role can execute video reconciliation")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canReconcileVideoVisibility() throws Exception {
        when(reconcileScreenEntryVideoVisibilityUseCase.execute(TITLE_ID, ENTRY_ID))
                .thenReturn(new ReconcileScreenVideoVisibilityResult(
                        TITLE_ID, ENTRY_ID, ASSET_ID, "PRIVATE", "PUBLIC",
                        ReconcileScreenVideoVisibilityResult.Outcome.RECONCILED, "Reconciled"
                ));

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", TITLE_ID, ENTRY_ID)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciledVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.outcome").value("RECONCILED"));

        verify(reconcileScreenEntryVideoVisibilityUseCase, times(1)).execute(TITLE_ID, ENTRY_ID);
    }

    @Test
    @DisplayName("Security: Regular USER role cannot execute video reconciliation (redirects to /access-denied)")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotReconcileVideoVisibility() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", TITLE_ID, ENTRY_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(reconcileScreenEntryVideoVisibilityUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Security: Anonymous user cannot execute video reconciliation (redirects to /login)")
    void anonymousUser_cannotReconcileVideoVisibility() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", TITLE_ID, ENTRY_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(reconcileScreenEntryVideoVisibilityUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Security: Missing CSRF token is rejected with redirect to /access-denied on video reconciliation")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void missingCsrf_rejectedOnVideoReconcile() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/reconcile", TITLE_ID, ENTRY_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(reconcileScreenEntryVideoVisibilityUseCase, never()).execute(any(), any());
    }
}
