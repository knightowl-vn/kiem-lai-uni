package com.universe.community.entry.admin;

import com.universe.community.application.command.UpdateCommunitySettingsCommand;
import com.universe.community.application.usecase.GetCommunitySettingsUseCase;
import com.universe.community.application.usecase.UpdateCommunitySettingsUseCase;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.community.domain.exception.CommunitySettingsOptimisticLockException;
import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.infrastructure.security.SafeReturnToValidator;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminCommunitySettingsController.class)
@Import({SecurityBeanConfig.class, AdminCommunitySettingsControllerTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("AdminCommunitySettingsController WebMvc Tests")
class AdminCommunitySettingsControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ADMIN_EMAIL = "admin@universe.local";

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
    private UserDetailsService userDetailsService;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private GetCommunitySettingsUseCase getSettingsUseCase;

    @MockBean
    private UpdateCommunitySettingsUseCase updateSettingsUseCase;

    @BeforeEach
    void setUp() {
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(ADMIN_ID.toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setDisplayName("Admin User");
        adminEntity.setPublicHandle("admin_user");
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        UserDTO adminUserDto = new UserDTO(ADMIN_ID, ADMIN_EMAIL, "Admin User", null, "admin_user", "ACTIVE", "ADMIN", Instant.now());
        when(authenticatedEmailResolver.require(any())).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminUserDto));
    }

    @Test
    @DisplayName("GET /admin/community/settings: Unauthenticated user is redirected to login")
    void getSettingsUnauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin/community/settings"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("GET /admin/community/settings: Non-admin user is redirected to /access-denied")
    void getSettingsNonAdminForbidden() throws Exception {
        UserJpaEntity userEntity = new UserJpaEntity();
        userEntity.setId(UUID.randomUUID().toString());
        userEntity.setEmail("user@universe.local");
        userEntity.setDisplayName("Normal User");
        userEntity.setPublicHandle("normal_user");
        userEntity.setStatus("ACTIVE");
        userEntity.setRole(UserRole.USER);
        when(springDataUserJpaRepository.findByEmail("user@universe.local")).thenReturn(Optional.of(userEntity));

        mockMvc.perform(get("/admin/community/settings")
                        .with(user("user@universe.local").roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("GET /admin/community/settings: Admin renders settings page with current mode and tabs")
    void getSettingsAdminRendersPage() throws Exception {
        CommunitySettings settings = CommunitySettings.defaultSettings();
        when(getSettingsUseCase.execute()).thenReturn(settings);

        mockMvc.perform(get("/admin/community/settings")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/community/settings"))
                .andExpect(content().string(containsString("Quản lý cộng đồng")))
                .andExpect(content().string(containsString("Cấu hình xuất bản bài viết")))
                .andExpect(content().string(containsString("Cài đặt")))
                .andExpect(content().string(containsString("AUTO_PUBLISH")))
                .andExpect(content().string(containsString("PRE_MODERATION")))
                .andExpect(content().string(containsString("community-workspace-tabs")));
    }

    @Test
    @DisplayName("GET /admin/community/settings: Resolves updater profile when updatedByUserId is non-zero")
    void getSettingsResolvesUpdaterProfile() throws Exception {
        UUID updaterId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        CommunitySettings settings = CommunitySettings.of(
                CommunityPublicationMode.PRE_MODERATION,
                3L,
                Instant.parse("2026-10-04T12:00:00Z"),
                updaterId
        );
        when(getSettingsUseCase.execute()).thenReturn(settings);
        when(userIdentityContract.findPublicProfilesByIds(Set.of(updaterId)))
                .thenReturn(Map.of(updaterId, new UserPublicProfileDTO(updaterId, "Super Mod", "/avatars/mod.png", "super_mod")));

        mockMvc.perform(get("/admin/community/settings")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Super Mod")))
                .andExpect(content().string(containsString("@super_mod")));
    }

    @Test
    @DisplayName("POST /admin/community/settings: Unauthenticated user is redirected to login")
    void postSettingsUnauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/community/settings")
                        .with(csrf())
                        .param("publicationMode", "PRE_MODERATION")
                        .param("version", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @DisplayName("POST /admin/community/settings: Non-admin user is redirected to /access-denied")
    void postSettingsNonAdminForbidden() throws Exception {
        UserJpaEntity userEntity = new UserJpaEntity();
        userEntity.setId(UUID.randomUUID().toString());
        userEntity.setEmail("user@universe.local");
        userEntity.setDisplayName("Normal User");
        userEntity.setPublicHandle("normal_user");
        userEntity.setStatus("ACTIVE");
        userEntity.setRole(UserRole.USER);
        when(springDataUserJpaRepository.findByEmail("user@universe.local")).thenReturn(Optional.of(userEntity));

        mockMvc.perform(post("/admin/community/settings")
                        .with(user("user@universe.local").roles("USER"))
                        .with(csrf())
                        .param("publicationMode", "PRE_MODERATION")
                        .param("version", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("POST /admin/community/settings: Rejects request missing CSRF token and redirects to /access-denied")
    void postSettingsMissingCsrfForbidden() throws Exception {
        mockMvc.perform(post("/admin/community/settings")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .param("publicationMode", "PRE_MODERATION")
                        .param("version", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @DisplayName("POST /admin/community/settings: Successfully updates mode and redirects with success flash")
    void postSettingsSuccess() throws Exception {
        mockMvc.perform(post("/admin/community/settings")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf())
                        .param("publicationMode", "PRE_MODERATION")
                        .param("version", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/settings"))
                .andExpect(flash().attribute("successMessage", "Cập nhật cài đặt cộng đồng thành công."));

        verify(updateSettingsUseCase).execute(new UpdateCommunitySettingsCommand(
                CommunityPublicationMode.PRE_MODERATION,
                2L,
                ADMIN_ID
        ));
    }

    @Test
    @DisplayName("POST /admin/community/settings: Invalid mode parameter redirects with error flash")
    void postSettingsInvalidMode() throws Exception {
        mockMvc.perform(post("/admin/community/settings")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf())
                        .param("publicationMode", "UNKNOWN_MODE")
                        .param("version", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/settings"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @DisplayName("POST /admin/community/settings: Optimistic lock conflict redirects with specific error flash")
    void postSettingsOptimisticLockConflict() throws Exception {
        doThrow(new CommunitySettingsOptimisticLockException(0L))
                .when(updateSettingsUseCase).execute(any());

        mockMvc.perform(post("/admin/community/settings")
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .with(csrf())
                        .param("publicationMode", "PRE_MODERATION")
                        .param("version", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/settings"))
                .andExpect(flash().attribute("errorMessage", "Cài đặt đã được người khác cập nhật trước đó. Vui lòng tải lại trang."));
    }
}
