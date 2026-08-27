package com.universe.novel.entry.admin;

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
import com.universe.novel.application.chapter.reference.BindChapterWideWikiReferenceUseCase;
import com.universe.novel.application.chapter.reference.BindOccurrenceSpecificWikiReferenceUseCase;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceItemDTO;
import com.universe.novel.application.chapter.reference.ChapterWikiReferenceStatus;
import com.universe.novel.application.chapter.reference.RemoveChapterWikiReferenceUseCase;
import com.universe.novel.domain.reference.ChapterWikiReferenceScope;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminNovelChapterWikiReferenceCommandController.class)
@Import({SecurityBeanConfig.class, AdminNovelChapterWikiReferenceCommandSecurityIntegrationTest.TestSecurityConfig.class})
@TestPropertySource(properties = {
        "security.remember-me.key=test-secret-key-1234567890123456",
        "security.remember-me.secure-cookie=false"
})
class AdminNovelChapterWikiReferenceCommandSecurityIntegrationTest {

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REFERENCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
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
    private BindChapterWideWikiReferenceUseCase bindChapterWideWikiReferenceUseCase;

    @MockBean
    private BindOccurrenceSpecificWikiReferenceUseCase bindOccurrenceSpecificWikiReferenceUseCase;

    @MockBean
    private RemoveChapterWikiReferenceUseCase removeChapterWikiReferenceUseCase;

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

        UserDTO adminUser = new UserDTO(
                ADMIN_ID, ADMIN_EMAIL, "Admin User", null, "ACTIVE", "ADMIN", Instant.now()
        );
        when(userIdentityContract.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(adminUser));
        when(authenticatedEmailResolver.require(any())).thenReturn(ADMIN_EMAIL);
    }

    @Test
    @DisplayName("Security: Anonymous POST /admin/novel/chapters/{id}/wiki-references/chapter-wide redirects to login")
    void shouldRedirectAnonymousToLogin() throws Exception {
        mockMvc.perform(post("/admin/novel/chapters/{id}/wiki-references/chapter-wide", CHAPTER_ID)
                        .with(csrf())
                        .param("term", "Trần Bình An")
                        .param("wikiArticleId", ARTICLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL, roles = {"USER"})
    @DisplayName("Security: Non-admin USER POST is redirected to /access-denied")
    void shouldDenyNonAdminUser() throws Exception {
        mockMvc.perform(post("/admin/novel/chapters/{id}/wiki-references/chapter-wide", CHAPTER_ID)
                        .with(csrf())
                        .param("term", "Trần Bình An")
                        .param("wikiArticleId", ARTICLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security: Missing CSRF token on POST is rejected by AccessDeniedHandler and redirected to /access-denied")
    void shouldRejectMissingCsrf() throws Exception {
        mockMvc.perform(post("/admin/novel/chapters/{id}/wiki-references/chapter-wide", CHAPTER_ID)
                        .param("term", "Trần Bình An")
                        .param("wikiArticleId", ARTICLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/access-denied"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security & Workflow: Admin POST chapter-wide with CSRF succeeds and redirects back to management page")
    void shouldAllowAdminChapterWideWithCsrf() throws Exception {
        ChapterWikiReferenceItemDTO resultDTO = new ChapterWikiReferenceItemDTO(
                REFERENCE_ID, CHAPTER_ID, "Trần Bình An", "trần bình an",
                ChapterWikiReferenceScope.CHAPTER_WIDE, 0, null, null, 1L,
                ARTICLE_ID, ChapterWikiReferenceStatus.ACTIVE, null, ADMIN_ID, ADMIN_ID,
                Instant.now(), Instant.now()
        );
        when(bindChapterWideWikiReferenceUseCase.execute(any())).thenReturn(resultDTO);

        mockMvc.perform(post("/admin/novel/chapters/{id}/wiki-references/chapter-wide", CHAPTER_ID)
                        .with(csrf())
                        .param("term", "Trần Bình An")
                        .param("wikiArticleId", ARTICLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security & Workflow: Admin POST occurrence with CSRF succeeds and redirects back")
    void shouldAllowAdminOccurrenceWithCsrf() throws Exception {
        ChapterWikiReferenceItemDTO resultDTO = new ChapterWikiReferenceItemDTO(
                REFERENCE_ID, CHAPTER_ID, "Đạo Đầu", "đạo đầu",
                ChapterWikiReferenceScope.OCCURRENCE_SPECIFIC, 1, "ngữ cảnh", 1L, 1L,
                ARTICLE_ID, ChapterWikiReferenceStatus.ACTIVE, null, ADMIN_ID, ADMIN_ID,
                Instant.now(), Instant.now()
        );
        when(bindOccurrenceSpecificWikiReferenceUseCase.execute(any())).thenReturn(resultDTO);

        mockMvc.perform(post("/admin/novel/chapters/{id}/wiki-references/occurrence", CHAPTER_ID)
                        .with(csrf())
                        .param("term", "Đạo Đầu")
                        .param("occurrenceIndex", "1")
                        .param("contextSnippet", "ngữ cảnh")
                        .param("wikiArticleId", ARTICLE_ID.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("Security & Workflow: Admin POST remove with CSRF succeeds and redirects back")
    void shouldAllowAdminRemoveWithCsrf() throws Exception {
        when(removeChapterWikiReferenceUseCase.execute(any())).thenReturn(true);

        mockMvc.perform(post("/admin/novel/chapters/{chapterId}/wiki-references/{referenceId}/delete", CHAPTER_ID, REFERENCE_ID)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/novel/chapters/" + CHAPTER_ID + "/wiki-references"))
                .andExpect(flash().attributeExists("successMessage"));
    }
}
