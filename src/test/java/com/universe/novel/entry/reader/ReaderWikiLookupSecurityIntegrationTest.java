package com.universe.novel.entry.reader;

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
import com.universe.novel.application.reader.ChapterWikiReferenceResolutionSource;
import com.universe.novel.application.reader.LookupContextualWikiUseCase;
import com.universe.novel.application.reader.ReaderChapterWikiResolutionResult;
import com.universe.novel.application.reader.ReaderWikiLookupItem;
import com.universe.novel.application.reader.ReaderWikiLookupResult;
import com.universe.novel.application.reader.ResolveReaderChapterWikiQuery;
import com.universe.novel.application.reader.ResolveReaderChapterWikiUseCase;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReaderWikiLookupController.class)
@Import({SecurityBeanConfig.class, ReaderWikiLookupSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class ReaderWikiLookupSecurityIntegrationTest {

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
    private LookupContextualWikiUseCase lookupContextualWikiUseCase;

    @MockBean
    private ResolveReaderChapterWikiUseCase resolveReaderChapterWikiUseCase;

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
        userEntity.setId(UUID.randomUUID().toString());
        userEntity.setEmail(USER_EMAIL);
        userEntity.setStatus("ACTIVE");
        userEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(USER_EMAIL))
                .thenReturn(Optional.of(userEntity));
    }

    @Test
    @DisplayName("1. Anonymous GET /novel/api/wiki/lookup with only q returns 200 OK with GLOBAL_LOOKUP")
    void shouldAllowAnonymousAccessWithOnlyQuery() throws Exception {
        UUID id = UUID.randomUUID();
        ReaderWikiLookupResult lookupResult = new ReaderWikiLookupResult(
                "Trần Bình An",
                true,
                List.of(new ReaderWikiLookupItem(
                        id,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính của Kiếm Lai",
                        "Tiểu Bình An"
                ))
        );

        when(lookupContextualWikiUseCase.execute("Trần Bình An")).thenReturn(lookupResult);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", "Trần Bình An")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("Trần Bình An"))
                .andExpect(jsonPath("$.source").value("GLOBAL_LOOKUP"))
                .andExpect(jsonPath("$.hasExactMatch").value(true))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Trần Bình An"))
                .andExpect(jsonPath("$.items[0].articleType").value("CHARACTER"))
                .andExpect(jsonPath("$.items[0].slug").value("tran-binh-an"))
                .andExpect(jsonPath("$.items[0].summary").value("Nhân vật chính của Kiếm Lai"))
                .andExpect(jsonPath("$.items[0].matchedAlias").value("Tiểu Bình An"))
                .andExpect(jsonPath("$.occurrenceIndex").doesNotExist())
                .andExpect(jsonPath("$.boundReferenceId").doesNotExist())
                .andExpect(jsonPath("$.contentVersion").doesNotExist())
                .andExpect(jsonPath("$.contextSnippet").doesNotExist());

        verify(lookupContextualWikiUseCase).execute("Trần Bình An");
    }

    @Test
    @DisplayName("2. GET /novel/api/wiki/lookup with chapterId and occurrence resolves OCCURRENCE_BINDING")
    void shouldResolveOccurrenceBinding() throws Exception {
        UUID chapterId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        ReaderChapterWikiResolutionResult result = new ReaderChapterWikiResolutionResult(
                "Trần Bình An",
                ChapterWikiReferenceResolutionSource.OCCURRENCE_BINDING,
                true,
                List.of(new ReaderWikiLookupItem(
                        articleId,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính của Kiếm Lai"
                )),
                2
        );

        when(resolveReaderChapterWikiUseCase.execute(new ResolveReaderChapterWikiQuery(chapterId, "Trần Bình An", 2)))
                .thenReturn(result);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", "Trần Bình An")
                        .param("chapterId", chapterId.toString())
                        .param("occurrence", "2")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("Trần Bình An"))
                .andExpect(jsonPath("$.source").value("OCCURRENCE_BINDING"))
                .andExpect(jsonPath("$.hasExactMatch").value(true))
                .andExpect(jsonPath("$.occurrenceIndex").value(2))
                .andExpect(jsonPath("$.items[0].id").value(articleId.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Trần Bình An"))
                .andExpect(jsonPath("$.boundReferenceId").doesNotExist())
                .andExpect(jsonPath("$.contentVersion").doesNotExist());

        verify(resolveReaderChapterWikiUseCase).execute(new ResolveReaderChapterWikiQuery(chapterId, "Trần Bình An", 2));
    }

    @Test
    @DisplayName("3. GET /novel/api/wiki/lookup with chapterId and without occurrence resolves CHAPTER_WIDE_BINDING")
    void shouldResolveChapterWideBinding() throws Exception {
        UUID chapterId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        ReaderChapterWikiResolutionResult result = new ReaderChapterWikiResolutionResult(
                "Trần Bình An",
                ChapterWikiReferenceResolutionSource.CHAPTER_WIDE_BINDING,
                true,
                List.of(new ReaderWikiLookupItem(
                        articleId,
                        "Trần Bình An",
                        "CHARACTER",
                        "tran-binh-an",
                        "Nhân vật chính của Kiếm Lai"
                )),
                null
        );

        when(resolveReaderChapterWikiUseCase.execute(new ResolveReaderChapterWikiQuery(chapterId, "Trần Bình An", null)))
                .thenReturn(result);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", "Trần Bình An")
                        .param("chapterId", chapterId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.query").value("Trần Bình An"))
                .andExpect(jsonPath("$.source").value("CHAPTER_WIDE_BINDING"))
                .andExpect(jsonPath("$.hasExactMatch").value(true))
                .andExpect(jsonPath("$.occurrenceIndex").doesNotExist())
                .andExpect(jsonPath("$.items[0].id").value(articleId.toString()))
                .andExpect(jsonPath("$.boundReferenceId").doesNotExist());

        verify(resolveReaderChapterWikiUseCase).execute(new ResolveReaderChapterWikiQuery(chapterId, "Trần Bình An", null));
    }

    @Test
    @DisplayName("4. Authenticated GET /novel/api/wiki/lookup returns 200 OK with JSON response")
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    void shouldAllowAuthenticatedAccess() throws Exception {
        ReaderWikiLookupResult lookupResult = new ReaderWikiLookupResult(
                "Lạc Phách Sơn",
                false,
                List.of()
        );

        when(lookupContextualWikiUseCase.execute("Lạc Phách Sơn")).thenReturn(lookupResult);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", "Lạc Phách Sơn")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("Lạc Phách Sơn"))
                .andExpect(jsonPath("$.source").value("GLOBAL_LOOKUP"))
                .andExpect(jsonPath("$.hasExactMatch").value(false))
                .andExpect(jsonPath("$.items").isEmpty());

        verify(lookupContextualWikiUseCase).execute("Lạc Phách Sơn");
    }

    @Test
    @DisplayName("5. Blank query returns 200 OK with empty result")
    void shouldReturnEmptyForBlankQuery() throws Exception {
        ReaderWikiLookupResult emptyResult = new ReaderWikiLookupResult("", false, List.of());
        when(lookupContextualWikiUseCase.execute("")).thenReturn(emptyResult);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", "")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value(""))
                .andExpect(jsonPath("$.source").value("GLOBAL_LOOKUP"))
                .andExpect(jsonPath("$.hasExactMatch").value(false))
                .andExpect(jsonPath("$.items").isEmpty());

        verify(lookupContextualWikiUseCase).execute("");
    }

    @Test
    @DisplayName("6. Oversized query returns 200 OK with empty result")
    void shouldReturnEmptyForOversizedQuery() throws Exception {
        String longQuery = "a".repeat(150);
        ReaderWikiLookupResult emptyResult = new ReaderWikiLookupResult(longQuery, false, List.of());
        when(lookupContextualWikiUseCase.execute(longQuery)).thenReturn(emptyResult);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", longQuery)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value(longQuery))
                .andExpect(jsonPath("$.source").value("GLOBAL_LOOKUP"))
                .andExpect(jsonPath("$.hasExactMatch").value(false))
                .andExpect(jsonPath("$.items").isEmpty());

        verify(lookupContextualWikiUseCase).execute(longQuery);
    }

    @Test
    @DisplayName("7. Special characters are safely passed through to use case")
    void shouldPassSpecialCharactersSafely() throws Exception {
        String specialQuery = "50%_discount\\test";
        ReaderWikiLookupResult lookupResult = new ReaderWikiLookupResult(specialQuery, false, List.of());
        when(lookupContextualWikiUseCase.execute(specialQuery)).thenReturn(lookupResult);

        mockMvc.perform(get("/novel/api/wiki/lookup")
                        .param("q", specialQuery)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value(specialQuery))
                .andExpect(jsonPath("$.source").value("GLOBAL_LOOKUP"));

        verify(lookupContextualWikiUseCase).execute(specialQuery);
    }
}