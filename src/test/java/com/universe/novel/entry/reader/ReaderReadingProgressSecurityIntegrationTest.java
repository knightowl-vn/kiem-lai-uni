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
import com.universe.novel.application.reader.RecordReadingProgressCommand;
import com.universe.novel.application.reader.RecordReadingProgressUseCase;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReaderReadingProgressController.class)
@Import({SecurityBeanConfig.class, ReaderReadingProgressSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class ReaderReadingProgressSecurityIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID CHAPTER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final String USER_EMAIL = "reader@universe.local";

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
    private RecordReadingProgressUseCase recordReadingProgressUseCase;

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

    @Test
    @DisplayName("POST progress without CSRF is rejected by AccessDeniedHandler with redirect to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postProgressWithoutCsrfShouldRedirectToAccessDenied() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/progress"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verifyNoInteractions(recordReadingProgressUseCase);
    }

    @Test
    @DisplayName("POST progress by anonymous user redirects to /login")
    void postProgressAnonymousShouldRedirectToLogin() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/progress").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verifyNoInteractions(recordReadingProgressUseCase);
    }

    @Test
    @DisplayName("POST progress by authenticated user with valid CSRF returns 204 No Content")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postProgressAuthenticatedWithCsrfShouldReturn204() throws Exception {
        UserDTO user = new UserDTO(
                USER_ID,
                USER_EMAIL,
                "Reader User",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );

        when(authenticatedEmailResolver.resolve(any()))
                .thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL))
                .thenReturn(Optional.of(user));

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/progress").with(csrf()))
                .andExpect(status().isNoContent());

        verify(recordReadingProgressUseCase).execute(new RecordReadingProgressCommand(USER_ID, CHAPTER_ID));
    }

    @Test
    @DisplayName("POST progress when chapter is not found returns 404 Not Found")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postProgressWhenChapterNotFoundShouldReturn404() throws Exception {
        UserDTO user = new UserDTO(
                USER_ID,
                USER_EMAIL,
                "Reader User",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );

        when(authenticatedEmailResolver.resolve(any()))
                .thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL))
                .thenReturn(Optional.of(user));
        doThrow(new ChapterNotFoundException(CHAPTER_ID))
                .when(recordReadingProgressUseCase)
                .execute(any());

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/progress").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
