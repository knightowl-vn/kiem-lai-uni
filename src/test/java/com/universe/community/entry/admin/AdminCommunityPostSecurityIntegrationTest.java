package com.universe.community.entry.admin;

import com.universe.configuration.SecurityBeanConfig;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.ports.CurrentUserQueryPort;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.identity.domain.UserRole;
import com.universe.identity.infrastructure.persistence.SpringDataUserJpaRepository;
import com.universe.identity.infrastructure.persistence.UserJpaEntity;
import com.universe.identity.infrastructure.security.AccountStatusFilter;
import com.universe.identity.infrastructure.security.CustomAuthenticationFailureHandler;
import com.universe.identity.infrastructure.security.GoogleOAuthSuccessHandler;
import com.universe.identity.infrastructure.security.SafeReturnToValidator;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.community.application.usecase.ApproveCommunityPostUseCase;
import com.universe.community.application.usecase.RejectCommunityPostUseCase;
import com.universe.community.application.usecase.RestoreCommunityPostUseCase;
import com.universe.community.entry.admin.dto.AdminCommunityPostHiddenPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostPendingPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportDetailDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostReportPageDTO;
import com.universe.community.entry.admin.dto.AdminCommunityPostUserDTO;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.interaction.domain.report.ReportReason;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        AdminCommunityPostReportQueueController.class,
        AdminCommunityPostReportDetailController.class,
        AdminCommunityPostReportModerationController.class,
        AdminCommunityPostPendingReviewController.class,
        AdminCommunityPostHiddenController.class
})
@Import({SecurityBeanConfig.class, AdminCommunityPostSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("Admin Community Post Real Spring Security Integration Tests (MS-07B8.5.3)")
class AdminCommunityPostSecurityIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private AdminCommunityPostReportCoordinator reportCoordinator;

    @MockBean
    private AdminCommunityPostReviewCoordinator reviewCoordinator;

    @MockBean
    private ResolveCommunityPostReportUseCase resolveReportUseCase;

    @MockBean
    private ApproveCommunityPostUseCase approveUseCase;

    @MockBean
    private RejectCommunityPostUseCase rejectUseCase;

    @MockBean
    private RestoreCommunityPostUseCase restoreUseCase;

    @BeforeEach
    void setUp() {
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(ADMIN_ID.toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setDisplayName("Admin User");
        adminEntity.setPublicHandle("admin_user");
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        UserJpaEntity regularUserEntity = new UserJpaEntity();
        regularUserEntity.setId(UUID.randomUUID().toString());
        regularUserEntity.setEmail(USER_EMAIL);
        regularUserEntity.setDisplayName("Regular User");
        regularUserEntity.setPublicHandle("regular_user");
        regularUserEntity.setStatus("ACTIVE");
        regularUserEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        when(springDataUserJpaRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(regularUserEntity));

        UserDTO adminUserDto = new UserDTO(ADMIN_ID, ADMIN_EMAIL, "Admin User", null, "admin_user", "ACTIVE", "ADMIN", Instant.now());
        when(authenticatedEmailResolver.require(any())).thenReturn(ADMIN_EMAIL);
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminUserDto));

        // Mock default page responses for coordinators
        when(reportCoordinator.getReportQueue(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostReportPageDTO(List.of(), 0, 20, 0L));
        when(reviewCoordinator.getPendingQueue(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostPendingPageDTO(List.of(), 0, 20, 0L));
        when(reviewCoordinator.getHiddenPosts(anyInt(), anyInt()))
                .thenReturn(new AdminCommunityPostHiddenPageDTO(List.of(), 0, 20, 0L));
        when(reportCoordinator.getReportDetail(TARGET_ID)).thenReturn(new AdminCommunityPostReportDetailDTO(
                TARGET_ID, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                ReportReason.SPAM, "Desc", "Snap", null, Instant.now(),
                ReportStatus.PENDING, UUID.randomUUID(), true, CommunityPostStatus.PUBLISHED,
                "Caption", null, AdminCommunityPostUserDTO.unresolved(UUID.randomUUID()),
                Instant.now(), Instant.now(), 1, null, null, null, List.of()
        ));
    }

    // =========================================================================
    // 1. REPORTS ROUTE FAMILY
    // =========================================================================

    @Test
    @DisplayName("GET /admin/community/reports — Admin allowed, User forbidden, Guest to login")
    void testReportsQueueSecurity() throws Exception {
        // Guest -> redirect to /login
        mockMvc.perform(get("/admin/community/reports"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User without ADMIN role -> redirect to /access-denied
        mockMvc.perform(get("/admin/community/reports").with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin -> 200 OK
        mockMvc.perform(get("/admin/community/reports").with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/community/reports/{id} — Admin allowed, User forbidden, Guest to login")
    void testReportDetailSecurity() throws Exception {
        // Guest -> /login
        mockMvc.perform(get("/admin/community/reports/{id}", TARGET_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User -> /access-denied
        mockMvc.perform(get("/admin/community/reports/{id}", TARGET_ID).with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin -> 200 OK
        mockMvc.perform(get("/admin/community/reports/{id}", TARGET_ID).with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/community/reports/{id}/resolve — Admin with CSRF allowed, missing CSRF denied, User forbidden, Guest to login")
    void testReportResolveSecurity() throws Exception {
        // Guest -> /login
        mockMvc.perform(post("/admin/community/reports/{id}/resolve", TARGET_ID).with(csrf()).param("action", "NO_ACTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User -> /access-denied
        mockMvc.perform(post("/admin/community/reports/{id}/resolve", TARGET_ID).with(csrf())
                        .with(user(USER_EMAIL).roles("USER"))
                        .param("action", "NO_ACTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin without CSRF -> /access-denied
        mockMvc.perform(post("/admin/community/reports/{id}/resolve", TARGET_ID)
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .param("action", "NO_ACTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin with CSRF -> allowed (redirects to report detail)
        mockMvc.perform(post("/admin/community/reports/{id}/resolve", TARGET_ID).with(csrf())
                        .with(user(ADMIN_EMAIL).roles("ADMIN"))
                        .param("action", "NO_ACTION"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/reports/" + TARGET_ID));
    }

    // =========================================================================
    // 2. PENDING ROUTE FAMILY
    // =========================================================================

    @Test
    @DisplayName("GET /admin/community/posts/pending — Admin allowed, User forbidden, Guest to login")
    void testPendingQueueSecurity() throws Exception {
        // Guest
        mockMvc.perform(get("/admin/community/posts/pending"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User
        mockMvc.perform(get("/admin/community/posts/pending").with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin
        mockMvc.perform(get("/admin/community/posts/pending").with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/community/posts/{id}/approve — Admin with CSRF allowed, missing CSRF denied, User forbidden, Guest to login")
    void testPendingApproveSecurity() throws Exception {
        // Guest
        mockMvc.perform(post("/admin/community/posts/{id}/approve", TARGET_ID).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User
        mockMvc.perform(post("/admin/community/posts/{id}/approve", TARGET_ID).with(csrf())
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin without CSRF
        mockMvc.perform(post("/admin/community/posts/{id}/approve", TARGET_ID)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin with CSRF
        mockMvc.perform(post("/admin/community/posts/{id}/approve", TARGET_ID).with(csrf())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/posts/pending"));
    }

    @Test
    @DisplayName("POST /admin/community/posts/{id}/reject — Admin with CSRF allowed, missing CSRF denied, User forbidden, Guest to login")
    void testPendingRejectSecurity() throws Exception {
        // Guest
        mockMvc.perform(post("/admin/community/posts/{id}/reject", TARGET_ID).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User
        mockMvc.perform(post("/admin/community/posts/{id}/reject", TARGET_ID).with(csrf())
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin without CSRF
        mockMvc.perform(post("/admin/community/posts/{id}/reject", TARGET_ID)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin with CSRF
        mockMvc.perform(post("/admin/community/posts/{id}/reject", TARGET_ID).with(csrf())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/posts/pending"));
    }

    // =========================================================================
    // 3. HIDDEN ROUTE FAMILY
    // =========================================================================

    @Test
    @DisplayName("GET /admin/community/posts/hidden — Admin allowed, User forbidden, Guest to login")
    void testHiddenQueueSecurity() throws Exception {
        // Guest
        mockMvc.perform(get("/admin/community/posts/hidden"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User
        mockMvc.perform(get("/admin/community/posts/hidden").with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin
        mockMvc.perform(get("/admin/community/posts/hidden").with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/community/posts/{id}/restore — Admin with CSRF allowed, missing CSRF denied, User forbidden, Guest to login")
    void testHiddenRestoreSecurity() throws Exception {
        // Guest
        mockMvc.perform(post("/admin/community/posts/{id}/restore", TARGET_ID).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        // User
        mockMvc.perform(post("/admin/community/posts/{id}/restore", TARGET_ID).with(csrf())
                        .with(user(USER_EMAIL).roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin without CSRF
        mockMvc.perform(post("/admin/community/posts/{id}/restore", TARGET_ID)
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        // Admin with CSRF
        mockMvc.perform(post("/admin/community/posts/{id}/restore", TARGET_ID).with(csrf())
                        .with(user(ADMIN_EMAIL).roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/community/posts/hidden"));
    }
}
