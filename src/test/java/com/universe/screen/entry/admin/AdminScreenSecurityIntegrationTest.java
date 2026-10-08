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
import com.universe.screen.application.commands.AddScreenFranchiseMemberCommand;
import com.universe.screen.application.commands.CreateScreenFranchiseCommand;
import com.universe.screen.application.commands.RemoveScreenFranchiseMemberCommand;
import com.universe.screen.application.commands.UpdateScreenFranchiseCommand;
import com.universe.screen.application.commands.UpdateScreenFranchiseMemberOrderCommand;
import com.universe.screen.application.dto.ScreenFranchiseDTO;
import com.universe.screen.application.dto.ScreenFranchiseMemberDTO;
import com.universe.screen.application.usecase.AddScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.CreateScreenFranchiseUseCase;
import com.universe.screen.application.usecase.GetAvailableScreenTitlesUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseDetailUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseListUseCase;
import com.universe.screen.application.usecase.GetScreenFranchiseMembersUseCase;
import com.universe.screen.application.usecase.RemoveScreenFranchiseMemberUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseMemberOrderUseCase;
import com.universe.screen.application.usecase.UpdateScreenFranchiseUseCase;
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

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminScreenFranchisePageController.class, AdminScreenFranchiseCommandController.class})
@Import({SecurityBeanConfig.class, AdminScreenSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenSecurityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String SUPER_ADMIN_EMAIL = "superadmin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";
    private static final UUID FRANCHISE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TITLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private GetScreenFranchiseListUseCase getScreenFranchiseListUseCase;

    @MockBean
    private GetScreenFranchiseDetailUseCase getScreenFranchiseDetailUseCase;

    @MockBean
    private GetScreenFranchiseMembersUseCase getScreenFranchiseMembersUseCase;

    @MockBean
    private GetAvailableScreenTitlesUseCase getAvailableScreenTitlesUseCase;

    @MockBean
    private CreateScreenFranchiseUseCase createScreenFranchiseUseCase;

    @MockBean
    private UpdateScreenFranchiseUseCase updateScreenFranchiseUseCase;

    @MockBean
    private AddScreenFranchiseMemberUseCase addScreenFranchiseMemberUseCase;

    @MockBean
    private UpdateScreenFranchiseMemberOrderUseCase updateScreenFranchiseMemberOrderUseCase;

    @MockBean
    private RemoveScreenFranchiseMemberUseCase removeScreenFranchiseMemberUseCase;

    @BeforeEach
    void setUp() {
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(UUID.randomUUID().toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setDisplayName("Admin User");
        adminEntity.setPublicHandle("admin_user");
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        UserJpaEntity superAdminEntity = new UserJpaEntity();
        superAdminEntity.setId(UUID.randomUUID().toString());
        superAdminEntity.setEmail(SUPER_ADMIN_EMAIL);
        superAdminEntity.setDisplayName("Super Admin");
        superAdminEntity.setPublicHandle("super_admin");
        superAdminEntity.setStatus("ACTIVE");
        superAdminEntity.setRole(UserRole.SUPER_ADMIN);

        UserJpaEntity regularUserEntity = new UserJpaEntity();
        regularUserEntity.setId(UUID.randomUUID().toString());
        regularUserEntity.setEmail(USER_EMAIL);
        regularUserEntity.setDisplayName("Regular User");
        regularUserEntity.setPublicHandle("regular_user");
        regularUserEntity.setStatus("ACTIVE");
        regularUserEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        when(springDataUserJpaRepository.findByEmail(SUPER_ADMIN_EMAIL)).thenReturn(Optional.of(superAdminEntity));
        when(springDataUserJpaRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(regularUserEntity));
    }

    @Test
    @DisplayName("Security: Anonymous GET /admin/screen/franchises redirects to login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/admin/screen/franchises"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER GET /admin/screen/franchises is redirected to /access-denied")
    void shouldDenyNonAdminUser() throws Exception {
        mockMvc.perform(get("/admin/screen/franchises"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN GET /admin/screen/franchises returns 200 OK")
    void shouldAllowAdminUser() throws Exception {
        when(getScreenFranchiseListUseCase.execute()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/franchises"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    @DisplayName("Security: SUPER_ADMIN GET /admin/screen/franchises returns 200 OK")
    void shouldAllowSuperAdminUser() throws Exception {
        when(getScreenFranchiseListUseCase.execute()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/franchises"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Security: Anonymous POST /admin/screen/franchises redirects to login")
    void shouldRedirectAnonymousPostToLogin() throws Exception {
        mockMvc.perform(post("/admin/screen/franchises")
                        .with(csrf())
                        .param("name", "Kiếm Lai")
                        .param("slug", "kiem-lai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER POST /admin/screen/franchises is redirected to /access-denied")
    void shouldDenyNonAdminUserPost() throws Exception {
        mockMvc.perform(post("/admin/screen/franchises")
                        .with(csrf())
                        .param("name", "Kiếm Lai")
                        .param("slug", "kiem-lai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST /admin/screen/franchises WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectPostWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/franchises")
                        .param("name", "Kiếm Lai")
                        .param("slug", "kiem-lai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST /admin/screen/franchises WITH CSRF succeeds and redirects")
    void shouldAllowPostWithCsrf() throws Exception {
        ScreenFranchiseDTO created = new ScreenFranchiseDTO(FRANCHISE_ID, "Kiếm Lai", "kiem-lai", 0);
        when(createScreenFranchiseUseCase.execute(any(CreateScreenFranchiseCommand.class))).thenReturn(created);

        mockMvc.perform(post("/admin/screen/franchises")
                        .with(csrf())
                        .param("name", "Kiếm Lai")
                        .param("slug", "kiem-lai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/franchises/" + FRANCHISE_ID + "/memberships"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST membership WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectMembershipPostWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/franchises/{id}/memberships", FRANCHISE_ID)
                        .param("titleId", TITLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST membership WITH CSRF succeeds and redirects")
    void shouldAllowMembershipPostWithCsrf() throws Exception {
        ScreenFranchiseMemberDTO member = new ScreenFranchiseMemberDTO(
                UUID.randomUUID(), FRANCHISE_ID, TITLE_ID, "Title", "title", 0, null, null, null
        );
        when(addScreenFranchiseMemberUseCase.execute(any(AddScreenFranchiseMemberCommand.class))).thenReturn(member);

        mockMvc.perform(post("/admin/screen/franchises/{id}/memberships", FRANCHISE_ID)
                        .with(csrf())
                        .param("titleId", TITLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/franchises/" + FRANCHISE_ID + "/memberships"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST update franchise WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectUpdateFranchiseWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/franchises/{id}/update", FRANCHISE_ID)
                        .param("name", "Kiếm Lai Cập Nhật")
                        .param("slug", "kiem-lai-cap-nhat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST update franchise WITH CSRF succeeds and redirects")
    void shouldAllowUpdateFranchiseWithCsrf() throws Exception {
        ScreenFranchiseDTO updated = new ScreenFranchiseDTO(FRANCHISE_ID, "Kiếm Lai Cập Nhật", "kiem-lai-cap-nhat", 1);
        when(updateScreenFranchiseUseCase.execute(any(UpdateScreenFranchiseCommand.class))).thenReturn(updated);

        mockMvc.perform(post("/admin/screen/franchises/{id}/update", FRANCHISE_ID)
                        .with(csrf())
                        .param("name", "Kiếm Lai Cập Nhật")
                        .param("slug", "kiem-lai-cap-nhat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/franchises/" + FRANCHISE_ID + "/memberships"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST member order WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectMemberOrderWithoutCsrf() throws Exception {
        UUID membershipId = UUID.randomUUID();
        mockMvc.perform(post("/admin/screen/franchises/{fid}/memberships/{mid}/order", FRANCHISE_ID, membershipId)
                        .param("displayOrder", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST member order WITH CSRF succeeds and redirects")
    void shouldAllowMemberOrderWithCsrf() throws Exception {
        UUID membershipId = UUID.randomUUID();
        ScreenFranchiseMemberDTO member = new ScreenFranchiseMemberDTO(
                membershipId, FRANCHISE_ID, TITLE_ID, "Title", "title", 5, null, null, null
        );
        when(updateScreenFranchiseMemberOrderUseCase.execute(any(UpdateScreenFranchiseMemberOrderCommand.class)))
                .thenReturn(member);

        mockMvc.perform(post("/admin/screen/franchises/{fid}/memberships/{mid}/order", FRANCHISE_ID, membershipId)
                        .with(csrf())
                        .param("displayOrder", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/franchises/" + FRANCHISE_ID + "/memberships"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST remove member WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectRemoveMemberWithoutCsrf() throws Exception {
        UUID membershipId = UUID.randomUUID();
        mockMvc.perform(post("/admin/screen/franchises/{fid}/memberships/{mid}/remove", FRANCHISE_ID, membershipId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST remove member WITH CSRF succeeds and redirects")
    void shouldAllowRemoveMemberWithCsrf() throws Exception {
        UUID membershipId = UUID.randomUUID();

        mockMvc.perform(post("/admin/screen/franchises/{fid}/memberships/{mid}/remove", FRANCHISE_ID, membershipId)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/franchises/" + FRANCHISE_ID + "/memberships"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER POST update franchise is redirected to /access-denied")
    void shouldDenyUserFromUpdateFranchise() throws Exception {
        mockMvc.perform(post("/admin/screen/franchises/{id}/update", FRANCHISE_ID)
                        .with(csrf())
                        .param("name", "Kiếm Lai Cập Nhật")
                        .param("slug", "kiem-lai-cap-nhat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER POST remove member is redirected to /access-denied")
    void shouldDenyUserFromRemoveMember() throws Exception {
        UUID membershipId = UUID.randomUUID();
        mockMvc.perform(post("/admin/screen/franchises/{fid}/memberships/{mid}/remove", FRANCHISE_ID, membershipId)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }
}
