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
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult.DiscardOutcome;
import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.usecase.DiscardScreenEntryVideoUploadUseCase;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
import com.universe.screen.entry.admin.dto.DiscardVideoUploadSessionRequest;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminScreenVideoRecoveryController.class)
@Import({SecurityBeanConfig.class, AdminScreenVideoRecoverySecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("AdminScreenVideoRecoverySecurityIntegrationTest - Real Filter Chain Security")
class AdminScreenVideoRecoverySecurityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String SUPER_ADMIN_EMAIL = "superadmin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";

    private final UUID sessionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private FindRecoverableScreenVideoUploadsUseCase findRecoverableScreenVideoUploadsUseCase;

    @MockBean
    private DiscardScreenEntryVideoUploadUseCase discardScreenEntryVideoUploadUseCase;

    @BeforeEach
    void setUp() {
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
    }

    // =========================================================================
    // GET /admin/screen/video-uploads/recoverable SECURITY TESTS
    // =========================================================================

    @Test
    @DisplayName("Security: Unauthenticated guest is denied and redirected to login")
    void guest_cannotAccessRecoverableUploads() throws Exception {
        mockMvc.perform(get("/admin/screen/video-uploads/recoverable"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @DisplayName("Security: Authenticated normal USER is denied and redirected to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotAccessRecoverableUploads() throws Exception {
        mockMvc.perform(get("/admin/screen/video-uploads/recoverable"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("Security: ADMIN role is allowed and authenticated actor identity determines query ownership")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_canAccessRecoverableUploads() throws Exception {
        when(findRecoverableScreenVideoUploadsUseCase.execute(any(FindRecoverableScreenVideoUploadsQuery.class)))
                .thenReturn(RecoverableVideoUploadPageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/screen/video-uploads/recoverable"))
                .andExpect(status().isOk());

        ArgumentCaptor<FindRecoverableScreenVideoUploadsQuery> captor =
                ArgumentCaptor.forClass(FindRecoverableScreenVideoUploadsQuery.class);
        verify(findRecoverableScreenVideoUploadsUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Security: SUPER_ADMIN role is allowed but strictly restricted to their own upload sessions")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_isRestrictedToOwnUploadSessions() throws Exception {
        when(findRecoverableScreenVideoUploadsUseCase.execute(any(FindRecoverableScreenVideoUploadsQuery.class)))
                .thenReturn(RecoverableVideoUploadPageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/screen/video-uploads/recoverable"))
                .andExpect(status().isOk());

        ArgumentCaptor<FindRecoverableScreenVideoUploadsQuery> captor =
                ArgumentCaptor.forClass(FindRecoverableScreenVideoUploadsQuery.class);
        verify(findRecoverableScreenVideoUploadsUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(SUPER_ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Security: Client-provided actor query parameters cannot override authenticated server identity")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void clientProvidedActorParam_cannotOverrideAuthenticatedActor() throws Exception {
        when(findRecoverableScreenVideoUploadsUseCase.execute(any(FindRecoverableScreenVideoUploadsQuery.class)))
                .thenReturn(RecoverableVideoUploadPageDTO.empty(0, 20));

        mockMvc.perform(get("/admin/screen/video-uploads/recoverable")
                        .param("authenticatedActor", "malicious-attacker")
                        .param("initiatorPrincipalId", "malicious-attacker")
                        .param("actor", "malicious-attacker"))
                .andExpect(status().isOk());

        ArgumentCaptor<FindRecoverableScreenVideoUploadsQuery> captor =
                ArgumentCaptor.forClass(FindRecoverableScreenVideoUploadsQuery.class);
        verify(findRecoverableScreenVideoUploadsUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(ADMIN_EMAIL);
    }

    // =========================================================================
    // POST /admin/screen/video-uploads/{sessionId}/discard SECURITY TESTS (S3-3B2)
    // =========================================================================

    @Test
    @DisplayName("Security POST Discard: Unauthenticated guest cannot mutate and redirects to login")
    void guest_cannotDiscardRecoverableUpload() throws Exception {
        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Security POST Discard: Authenticated normal USER cannot mutate and redirects to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void regularUser_cannotDiscardRecoverableUpload() throws Exception {
        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Security POST Discard: ADMIN without CSRF cannot mutate and redirects to /access-denied")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withoutCsrf_cannotDiscard() throws Exception {
        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Security POST Discard: SUPER_ADMIN without CSRF cannot mutate and redirects to /access-denied")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_withoutCsrf_cannotDiscard() throws Exception {
        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(discardScreenEntryVideoUploadUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Security POST Discard: ADMIN with valid CSRF can reach discard and server derives actor")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void adminUser_withCsrf_canDiscard() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + assetId + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"));

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor =
                ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardScreenEntryVideoUploadUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Security POST Discard: SUPER_ADMIN with valid CSRF can reach discard and server derives actor (no bypass)")
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    void superAdminUser_withCsrf_canDiscard_restrictedToOwnUploadSessions() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.DISCARDED,
                        "Completed video upload asset [" + assetId + "] successfully discarded."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.status").value("DISCARDED"));

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor =
                ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardScreenEntryVideoUploadUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(SUPER_ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Security POST Discard: Client-provided actor params or headers cannot override server identity")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void clientProvidedActorParam_cannotOverrideAuthenticatedActor_onDiscard() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenReturn(new DiscardScreenEntryVideoUploadResult(
                        assetId,
                        DiscardOutcome.DISCARDED,
                        "Discarded."
                ));

        String spoofedPayload = "{\"videoMediaAssetId\":\"" + assetId + "\",\"authenticatedActor\":\"spoofed-actor\"}";

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf())
                        .param("authenticatedActor", "malicious-attacker")
                        .param("actor", "malicious-attacker")
                        .header("X-Authenticated-Actor", "malicious-attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofedPayload))
                .andExpect(status().isOk());

        ArgumentCaptor<DiscardScreenEntryVideoUploadCommand> captor =
                ArgumentCaptor.forClass(DiscardScreenEntryVideoUploadCommand.class);
        verify(discardScreenEntryVideoUploadUseCase).execute(captor.capture());
        assertThat(captor.getValue().authenticatedActor()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Security POST Discard: Cross-owner access returns safe 403 denial with no metadata disclosure")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void crossOwnerAccess_returnsSafeDenial_withNoMetadataDisclosure_onDiscard() throws Exception {
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoDiscardAccessDeniedException(
                        "Authenticated actor is not authorized to discard this video upload session."
                ));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_DISCARD_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Authenticated actor is not authorized to discard this video upload session."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("original-initiator"))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(ADMIN_EMAIL))));
    }

    @Test
    @DisplayName("Security POST Discard: Referenced-video conflict returns 409 and never exposes referencing IDs in JSON")
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    void referencedVideoConflict_neverExposesReferencingIdsInJson_onDiscard() throws Exception {
        UUID referencingEntry1 = UUID.randomUUID();
        UUID referencingEntry2 = UUID.randomUUID();
        when(discardScreenEntryVideoUploadUseCase.execute(any(DiscardScreenEntryVideoUploadCommand.class)))
                .thenThrow(new ScreenVideoAlreadyReferencedException(assetId, List.of(referencingEntry1, referencingEntry2)));

        mockMvc.perform(post("/admin/screen/video-uploads/{sessionId}/discard", sessionId)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SCREEN_VIDEO_ALREADY_REFERENCED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(assetId.toString())))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(referencingEntry1.toString()))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(referencingEntry2.toString()))));
    }
}
