package com.universe.screen.entry.admin;

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
import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.usecase.FindRecoverableScreenVideoUploadsUseCase;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
