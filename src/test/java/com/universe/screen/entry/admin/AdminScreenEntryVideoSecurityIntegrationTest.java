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
import com.universe.shared.security.AuthenticatedEmailResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private MediaContract mediaContract;

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
                .andExpect(jsonPath("$.playbackUrl").value("/media/assets/" + ASSET_ID + "/content"));

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
}
