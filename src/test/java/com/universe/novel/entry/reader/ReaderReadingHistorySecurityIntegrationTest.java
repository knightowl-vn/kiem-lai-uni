package com.universe.novel.entry.reader;

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
import com.universe.novel.application.exceptions.ChapterNotFoundException;
import com.universe.novel.application.reader.ListUserReadingHistoryUseCase;
import com.universe.novel.application.reader.RecordReadingHistoryCommand;
import com.universe.novel.application.reader.RecordReadingHistoryUseCase;
import com.universe.novel.contracts.dto.reader.ReaderReadingHistoryDTO;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ReaderReadingHistoryController.class)
@Import({SecurityBeanConfig.class, ReaderReadingHistorySecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class ReaderReadingHistorySecurityIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String USER_EMAIL = "history-reader@universe.local";

    @TestConfiguration
    static class TestSecurityConfig {
        @Bean
        public GoogleOAuthSuccessHandler googleOAuthSuccessHandler() {
            return new GoogleOAuthSuccessHandler(Mockito.mock(GoogleOAuthUserService.class));
        }

        @Bean
        public CustomAuthenticationFailureHandler authenticationFailureHandler() {
            return new CustomAuthenticationFailureHandler();
        }

        @Bean
        public AccountStatusFilter accountStatusFilter(SpringDataUserJpaRepository userRepository) {
            return new AccountStatusFilter(userRepository);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SpringDataUserJpaRepository springDataUserJpaRepository;

    @MockBean
    private RecordReadingHistoryUseCase recordReadingHistoryUseCase;

    @MockBean
    private ListUserReadingHistoryUseCase listUserReadingHistoryUseCase;

    @MockBean
    private UserIdentityContract userIdentityContract;

    @MockBean
    private CurrentUserQueryPort currentUserQueryPort;

    @MockBean
    private AuthenticatedEmailResolver authenticatedEmailResolver;

    @MockBean
    private UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        UserJpaEntity userEntity = new UserJpaEntity();
        userEntity.setId(USER_ID.toString());
        userEntity.setEmail(USER_EMAIL);
        userEntity.setStatus("ACTIVE");
        userEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(USER_EMAIL))
                .thenReturn(Optional.of(userEntity));
    }

    private UserDTO createTestUser() {
        return new UserDTO(
                USER_ID,
                USER_EMAIL,
                "History Reader User",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. POST history without CSRF is rejected by AccessDeniedHandler with redirect to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postHistoryWithoutCsrfShouldRedirectToAccessDenied() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/history"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verifyNoInteractions(recordReadingHistoryUseCase);
    }

    @Test
    @DisplayName("2. POST history anonymous with CSRF redirects to /login")
    void postHistoryAnonymousShouldRedirectToLogin() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/history").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verifyNoInteractions(recordReadingHistoryUseCase);
    }

    @Test
    @DisplayName("3. POST history by authenticated user with valid CSRF returns 204 No Content")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postHistoryAuthenticatedWithCsrfShouldReturn204() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/history").with(csrf()))
                .andExpect(status().isNoContent());

        verify(recordReadingHistoryUseCase).execute(new RecordReadingHistoryCommand(USER_ID, CHAPTER_ID));
    }

    @Test
    @DisplayName("4. POST history when chapter is not found returns 404 Not Found")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postHistoryWhenChapterNotFoundShouldReturn404() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        doThrow(new ChapterNotFoundException(CHAPTER_ID))
                .when(recordReadingHistoryUseCase)
                .execute(any());

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/history").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("5. GET /novel/history anonymous redirects to /login")
    void getHistoryAnonymousShouldRedirectToLogin() throws Exception {
        mockMvc.perform(get("/novel/history"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verifyNoInteractions(listUserReadingHistoryUseCase);
    }

    @Test
    @DisplayName("6. GET /novel/history authenticated renders history view with DTOs")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void getHistoryAuthenticatedShouldRenderView() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        List<ReaderReadingHistoryDTO> list = List.of(
                new ReaderReadingHistoryDTO(
                        CHAPTER_ID,
                        1,
                        "Chương 1",
                        "chuong-1",
                        "Quyển 1",
                        Instant.now()
                )
        );
        when(listUserReadingHistoryUseCase.execute(USER_ID)).thenReturn(list);

        mockMvc.perform(get("/novel/history"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/history"))
                .andExpect(model().attribute("historyList", list))
                .andExpect(model().attribute("pageTitle", "Lịch sử đọc"));
    }
}
