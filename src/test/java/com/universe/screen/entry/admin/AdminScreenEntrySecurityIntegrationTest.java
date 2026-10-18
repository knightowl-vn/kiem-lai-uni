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
import com.universe.screen.application.commands.CreateScreenEntryCommand;
import com.universe.screen.application.commands.UpdateScreenEntryCommand;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.CreateScreenEntryUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenEntryListUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.UpdateScreenEntryUseCase;
import com.universe.screen.domain.ScreenEntryType;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AdminScreenEntryPageController.class, AdminScreenEntryCommandController.class})
@Import({SecurityBeanConfig.class, AdminScreenEntrySecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminScreenEntrySecurityIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String SUPER_ADMIN_EMAIL = "superadmin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";
    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @MockBean
    private GetScreenEntryListUseCase getScreenEntryListUseCase;

    @MockBean
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    @MockBean
    private CreateScreenEntryUseCase createScreenEntryUseCase;

    @MockBean
    private UpdateScreenEntryUseCase updateScreenEntryUseCase;

    @MockBean
    private PublishScreenEntryUseCase publishScreenEntryUseCase;

    @MockBean
    private ArchiveScreenEntryUseCase archiveScreenEntryUseCase;

    @MockBean
    private DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;

    private ScreenTitleDTO sampleTitle;
    private ScreenEntryDTO sampleEntry;

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
                TITLE_ID, "kiem-lai", "Kiếm Lai", null, "Synopsis",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D,
                null, null, ScreenPublicationStatus.DRAFT, null, Instant.now(), Instant.now()
        );

        sampleEntry = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "ep-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Synopsis", null, null, 1200L, ScreenPublicationStatus.DRAFT,
                null, Instant.now(), Instant.now()
        );
    }

    // --- GET ACCESS CHECKS ---

    @Test
    @DisplayName("Security: Anonymous GET /admin/screen/titles/{titleId}/entries redirects to login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries", TITLE_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER GET entries is redirected to /access-denied")
    void shouldDenyNonAdminUser() throws Exception {
        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries", TITLE_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN GET entries returns 200 OK")
    void shouldAllowAdminUser() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryListUseCase.execute(TITLE_ID)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries", TITLE_ID))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = SUPER_ADMIN_EMAIL, roles = {"SUPER_ADMIN"})
    @DisplayName("Security: SUPER_ADMIN GET entries returns 200 OK")
    void shouldAllowSuperAdminUser() throws Exception {
        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(sampleTitle);
        when(getScreenEntryListUseCase.execute(TITLE_ID)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/screen/titles/{titleId}/entries", TITLE_ID))
                .andExpect(status().isOk());
    }

    // --- POST MUTATION & CSRF CHECKS ---

    @Test
    @DisplayName("Security: Anonymous POST /admin/screen/titles/{titleId}/entries redirects to login")
    void shouldRedirectAnonymousPostToLogin() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries", TITLE_ID)
                        .with(csrf())
                        .param("title", "Tập 1")
                        .param("slug", "ep-1")
                        .param("type", "EPISODE")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER POST entries is redirected to /access-denied")
    void shouldDenyNonAdminUserPost() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries", TITLE_ID)
                        .with(csrf())
                        .param("title", "Tập 1")
                        .param("slug", "ep-1")
                        .param("type", "EPISODE")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST create entry WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectCreateWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries", TITLE_ID)
                        .param("title", "Tập 1")
                        .param("slug", "ep-1")
                        .param("type", "EPISODE")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST create entry WITH CSRF succeeds and redirects")
    void shouldAllowCreateWithCsrf() throws Exception {
        when(createScreenEntryUseCase.execute(any(CreateScreenEntryCommand.class))).thenReturn(sampleEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries", TITLE_ID)
                        .with(csrf())
                        .param("title", "Tập 1")
                        .param("slug", "ep-1")
                        .param("type", "EPISODE")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST update entry WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectUpdateWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/update", TITLE_ID, ENTRY_ID)
                        .param("title", "Tập 1 Sửa")
                        .param("slug", "ep-1-sua")
                        .param("type", "EPISODE")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST update entry WITH CSRF succeeds and redirects")
    void shouldAllowUpdateWithCsrf() throws Exception {
        when(updateScreenEntryUseCase.execute(any(UpdateScreenEntryCommand.class))).thenReturn(sampleEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/update", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .param("title", "Tập 1 Sửa")
                        .param("slug", "ep-1-sua")
                        .param("type", "EPISODE")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST publish entry WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectPublishWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/publish", TITLE_ID, ENTRY_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST publish entry WITH CSRF succeeds and redirects")
    void shouldAllowPublishWithCsrf() throws Exception {
        when(publishScreenEntryUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/publish", TITLE_ID, ENTRY_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST archive entry WITHOUT CSRF is rejected and redirected to /access-denied")
    void shouldRejectArchiveWithoutCsrf() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/archive", TITLE_ID, ENTRY_ID))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN POST archive entry WITH CSRF succeeds and redirects")
    void shouldAllowArchiveWithCsrf() throws Exception {
        when(archiveScreenEntryUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(sampleEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/archive", TITLE_ID, ENTRY_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("HTTP Binding Regression: Test A — ADMIN POST create without type parameter redirects to create form with errorMessage")
    void shouldRejectCreateWithoutTypeParameter() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries", TITLE_ID)
                        .with(csrf())
                        .param("title", "Tập 1")
                        .param("slug", "ep-1")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/new"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(result -> assertFalse(result.getFlashMap().containsKey("successMessage")));

        verify(createScreenEntryUseCase, never()).execute(any());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("HTTP Binding Regression: Test B — ADMIN POST update without type parameter redirects to edit form with errorMessage")
    void shouldRejectUpdateWithoutTypeParameter() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/update", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .param("title", "Tập 1 Mới")
                        .param("slug", "ep-1-updated")
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID + "/edit"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(result -> assertFalse(result.getFlashMap().containsKey("successMessage")));

        verify(updateScreenEntryUseCase, never()).execute(any());
    }

    @Test
    @DisplayName("Security: Unauthenticated request cannot post detach video form")
    void unauthenticated_cannotPostDetachForm() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: USER role cannot post detach video form")
    void regularUser_cannotPostDetachForm() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN without CSRF cannot post detach video form")
    void adminUser_withoutCsrf_cannotPostDetachForm() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN with CSRF can post detach video form and redirects to detail")
    void adminUser_withCsrf_canPostDetachForm() throws Exception {
        UUID currentVideoId = UUID.randomUUID();
        when(detachScreenEntryVideoUseCase.execute(TITLE_ID, ENTRY_ID, currentVideoId)).thenReturn(sampleEntry);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("expectedVideoMediaAssetId", currentVideoId.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID))
                .andExpect(flash().attributeExists("successMessage"));

        verify(detachScreenEntryVideoUseCase).execute(TITLE_ID, ENTRY_ID, currentVideoId);
    }
}
