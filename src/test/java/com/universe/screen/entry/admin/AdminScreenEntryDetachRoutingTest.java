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
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.usecase.ArchiveScreenEntryUseCase;
import com.universe.screen.application.usecase.AttachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.CreateScreenEntryUseCase;
import com.universe.screen.application.usecase.DetachScreenEntryVideoUseCase;
import com.universe.screen.application.usecase.GetScreenEntryDetailUseCase;
import com.universe.screen.application.usecase.GetScreenTitleDetailUseCase;
import com.universe.screen.application.usecase.PublishScreenEntryUseCase;
import com.universe.screen.application.usecase.ReplaceScreenEntryVideoUseCase;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Combined WebMvcTest registering both {@link AdminScreenEntryCommandController} and
 * {@link AdminScreenEntryVideoController} in a single Spring application context.
 *
 * <p>Proves:
 * <ul>
 *   <li>Finding E2L1-01 resolution: No ambiguous mapping error on context startup.</li>
 *   <li>HTML Form requests (application/x-www-form-urlencoded) unambiguously route to AdminScreenEntryCommandController.</li>
 *   <li>REST JSON requests (application/json) unambiguously route to AdminScreenEntryVideoController.</li>
 *   <li>Both endpoints enforce ADMIN role authorization and CSRF protection.</li>
 *   <li>Finding E2L1-02 resolution: Both reject missing/null expectedVideoMediaAssetId safely.</li>
 * </ul>
 */
