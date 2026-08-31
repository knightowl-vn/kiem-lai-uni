package com.universe.novel.entry.admin;

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
import com.universe.novel.application.chapter.reference.SearchTargetWikiArticlesUseCase;
import com.universe.novel.application.chapter.reference.TargetWikiArticleSearchItemDTO;
import com.universe.novel.application.chapter.reference.TargetWikiArticleSearchResultDTO;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminNovelChapterWikiReferenceSearchController.class)
@Import({SecurityBeanConfig.class, AdminNovelChapterWikiReferenceSearchSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminNovelChapterWikiReferenceSearchSecurityIntegrationTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String ADMIN_EMAIL = "admin@universe.local";
    private static final String USER_EMAIL = "user@universe.local";

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
    private SearchTargetWikiArticlesUseCase searchTargetWikiArticlesUseCase;

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
        UserJpaEntity adminEntity = new UserJpaEntity();
        adminEntity.setId(ADMIN_ID.toString());
        adminEntity.setEmail(ADMIN_EMAIL);
        adminEntity.setStatus("ACTIVE");
        adminEntity.setRole(UserRole.ADMIN);

        UserJpaEntity regularUserEntity = new UserJpaEntity();
        regularUserEntity.setId(UUID.randomUUID().toString());
        regularUserEntity.setEmail(USER_EMAIL);
        regularUserEntity.setStatus("ACTIVE");
        regularUserEntity.setRole(UserRole.USER);

        when(springDataUserJpaRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminEntity));
        when(springDataUserJpaRepository.findByEmail(USER_EMAIL)).thenReturn(Optional.of(regularUserEntity));
    }

    @Test
    @DisplayName("Security: Anonymous GET /admin/novel/chapters/{id}/wiki-references/search-targets redirects to login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(get("/admin/novel/chapters/{id}/wiki-references/search-targets", CHAPTER_ID)
                        .param("q", "Trần Bình An"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER GET /admin/novel/chapters/{id}/wiki-references/search-targets is redirected to /access-denied")
    void shouldDenyNonAdminUser() throws Exception {
        mockMvc.perform(get("/admin/novel/chapters/{id}/wiki-references/search-targets", CHAPTER_ID)
                        .param("q", "Trần Bình An"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: ADMIN GET /admin/novel/chapters/{id}/wiki-references/search-targets returns 200 OK and JSON payload")
    void shouldAllowAdminAccessAndReturnJson() throws Exception {
        TargetWikiArticleSearchItemDTO item = new TargetWikiArticleSearchItemDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "CHARACTER",
                "tran-binh-an",
                "Nhân vật chính của Kiếm Lai.",
                null
        );
        TargetWikiArticleSearchResultDTO searchResult = new TargetWikiArticleSearchResultDTO(
                "Trần Bình An",
                List.of(item)
        );

        when(searchTargetWikiArticlesUseCase.execute("Trần Bình An")).thenReturn(searchResult);

        mockMvc.perform(get("/admin/novel/chapters/{id}/wiki-references/search-targets", CHAPTER_ID)
                        .param("q", "Trần Bình An")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("Trần Bình An"))
                .andExpect(jsonPath("$.items[0].id").value(ARTICLE_ID.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Trần Bình An"))
                .andExpect(jsonPath("$.items[0].articleType").value("CHARACTER"))
                .andExpect(jsonPath("$.items[0].slug").value("tran-binh-an"))
                .andExpect(jsonPath("$.items[0].summary").value("Nhân vật chính của Kiếm Lai."))
                .andExpect(jsonPath("$.items[0].matchedAlias").doesNotExist());
    }
}
