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
import com.universe.screen.application.commands.CreateScreenTitleCommand;
import com.universe.screen.application.commands.UpdateScreenTitleCommand;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.ports.ScreenImageMediaPort;
import com.universe.screen.application.usecase.ArchiveScreenTitleUseCase;
import com.universe.screen.application.usecase.CreateScreenTitleUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleListUseCase;
import com.universe.screen.application.usecase.PublishScreenTitleUseCase;
import com.universe.screen.application.usecase.UpdateScreenTitleUseCase;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
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

@WebMvcTest({AdminScreenTitlePageController.class, AdminScreenTitleCommandController.class})
@Import({SecurityBeanConfig.class, AdminScreenTitleSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenTitleSecurityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String SUPER_ADMIN_EMAIL = "superadmin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";
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
    private GetScreenTitleListUseCase getScreenTitleListUseCase;

    @MockBean
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @MockBean
    private CreateScreenTitleUseCase createScreenTitleUseCase;

    @MockBean
    private UpdateScreenTitleUseCase updateScreenTitleUseCase;

    @MockBean
    private PublishScreenTitleUseCase publishScreenTitleUseCase;

    @MockBean
    private ArchiveScreenTitleUseCase archiveScreenTitleUseCase;

    @MockBean
    private ScreenImageMediaPort screenImageMediaPort;

    private ScreenTitleDTO sampleTitle;

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

        sampleTitle = new ScreenTitleDTO(
                TITLE_ID,
                "kiem-lai",
                "Kiếm Lai",
                null,
                "Synopsis",
                ScreenStructure.SERIES,
                ScreenPresentation.ANIMATION_3D,
                null,
                null,
                ScreenPublicationStatus.DRAFT,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Security: Anonymous GET /admin/screen/titles redirects to login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/admin/screen/titles"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER GET /admin/screen/titles is redirected to /access-denied")
    void shouldDenyNonAdminUser() throws Exception {
        mockMvc.perform(get("/admin/screen/titles"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN GET /admin/screen/titles returns 200 OK")
    void shouldAllowAdminUser() throws Exception {
        when(getScreenTitleListUseCase.execute()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/titles"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    @DisplayName("Security: SUPER_ADMIN GET /admin/screen/titles returns 200 OK")
    void shouldAllowSuperAdminUser() throws Exception {
        when(getScreenTitleListUseCase.execute()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/titles"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Security: Anonymous POST /admin/screen/titles redirects to login")
    void shouldRedirectAnonymousPostToLogin() throws Exception {
        mockMvc.perform(post("/admin/screen/titles")
                        .with(csrf())
                        .param("title", "Kiếm Lai")
                        .param("slug", "kiem-lai")
                        .param("structure", "SERIES")
                        .param("presentation", "ANIMATION_3D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER POST /admin/screen/titles is redirected to /access-denied")
    void shouldDenyNonAdminUserPost() throws Exception {
        mockMvc.perform(post("/admin/screen/titles")
                        .with(csrf())
                        .param("title", "Kiếm Lai")
                        .param("slug", "kiem-lai")
                        .param("structure", "SERIES")
                        .param("presentation", "ANIMATION_3D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST /admin/screen/titles WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectPostWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles")
                        .param("title", "Kiếm Lai")
                        .param("slug", "kiem-lai")
                        .param("structure", "SERIES")
                        .param("presentation", "ANIMATION_3D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST /admin/screen/titles WITH CSRF succeeds and redirects")
    void shouldAllowPostWithCsrf() throws Exception {
        when(createScreenTitleUseCase.execute(any(CreateScreenTitleCommand.class))).thenReturn(sampleTitle);

        mockMvc.perform(post("/admin/screen/titles")
                        .with(csrf())
                        .param("title", "Kiếm Lai")
                        .param("slug", "kiem-lai")
                        .param("structure", "SERIES")
                        .param("presentation", "ANIMATION_3D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST /admin/screen/titles/{id}/update WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectUpdateWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{id}/update", TITLE_ID)
                        .param("title", "Kiếm Lai Cập Nhật")
                        .param("slug", "kiem-lai-cap-nhat")
                        .param("structure", "SERIES")
                        .param("presentation", "ANIMATION_3D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST /admin/screen/titles/{id}/update WITH CSRF succeeds and redirects")
    void shouldAllowUpdateWithCsrf() throws Exception {
        when(updateScreenTitleUseCase.execute(any(UpdateScreenTitleCommand.class))).thenReturn(sampleTitle);

        mockMvc.perform(post("/admin/screen/titles/{id}/update", TITLE_ID)
                        .with(csrf())
                        .param("title", "Kiếm Lai Cập Nhật")
                        .param("slug", "kiem-lai-cap-nhat")
                        .param("structure", "SERIES")
                        .param("presentation", "ANIMATION_3D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST publish WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectPublishWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{id}/publish", TITLE_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST publish WITH CSRF succeeds and redirects")
    void shouldAllowPublishWithCsrf() throws Exception {
        when(publishScreenTitleUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);

        mockMvc.perform(post("/admin/screen/titles/{id}/publish", TITLE_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST archive WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectArchiveWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{id}/archive", TITLE_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST archive WITH CSRF succeeds and redirects")
    void shouldAllowArchiveWithCsrf() throws Exception {
        when(archiveScreenTitleUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);

        mockMvc.perform(post("/admin/screen/titles/{id}/archive", TITLE_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID));
    }
}