@WebMvcTest({AdminScreenEntryCommandController.class, AdminScreenEntryVideoController.class})
@Import({SecurityBeanConfig.class, AdminScreenEntryDetachRoutingTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
@DisplayName("AdminScreenEntryDetachRoutingTest — Multi-Controller Media-Type Route Disambiguation")
class AdminScreenEntryDetachRoutingTest {

    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";
    private static final UUID TITLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTRY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

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

    // Security & User Mocks
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

    // Command Controller Mocks
    @MockBean
    private CreateScreenEntryUseCase createScreenEntryUseCase;

    @MockBean
    private UpdateScreenEntryUseCase updateScreenEntryUseCase;

    @MockBean
    private PublishScreenEntryUseCase publishScreenEntryUseCase;

    @MockBean
    private ArchiveScreenEntryUseCase archiveScreenEntryUseCase;

    // Video Controller Mocks
    @MockBean
    private GetScreenTitleDetailUseCase getScreenTitleDetailUseCase;

    @MockBean
    private GetScreenEntryDetailUseCase getScreenEntryDetailUseCase;

    @MockBean
    private AttachScreenEntryVideoUseCase attachScreenEntryVideoUseCase;

    @MockBean
    private ReplaceScreenEntryVideoUseCase replaceScreenEntryVideoUseCase;

    @MockBean
    private MediaContract mediaContract;

    // Shared Detach Use Case Mock
    @MockBean
    private DetachScreenEntryVideoUseCase detachScreenEntryVideoUseCase;

    private ScreenEntryDTO defaultEntryDTO;

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

        UserJpaEntity userEntity = new UserJpaEntity();
        userEntity.setId(UUID.randomUUID().toString());
        userEntity.setEmail(USER_EMAIL);
        userEntity.setDisplayName("Regular User");
        userEntity.setPublicHandle("regular_user");
        userEntity.setStatus("ACTIVE");
        userEntity.setRole(UserRole.USER);
        when(springDataUserJpaRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(userEntity));

        Instant now = Instant.now();
        ScreenTitleDTO titleDTO = new ScreenTitleDTO(
                TITLE_ID, "title-slug", "Title", null, null, ScreenStructure.FEATURE,
                ScreenPresentation.LIVE_ACTION, null, null, ScreenPublicationStatus.PUBLISHED,
                null, now, now
        );

        defaultEntryDTO = new ScreenEntryDTO(
                ENTRY_ID,
                TITLE_ID,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1",
                "Mô tả tập 1",
                ASSET_ID,
                null,
                1200L,
                ScreenPublicationStatus.ARCHIVED,
                null,
                now,
                now
        );

        when(getScreenTitleDetailUseCase.execute(TITLE_ID)).thenReturn(titleDTO);
        when(getScreenEntryDetailUseCase.execute(TITLE_ID, ENTRY_ID)).thenReturn(defaultEntryDTO);
    }

    // =========================================================================
    // 1. HTML FORM ROUTING (AdminScreenEntryCommandController)
    // =========================================================================

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("HTML Form POST routes to AdminScreenEntryCommandController, executes detach, and redirects")
    void htmlFormPost_RoutesToCommandController_AndRedirects() throws Exception {
        ScreenEntryDTO detachedDTO = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả tập 1", null, null, 1200L, ScreenPublicationStatus.ARCHIVED, null,
                Instant.now(), Instant.now()
        );
        when(detachScreenEntryVideoUseCase.execute(TITLE_ID, ENTRY_ID, ASSET_ID)).thenReturn(detachedDTO);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("expectedVideoMediaAssetId", ASSET_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID))
                .andExpect(flash().attributeExists("successMessage"));

        verify(detachScreenEntryVideoUseCase).execute(TITLE_ID, ENTRY_ID, ASSET_ID);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("HTML Form POST without expectedVideoMediaAssetId redirects with error flash attribute")
    void htmlFormPost_MissingExpectedId_RedirectsWithError() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/screen/titles/" + TITLE_ID + "/entries/" + ENTRY_ID))
                .andExpect(flash().attribute("errorMessage", "Không tìm thấy mã video hiện tại để xác thực thao tác gỡ bỏ."));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    // =========================================================================
    // 2. JSON REST ROUTING (AdminScreenEntryVideoController)
    // =========================================================================

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("JSON POST routes to AdminScreenEntryVideoController, executes detach, and returns JSON 200 OK")
    void jsonPost_RoutesToVideoController_AndReturnsJson() throws Exception {
        ScreenEntryDTO detachedDTO = new ScreenEntryDTO(
                ENTRY_ID, TITLE_ID, "tap-1", ScreenEntryType.EPISODE, 1, "Tập 1",
                "Mô tả tập 1", null, null, 1200L, ScreenPublicationStatus.ARCHIVED, null,
                Instant.now(), Instant.now()
        );
        when(detachScreenEntryVideoUseCase.execute(TITLE_ID, ENTRY_ID, ASSET_ID)).thenReturn(detachedDTO);

        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVideoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DETACHED"))
                .andExpect(jsonPath("$.entryId").value(ENTRY_ID.toString()))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(detachScreenEntryVideoUseCase).execute(TITLE_ID, ENTRY_ID, ASSET_ID);
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("JSON POST without expectedVideoMediaAssetId returns 400 Bad Request")
    void jsonPost_MissingExpectedId_Returns400() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("expectedVideoMediaAssetId không được để trống khi thực hiện gỡ bỏ video."));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    // =========================================================================
    // 3. SECURITY & CSRF BOUNDARIES
    // =========================================================================

    @Test
    @DisplayName("Unauthenticated HTML Form POST redirects to login")
    void unauthenticated_HtmlForm_RedirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("expectedVideoMediaAssetId", ASSET_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("Unauthenticated JSON POST redirects to login")
    void unauthenticated_JsonPost_RedirectsToLogin() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVideoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("HTML Form POST without CSRF token is redirected to /access-denied")
    void csrfMissing_HtmlForm_IsForbidden() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("expectedVideoMediaAssetId", ASSET_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("JSON POST without CSRF token is redirected to /access-denied")
    void csrfMissing_JsonPost_IsForbidden() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVideoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("HTML Form POST with non-admin USER role is forbidden (403)")
    void userRole_HtmlForm_IsForbidden() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("expectedVideoMediaAssetId", ASSET_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("JSON POST with non-admin USER role is forbidden (403)")
    void userRole_JsonPost_IsForbidden() throws Exception {
        mockMvc.perform(post("/admin/screen/titles/{titleId}/entries/{entryId}/video/detach", TITLE_ID, ENTRY_ID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVideoMediaAssetId", ASSET_ID.toString()
                        ))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verify(detachScreenEntryVideoUseCase, never()).execute(any(), any(), any());
    }
}
