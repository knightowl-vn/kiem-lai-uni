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
import com.universe.novel.application.reader.BookmarkChapterCommand;
import com.universe.novel.application.reader.BookmarkChapterUseCase;
import com.universe.novel.application.reader.ListUserBookmarkedChaptersUseCase;
import com.universe.novel.application.reader.UnbookmarkChapterCommand;
import com.universe.novel.application.reader.UnbookmarkChapterUseCase;
import com.universe.novel.contracts.dto.reader.ReaderBookmarkedChapterDTO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ReaderBookmarkController.class)
@Import({SecurityBeanConfig.class, ReaderBookmarkSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class ReaderBookmarkSecurityIntegrationTest {

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
    private BookmarkChapterUseCase bookmarkChapterUseCase;

    @MockBean
    private UnbookmarkChapterUseCase unbookmarkChapterUseCase;

    @MockBean
    private ListUserBookmarkedChaptersUseCase listUserBookmarkedChaptersUseCase;

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
                "Reader User",
                null,
                "ACTIVE",
                "USER",
                Instant.now()
        );
    }

    @Test
    @DisplayName("1. POST bookmark without CSRF is rejected by AccessDeniedHandler with redirect to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postBookmarkWithoutCsrfShouldRedirectToAccessDenied() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/bookmark"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verifyNoInteractions(bookmarkChapterUseCase);
    }

    @Test
    @DisplayName("2. DELETE bookmark without CSRF is rejected by AccessDeniedHandler with redirect to /access-denied")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void deleteBookmarkWithoutCsrfShouldRedirectToAccessDenied() throws Exception {
        mockMvc.perform(delete("/novel/chapters/" + CHAPTER_ID + "/bookmark"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));

        verifyNoInteractions(unbookmarkChapterUseCase);
    }

    @Test
    @DisplayName("3. POST bookmark anonymous with CSRF redirects to /login")
    void postBookmarkAnonymousShouldRedirectToLogin() throws Exception {
        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/bookmark").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verifyNoInteractions(bookmarkChapterUseCase);
    }

    @Test
    @DisplayName("4. DELETE bookmark anonymous with CSRF redirects to /login")
    void deleteBookmarkAnonymousShouldRedirectToLogin() throws Exception {
        mockMvc.perform(delete("/novel/chapters/" + CHAPTER_ID + "/bookmark").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verifyNoInteractions(unbookmarkChapterUseCase);
    }

    @Test
    @DisplayName("5. POST bookmark by authenticated user with valid CSRF returns 204 No Content")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postBookmarkAuthenticatedWithCsrfShouldReturn204() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/bookmark").with(csrf()))
                .andExpect(status().isNoContent());

        verify(bookmarkChapterUseCase).execute(new BookmarkChapterCommand(USER_ID, CHAPTER_ID));
    }

    @Test
    @DisplayName("6. DELETE bookmark by authenticated user with valid CSRF returns 204 No Content")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void deleteBookmarkAuthenticatedWithCsrfShouldReturn204() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        mockMvc.perform(delete("/novel/chapters/" + CHAPTER_ID + "/bookmark").with(csrf()))
                .andExpect(status().isNoContent());

        verify(unbookmarkChapterUseCase).execute(new UnbookmarkChapterCommand(USER_ID, CHAPTER_ID));
    }

    @Test
    @DisplayName("7. POST bookmark when chapter is not found returns 404 Not Found")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postBookmarkWhenChapterNotFoundShouldReturn404() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        doThrow(new ChapterNotFoundException(CHAPTER_ID))
                .when(bookmarkChapterUseCase)
                .execute(any());

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/bookmark").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("8. POST bookmark when limit exceeded returns 409 Conflict")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void postBookmarkWhenLimitExceededShouldReturn409() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));
        doThrow(new com.universe.novel.application.exceptions.BookmarkLimitExceededException(USER_ID, 100))
                .when(bookmarkChapterUseCase)
                .execute(any());

        mockMvc.perform(post("/novel/chapters/" + CHAPTER_ID + "/bookmark").with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("9. GET /novel/bookmarks anonymous redirects to /login")
    void getBookmarksAnonymousShouldRedirectToLogin() throws Exception {
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/novel/bookmarks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verifyNoInteractions(listUserBookmarkedChaptersUseCase);
    }

    @Test
    @DisplayName("9. GET /novel/bookmarks authenticated renders bookmarks view with DTOs")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void getBookmarksAuthenticatedShouldRenderView() throws Exception {
        UserDTO user = createTestUser();
        when(authenticatedEmailResolver.resolve(any())).thenReturn(Optional.of(USER_EMAIL));
        when(userIdentityContract.findByEmail(USER_EMAIL)).thenReturn(Optional.of(user));

        List<ReaderBookmarkedChapterDTO> list = List.of(
                new ReaderBookmarkedChapterDTO(
                        CHAPTER_ID,
                        1,
                        "Chương 1",
                        "chuong-1",
                        "Quyển 1",
                        Instant.now()
                )
        );
        when(listUserBookmarkedChaptersUseCase.execute(USER_ID)).thenReturn(list);

        mockMvc.perform(get("/novel/bookmarks"))
                .andExpect(status().isOk())
                .andExpect(view().name("novel/reader/bookmarks"))
                .andExpect(model().attribute("bookmarks", list))
                .andExpect(model().attribute("pageTitle", "Dấu trang chương"));
    }
}
